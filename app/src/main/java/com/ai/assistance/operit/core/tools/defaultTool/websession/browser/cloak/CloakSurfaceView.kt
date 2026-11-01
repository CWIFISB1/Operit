package com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.TextureView
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

/**
 * Cloak 内核的可视化画布。
 *
 * 背景：CloakBrowser 跑在 proot 里，画面在 Xvfb 内，Android 侧没有原生 View 可贴。
 * 官方给的两条路是「CDP 截图」和「VNC」，这里走 CDP 截图轮询 —— 依赖最少，复用已有 CDP 通道。
 *
 * 分工：
 *  - 本类只负责「把远端画面画出来」和「把本地手指动作翻译成 CDP 输入事件」。
 *  - 浏览器语义（导航、快照、工具调用）仍走 CloakBrowserTools / BrowserEngineBridge。
 *
 * [TEXTUREVIEW-FIX] 2026-11-01 真机第三次复测：
 * 原来继承 SurfaceView，日志已出现「Cloak 画面流启动」，说明 surfaceCreated 正常、
 * 抓帧线程也在跑，但用户看到的仍是一片白。
 * 根因是 SurfaceView 的图层语义：它由 SurfaceFlinger 单独合成在应用窗口之下，
 * 默认 ZOrder 在 View 树底部。Compose 的 AndroidView 外层 Box 铺了不透明背景
 * （MaterialTheme.colorScheme.background），于是把 SurfaceView 整个盖住了 ——
 * 画面在画，但被遮住看不见。
 * 常规解法是 setZOrderOnTop(true)，但那会把 SurfaceView 顶到所有 UI 之上，
 * 连地址栏、工具栏都会被盖住，还得手工处理透明与层级，非常脆。
 * 改继承 TextureView：它作为普通 View 参与 View 树的正常合成，
 * 不存在图层穿透问题，也不需要任何 ZOrder 技巧。
 * 代价是多一次 GPU 拷贝，对 15fps 截图流完全可以接受。
 */
internal class CloakSurfaceView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "CloakSurface"
        /** 截图轮询间隔。15fps 足够看清滚动与输入反馈，又不至于把 proot 的 CPU 打满。 */
        private const val FRAME_INTERVAL_MS = 66L
        /** 远端 CSS 视口兜底值，仅在某帧解码前未知时使用；实际以帧宽高为准。 */
        private const val FALLBACK_WIDTH = 1280
        private const val FALLBACK_HEIGHT = 800
    }

    /** 由外部注入：拿到当前会话的 CDP 客户端。返回 null 表示引擎还没起来。 */
    var clientProvider: (() -> CloakCdpClient?)? = null

    /** 由外部注入：当前会话 id，用于日志定位。 */
    var sessionId: String = ""

    /**
     * 抓帧线程池。
     *
     * 必须可重建：TextureView 的 surface 会随页面切换 / 息屏 / 返回被销毁再创建，
     * 而 shutdown 过的 executor 不能再提交任务，重建是唯一安全做法。
     */
    @Volatile private var frameThread: ScheduledExecutorService? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private val running = AtomicBoolean(false)
    /** 输入派发期间暂停抓帧，避免把用户的按下/抬起吃掉。 */
    private val inputBusy = AtomicBoolean(false)

    @Volatile private var frame: Bitmap? = null
    @Volatile private var lastFrameHash: Int = 0
    @Volatile private var lastError: String? = null
    @Volatile private var attached = false

    /** 诊断用：确认「抓到了帧」和「画上去了」各发生过一次，不刷屏。 */
    @Volatile private var loggedFirstFrame = false
    @Volatile private var loggedFirstDraw = false
    @Volatile private var loggedNoClient = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val destRect = RectF()
    private val srcRect = Rect()
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false

    init {
        surfaceTextureListener = this
        // TextureView 默认不透明即可；这里显式置白，未出帧时是白底而不是黑底。
        isOpaque = true
        setBackgroundColor(Color.WHITE)
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        attached = true
        AppLogger.i(TAG, "TextureView surface 就绪 ${width}x$height session=$sessionId")
        startLoop()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        AppLogger.i(TAG, "TextureView 尺寸变化 ${width}x$height")
        redraw()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        attached = false
        stopLoop()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    fun startLoop() {
        if (!running.compareAndSet(false, true)) return
        ensureExecutor()
        runCatching {
            frameThread?.scheduleWithFixedDelay(
                { pullFrame() },
                0L,
                FRAME_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        }.onFailure { AppLogger.w(TAG, "启动画面流失败: ${it.message}") }
        AppLogger.i(TAG, "Cloak 画面流启动 session=$sessionId")
    }

    /** 需要时创建抓帧 executor。已 shutdown 的实例会被丢弃重建。 */
    private fun ensureExecutor() {
        val current = frameThread
        if (current != null && !current.isShutdown) return
        frameThread =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "cloak-frame").apply { isDaemon = true }
            }
    }

    fun stopLoop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { frameThread?.shutdownNow() }
        frameThread = null
        AppLogger.i(TAG, "Cloak 画面流停止 session=$sessionId")
    }

    fun statusText(): String =
        if (lastError != null) "Cloak 画面异常: $lastError" else "Cloak 画面流 ${if (running.get()) "运行中" else "已停止"}"

    // ---------------------------------------------------------------- 抓帧

    private fun pullFrame() {
        if (!running.get() || inputBusy.get()) return
        val client = clientProvider?.invoke()
        if (client == null) {
            // 只记一次：客户端还没绑定时这条每 66ms 都会命中，刷屏没意义。
            if (!loggedNoClient) {
                loggedNoClient = true
                AppLogger.w(TAG, "抓帧跳过：clientProvider 返回 null，session=$sessionId")
            }
            return
        }
        loggedNoClient = false
        try {
            val png = client.screenshot()
            if (png.isEmpty()) return
            val hash = png.contentHashCode()
            if (hash == lastFrameHash) return
            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return
            lastFrameHash = hash
            lastError = null
            if (!loggedFirstFrame) {
                loggedFirstFrame = true
                AppLogger.i(TAG, "首帧抓取成功 ${bmp.width}x${bmp.height} bytes=${png.size}")
            }
            val old = frame
            frame = bmp
            // 旧帧必须回收，否则 15fps 下几秒就把堆打满（1280x800 单帧约 4MB）。
            old?.takeIf { it !== bmp && !it.isRecycled }?.recycle()
            uiHandler.post { redraw() }
        } catch (t: Throwable) {
            // 引擎重启期间必然报错，只记一次，不要刷屏。
            val msg = t.message ?: t.javaClass.simpleName
            if (msg != lastError) {
                lastError = msg
                AppLogger.w(TAG, "抓帧失败: $msg")
            }
        }
    }

    private fun redraw() {
        if (!attached) return
        if (!isAvailable) return
        val bmp = frame
        var canvas: Canvas? = null
        try {
            canvas = lockCanvas() ?: return
            if (bmp == null || bmp.isRecycled) {
                canvas.drawColor(Color.WHITE)
                return
            }
            computeDestRect(canvas.width, canvas.height)
            canvas.drawColor(Color.WHITE)
            srcRect.set(0, 0, bmp.width, bmp.height)
            canvas.drawBitmap(bmp, srcRect, destRect, paint)
            if (!loggedFirstDraw) {
                loggedFirstDraw = true
                AppLogger.i(TAG, "首帧绘制完成 canvas=${canvas.width}x${canvas.height} dest=$destRect")
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "绘制失败: ${t.message}")
        } finally {
            if (canvas != null) {
                runCatching { unlockCanvasAndPost(canvas) }
            }
        }
    }

    /** 当前帧对应的远端视口尺寸。帧还没到时用兜底值。 */
    private fun remoteSize(): Pair<Int, Int> {
        val bmp = frame
        if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
            return bmp.width to bmp.height
        }
        return FALLBACK_WIDTH to FALLBACK_HEIGHT
    }

    /** 等比缩放并居中，与 WebView 那套 viewport 适配保持一致。 */
    private fun computeDestRect(viewW: Int, viewH: Int) {
        if (viewW <= 0 || viewH <= 0) {
            destRect.setEmpty()
            return
        }
        // 视口尺寸取真实帧宽高：设备 resize、用户改窗口、不同 fingerprint 的
        // viewportWidth/Height 都会让远端尺寸变化，写死 1280x800 会导致坐标偏移。
        val (rw, rh) = remoteSize()
        val scale = min(viewW.toFloat() / rw, viewH.toFloat() / rh)
        val w = rw * scale
        val h = rh * scale
        val left = (viewW - w) / 2f
        val top = (viewH - h) / 2f
        destRect.set(left, top, left + w, top + h)
    }

    /** 把屏幕坐标换算成远端 CSS 像素。点在画布外返回 null，不误触。 */
    private fun toRemote(x: Float, y: Float): Pair<Double, Double>? {
        if (destRect.isEmpty) return null
        if (x < destRect.left || x > destRect.right || y < destRect.top || y > destRect.bottom) {
            return null
        }
        val (rw, rh) = remoteSize()
        val rx = (x - destRect.left) / destRect.width() * rw
        val ry = (y - destRect.top) / destRect.height() * rh
        return rx.toDouble() to ry.toDouble()
    }

    // ---------------------------------------------------------------- 触摸

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val client = clientProvider?.invoke() ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downAt = System.currentTimeMillis()
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > 24f || abs(event.y - downY) > 24f) moved = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                val target = toRemote(downX, downY)
                if (target == null) return true
                val elapsed = System.currentTimeMillis() - downAt
                val destH = destRect.height()
                val upY = event.y
                val didMove = moved
                inputBusy.set(true)
                ensureExecutor()
                // executor 可能正处于重建窗口，取到 null 时直接放弃本次派发，
                // 不能让它把 inputBusy 卡在 true（否则抓帧永久停摆）。
                val exec = frameThread
                if (exec == null || exec.isShutdown) {
                    inputBusy.set(false)
                    return true
                }
                runCatching {
                    exec.execute {
                        try {
                            if (didMove && destH > 0f) {
                                // 拖动换算成滚动像素：页面常自己接管手势，模拟拖拽不稳。
                                val (_, rh2) = remoteSize()
                                val deltaY = (downY - upY) / destH * rh2
                                if (abs(deltaY) > 6f) {
                                    client.scroll(deltaY.toDouble(), target.first, target.second)
                                }
                            } else if (elapsed < 800) {
                                client.click(target.first, target.second)
                            }
                            lastFrameHash = 0 // 强制下一帧重绘
                        } catch (t: Throwable) {
                            AppLogger.w(TAG, "输入派发失败: ${t.message}")
                        } finally {
                            inputBusy.set(false)
                        }
                    }
                }.onFailure {
                    AppLogger.w(TAG, "输入任务提交失败: ${it.message}")
                    inputBusy.set(false)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun release() {
        stopLoop()
        val bmp = frame
        frame = null
        bmp?.takeIf { !it.isRecycled }?.recycle()
    }
}
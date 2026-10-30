package com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak

import android.content.Context
import com.ai.assistance.operit.core.tools.system.Terminal
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.runBlocking
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * proot 内 Chromium 启动器。
 *
 * 关键设计（对齐真实运行环境）：
 *  1. 执行通道走 [Terminal.executeHiddenCommand]，它在 proot Ubuntu 内运行，
 *     而 `ProcessBuilder("sh","-c")` 跑在 Android 根，看不到 proot 的 /root。
 *  2. Chrome 路径自动探测，覆盖 Playwright / camoufox / 系统安装等多种布局。
 *  3. 抗检测必须 `headless=false` + Xvfb（headless 会关掉 WebGL，指纹立刻异常）。
 *     若环境无 Xvfb 才降级到 `--headless=new`，并在状态里标明降级原因。
 *  4. 所有路径都以 proot 视角书写（/root/...），不用 Android 路径。
 */
internal object CloakLauncher {
    private const val TAG = "CloakLauncher"

    private const val DISPLAY_NUM = 99
    private const val DISPLAY = ":$DISPLAY_NUM"
    private const val XVFB_RESOLUTION = "1280x800x24"
    private const val PROFILE_ROOT = "/root/.operit_cloak/profiles"
    private const val LOG_ROOT = "/root/.operit_cloak/logs"
    private const val BASE_PORT = 9300
    private const val XVFB_PID_FILE = "/root/.operit_cloak/xvfb.pid"

    /** Chrome 候选路径，按优先级排列。 */
    private val CHROME_CANDIDATES = listOf(
        // ① 真 CloakBrowser 内核（源码级指纹补丁 58 处，navigator.webdriver=false）。
        //    cloakbrowser 官方按版本号分目录下发，所以必须用通配。
        //    实测 ARM64 免费版：Chrome/146.0.7680.177，platform=Win32，cores=8，plugins=5。
        "/root/.cloakbrowser/chromium-*/chrome",
        "/root/.cloakbrowser/chrome",
        // ② 退路：Playwright 原生 Chromium。能跑，但只有 flag 级伪装，
        //    在 reCAPTCHA Enterprise / DataDome 面前仍会被认，属降级方案。
        "/root/.cache/ms-playwright/chromium-1248/chrome-linux-arm64/chrome",
        "/root/.cache/ms-playwright/chromium-*/chrome-linux*/chrome",
        "/root/.cache/ms-playwright/chromium_headless_shell-*/chrome-linux*/headless_shell",
        "/opt/google/chrome/chrome",
        "/usr/bin/chromium",
        "/usr/bin/chromium-browser",
        "/usr/bin/google-chrome"
    )

    @Volatile private var chromePath: String? = null
    @Volatile private var hasXvfb: Boolean? = null
    @Volatile private var headlessForced: Boolean = false
    @Volatile private var current: CloakStatus = CloakStatus()
    private var client: CloakCdpClient? = null
    private val launching = AtomicBoolean(false)

    fun setChromePath(path: String) {
        chromePath = path
        AppLogger.i(TAG, "手动指定 chrome: $path")
    }

    fun status(): CloakStatus = current
    fun cdp(): CloakCdpClient? = client

    // ------------------------------------------------------------------ 执行通道

    /**
     * 在 proot 内执行命令并取回 stdout。
     * 超时或失败返回 null，并记录日志。
     */
    private fun proot(context: Context, command: String, timeoutMs: Long = 60_000L): String? {
        return runCatching {
            runBlocking {
                val terminal = Terminal.getInstance(context)
                val r: HiddenExecResult = terminal.executeHiddenCommand(
                    command = command,
                    executorKey = "cloak_launcher",
                    timeoutMs = timeoutMs
                )
                if (r.isOk) r.output.ifBlank { r.rawOutputPreview }
                else {
                    AppLogger.w(
                        TAG,
                        "hidden exec 失败 state=${r.state} err=${r.error} cmd=${command.take(120)}"
                    )
                    r.output.ifBlank { r.rawOutputPreview }.ifBlank { null }
                }
            }
        }.getOrElse {
            AppLogger.e(TAG, "hidden exec 异常: ${it.message}", it)
            null
        }
    }

    // ------------------------------------------------------------------ 环境探测

    /** 探测可用的 chrome 可执行文件，结果缓存。 */
    private fun resolveChrome(context: Context): String? {
        chromePath?.let { if (it.isNotBlank()) return it }
        for (cand in CHROME_CANDIDATES) {
            val cmd = if (cand.contains('*')) {
                "ls -1 $cand 2>/dev/null | head -1"
            } else {
                "[ -x $cand ] && echo $cand"
            }
            val out = proot(context, cmd, 20_000L)?.trim()?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("/") && it.isNotBlank() }
            if (!out.isNullOrBlank()) {
                chromePath = out
                AppLogger.i(TAG, "探测到 chrome: $out")
                return out
            }
        }
        // 候选表里没有 → 按官方 wrapper 的做法自动拉取内核。
        // 这是「装到新设备也能拿到真反检测内核」的关键一步：
        // 真 CloakBrowser 有源码级指纹补丁（ARM64 免费版 58 处），
        // 而 Playwright Chromium 只有 flag 级伪装，重站必被认。
        val fetched = CloakBinaryInstaller.ensure(context) { c, cmd, tmo -> proot(c, cmd, tmo) }
        if (!fetched.isNullOrBlank()) {
            chromePath = fetched
            AppLogger.i(TAG, "已自动安装 CloakBrowser 内核: $fetched")
            return fetched
        }

        AppLogger.w(TAG, "未找到任何 chrome 可执行文件（自动安装也失败）")
        return null
    }

    /** 探测 Xvfb 是否可用，结果缓存。 */
    private fun resolveXvfb(context: Context): Boolean {
        hasXvfb?.let { return it }
        val out = proot(context, "command -v Xvfb 2>/dev/null || which Xvfb 2>/dev/null", 20_000L)
        val ok = !out.isNullOrBlank() && out.contains("Xvfb")
        hasXvfb = ok
        AppLogger.i(TAG, "Xvfb 可用: $ok")
        return ok
    }

    /** 确保 Xvfb 在 DISPLAY:99 上运行。返回是否成功。 */
    private fun ensureXvfb(context: Context): Boolean {
        if (resolveXvfb(context).not()) return false

        // 已有的 display 是否还活着？
        val alive = proot(
            context,
            "pgrep -f 'Xvfb $DISPLAY' >/dev/null 2>&1 && echo ALIVE || echo DEAD",
            15_000L
        )?.contains("ALIVE") == true
        if (alive) {
            AppLogger.i(TAG, "Xvfb $DISPLAY 已在运行")
            return true
        }

        proot(context, "mkdir -p ${LOG_ROOT} 2>/dev/null", 15_000L)
        proot(
            context,
            "nohup Xvfb $DISPLAY -screen 0 $XVFB_RESOLUTION -ac +extension GLX +render -noreset " +
                "> ${LOG_ROOT}/xvfb.log 2>&1 & echo \$! > $XVFB_PID_FILE; sleep 1; echo started",
            30_000L
        )

        // 等 socket 出现
        repeat(20) { i ->
            val ready = proot(
                context,
                "pgrep -f 'Xvfb $DISPLAY' >/dev/null 2>&1 && echo ALIVE || echo DEAD",
                10_000L
            )?.contains("ALIVE") == true
            if (ready) {
                AppLogger.i(TAG, "Xvfb $DISPLAY 启动成功（第 ${i + 1} 次探测）")
                return true
            }
            Thread.sleep(300)
        }

        val log = proot(context, "tail -20 ${LOG_ROOT}/xvfb.log 2>/dev/null", 10_000L)
        AppLogger.w(TAG, "Xvfb 启动失败。日志: $log")
        return false
    }

    // ------------------------------------------------------------------ 端口

    private fun portOpen(port: Int): Boolean = runCatching {
        Socket("127.0.0.1", port).use { true }
    }.getOrDefault(false)

    private fun waitPort(port: Int, timeoutMs: Int = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (portOpen(port)) return true
            Thread.sleep(300)
        }
        return false
    }

    /** 找一个空闲端口（从 BASE_PORT 起）。 */
    private fun pickPort(): Int {
        for (p in BASE_PORT until BASE_PORT + 20) {
            if (!portOpen(p)) return p
        }
        return BASE_PORT
    }

    // ------------------------------------------------------------------ 启动

    /**
     * 启动 Chromium 并建立 CDP 连接。
     * 已启动则直接复用。
     */
    fun launch(context: Context, fingerprint: CloakFingerprint): CloakCdpClient {
        // 已有连接直接复用
        client?.let { if (current.running) return it }

        if (!launching.compareAndSet(false, true)) {
            // 另一个线程正在启动，等它完成
            val deadline = System.currentTimeMillis() + 40_000
            while (System.currentTimeMillis() < deadline) {
                client?.let { if (current.running) return it }
                Thread.sleep(200)
            }
            throw IllegalStateException("CloakBrowser 正在启动中，等待超时")
        }

        try {
            val chrome = resolveChrome(context)
                ?: throw IllegalStateException(
                    "proot 内未找到 Chromium。已尝试路径:\n" +
                        CHROME_CANDIDATES.joinToString("\n") { "  $it" } +
                        "\n请在 proot 中安装，或调用 setChromePath 指定。"
                )

            // 抗检测优先：Xvfb + 有头模式
            val useXvfb = ensureXvfb(context)
            headlessForced = !useXvfb

            val port = pickPort()
            val profileDir = "$PROFILE_ROOT/${fingerprint.profileId}"
            val logFile = "$LOG_ROOT/cloak_$port.log"

            proot(context, "mkdir -p $profileDir $LOG_ROOT 2>/dev/null", 20_000L)
            // 残留进程清理（2026-10-30 补）。
            // 实测故障：上一次启动失败后 chrome 主进程仍占着同一 profileDir，
            // 新实例启动时 Chromium 把参数转交给已有实例，自己立刻退出，只往日志写一句
            //   "Opening in existing browser session."
            // 于是 waitPort(9301) 空等 35 秒，日志里却看不到任何错误原因。
            // 修法：同一 profile 只允许一个 chrome；启动前先把该 profile 的旧进程清掉。
            runCatching {
                proot(
                    context,
                    "pkill -f 'user-data-dir=$profileDir' 2>/dev/null; sleep 1; echo cleaned",
                    15_000L
                )
            }

            val args = buildList {
                add(chrome)
                add("--remote-debugging-port=$port")
                add("--remote-debugging-address=127.0.0.1")
                add("--user-data-dir=$profileDir")
                add("--no-sandbox")
                add("--disable-setuid-sandbox")
                add("--no-first-run")
                add("--no-default-browser-check")
                add("--disable-dev-shm-usage")
                add("--disable-background-networking")
                add("--disable-backgrounding-occluded-windows")
                add("--disable-renderer-backgrounding")
                add("--disable-ipc-flooding-protection")
                // 保留 WebGL：软件渲染 + 允许不安全 swiftshader
                add("--use-gl=swiftshader")
                add("--enable-unsafe-swiftshader")
                add("--enable-webgl")
                // ===== 反检测关键 flag（2026-10-29 补）=====
                // 原实现完全缺这几条，是「Cloak 能起但一上重站就被认」的直接原因：
                // ① AutomationControlled：Chromium 被 CDP 接管时会把 navigator.webdriver 置 true。
                //    这是最基础的机器人特征，不关掉等于自报身份，后面伪造再多指纹也没用。
                add("--disable-blink-features=AutomationControlled")
                // ② 站点隔离会让部分 iframe 跑独立渲染进程，跨域读特征值时口径不一致，
                //    常被当作反检测探针。关掉后所有 frame 的指纹口径统一。
                add("--disable-features=IsolateOrigins,site-per-process,Translate,BackForwardCache")
                // ③ 去掉自动化提示条与通知，避免截图尺寸异常/弹窗干扰
                add("--disable-infobars")
                add("--disable-notifications")
                add("--hide-crash-restore-bubble")
                add("--ignore-gpu-blocklist")
                // ④ CDP WebSocket 来源放行。
                //    实测：Chrome 111+ 默认拒绝外部 Origin 的 CDP 握手，报
                //      "Handshake status 403 Forbidden ... Use --remote-allow-origins"
                //    真机通过 127.0.0.1 连也要带这条，否则 socket 永远连不上。
                add("--remote-allow-origins=*")
                if (headlessForced) {
                    add("--headless=new")
                } else {
                    add("--start-maximized")
                }
                addAll(fingerprint.toCliArgs())
                add("about:blank")
            }

            val cmd = buildString {
                if (!headlessForced) append("DISPLAY=$DISPLAY ")
                // 进程存活修复（2026-10-29）
                // 原实现只有 nohup + &，实测表现是：日志里已经打出
                //   "DevTools listening on ws://127.0.0.1:9300/..."
                // 但 waitPort() 一直超时、报「CDP 端口 9300 未就绪」。
                // 原因是 executeHiddenCommand 结束时会清理整个进程组，chrome 被连带杀掉，
                // 端口随之消失 —— 所以日志有、进程没。
                // 修法：setsid 让 chrome 脱离当前会话与进程组，nohup 挡 SIGHUP，
                //       再把 stdin 接到 /dev/null，三层保证它彻底独立于 hidden exec 的生命周期。
                append("setsid nohup ")
                append(args.joinToString(" ") { quote(it) })
                append(" > ").append(logFile).append(" 2>&1 < /dev/null & echo \$!")
            }

            AppLogger.i(
                TAG,
                "启动 chromium: chrome=$chrome xvfb=$useXvfb headless=$headlessForced port=$port"
            )

            val pid = proot(context, cmd, 30_000L)
                ?.trim()?.lineSequence()?.lastOrNull { it.trim().toIntOrNull() != null }
                ?.trim()?.toIntOrNull() ?: -1

            if (!waitPort(port, 35_000)) {
                val log = proot(context, "tail -40 $logFile 2>/dev/null", 15_000L)
                current = CloakStatus(
                    running = false, port = port, pid = pid,
                    profileId = fingerprint.profileId,
                    lastError = buildString {
                        append("CDP 端口 $port 未就绪。")
                        if (headlessForced) append("（已降级 headless，因 Xvfb 不可用）")
                        append("\n日志:\n")
                        append(log ?: "(无日志)")
                    }
                )
                throw IllegalStateException(current.lastError)
            }

            val cdp = CloakCdpClient(port = port)
            val wsUrl = cdp.connectToFirstPage()
            AppLogger.i(TAG, "CDP 已连接: $wsUrl")

            // CDP 层指纹覆盖
            runCatching {
                cdp.setUserAgent(fingerprint.userAgent, fingerprint.platform)
                cdp.setTimezone(fingerprint.timezone)
                // 屏幕几何：不给 screenWidth/Height 时 screen 会回落到视口尺寸（如 1279x799），
                // 与真实桌面 1920x1080 不一致，是明显自动化信号，必须显式指定。
                // 注：positionX/Y 改不动 window.screenX/Y（实测已验证），传 0 仅表意图。
                cdp.setDeviceMetrics(
                    fingerprint.viewportWidth, fingerprint.viewportHeight,
                    fingerprint.deviceScale, fingerprint.mobile,
                    screenWidth = fingerprint.screenWidth ?: 1920,
                    screenHeight = fingerprint.screenHeight ?: 1080,
                    positionX = 0,
                    positionY = 0
                )
                // navigator.onLine：proot 里拿不到 Android 的网络状态，
                // 不修的话页面明明能联网却报告 offline，是矛盾信号。
                cdp.setOnline(true)
                if (fingerprint.latitude != null && fingerprint.longitude != null) {
                    cdp.setGeolocation(fingerprint.latitude, fingerprint.longitude)
                }
            }.onFailure { AppLogger.w(TAG, "CDP 指纹覆盖部分失败: ${it.message}") }

            client = cdp
            current = CloakStatus(
                running = true, port = port, pid = pid,
                profileId = fingerprint.profileId,
                version = detectVersion(context, chrome),
                lastError = if (headlessForced) "Xvfb 不可用，已降级 headless（WebGL 指纹可能异常）" else null
            )
            AppLogger.i(TAG, "CloakBrowser 就绪: port=$port pid=$pid headless=$headlessForced")
            return cdp
        } finally {
            launching.set(false)
        }
    }

    private fun detectVersion(context: Context, chrome: String): String =
        proot(context, "$chrome --version 2>/dev/null", 20_000L)?.trim()?.take(80) ?: "unknown"

    // ------------------------------------------------------------------ 停止

    fun shutdown() {
        runCatching { client?.close() }
        client = null
        current.pid.takeIf { it > 0 }?.let { pid ->
            runCatching {
                // 没有 context 时尽力而为，真正的关闭在 shutdown(context)
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "kill $pid 2>/dev/null"))
            }
        }
        current = CloakStatus()
    }

    /** 带 context 的完整关闭：杀浏览器 + 可选杀 Xvfb。 */
    fun shutdown(context: Context, stopXvfb: Boolean = false) {
        runCatching { client?.close() }
        client = null
        current.pid.takeIf { it > 0 }?.let { pid ->
            proot(context, "kill $pid 2>/dev/null; sleep 0.5; kill -9 $pid 2>/dev/null; echo done", 20_000L)
        }
        if (stopXvfb) {
            proot(context, "kill \$(cat $XVFB_PID_FILE 2>/dev/null) 2>/dev/null; rm -f $XVFB_PID_FILE; echo done", 15_000L)
            hasXvfb = null
        }
        current = CloakStatus()
    }

    // ------------------------------------------------------------------ 工具

    private fun quote(s: String): String =
        if (s.any { it.isWhitespace() || it == '\'' || it == '"' || it == '$' || it == '`' }) {
            "'" + s.replace("'", "'\\''") + "'"
        } else {
            s
        }

    /** 环境自检摘要，供 browser_switch_engine 的 status 输出。 */
    fun environmentSummary(context: Context): String {
        val chrome = resolveChrome(context)
        val xvfb = resolveXvfb(context)
        val running = current.running
        return buildString {
            append("chrome=").append(chrome ?: "(未找到)")
            append(", xvfb=").append(if (xvfb) "可用" else "缺失")
            append(", headless=").append(if (headlessForced) "降级中" else "关闭(有头)")
            append(", running=").append(running)
            if (current.port > 0) append(", port=").append(current.port)
            if (!current.lastError.isNullOrBlank()) append(", note=").append(current.lastError)
        }
    }
}

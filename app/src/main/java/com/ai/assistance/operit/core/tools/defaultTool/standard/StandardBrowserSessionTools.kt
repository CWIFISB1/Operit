package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.application.ActivityLifecycleManager
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.*
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.UserscriptInstallSourceType
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.runtime.WebSessionUserscriptManager
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.storage.UserscriptRepository
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class StandardBrowserSessionTools(internal val context: Context) : ToolExecutor {

    companion object {
        private const val TAG = "BrowserSessionTools"
        internal const val DEFAULT_TIMEOUT_MS = 10_000L
        internal const val MAX_EVENT_LOG_ENTRIES = 500
        internal const val DESKTOP_CHROME_MAJOR_VERSION = "124"
        internal const val DESKTOP_CHROME_FULL_VERSION = "124.0.0.0"
        internal const val DEFAULT_DESKTOP_VIEWPORT_WIDTH = 1280
        internal const val DEFAULT_DESKTOP_VIEWPORT_HEIGHT = 720
        internal const val MAX_VIEWPORT_DIMENSION_CSS_PX = 4096
        internal const val MAX_VIEWPORT_PIXEL_COUNT = 8_388_608L
        internal const val MAX_VIEWPORT_LAYOUT_PIXEL_COUNT = 33_554_432L
        internal const val MAX_SCREENSHOT_DIMENSION_PX = 32_768
        internal const val MAX_SCREENSHOT_PIXEL_COUNT = 16_777_216L
        internal const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$DESKTOP_CHROME_FULL_VERSION Safari/537.36"
        internal const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        internal val mainHandler = Handler(Looper.getMainLooper())

        internal val sessions = ConcurrentHashMap<String, WebSession>()
        internal val sessionOrder = mutableListOf<String>()
        internal val pendingAsyncJsCalls = ConcurrentHashMap<String, PendingAsyncJsCall>()
        private val snapshotGenerationSeed = AtomicLong(0L)

        internal val sessionOrderLock = Any()
        internal val overlayLock = Any()
        internal val sessionConfigLock = Any()

        @Volatile internal var browserHost: WebSessionBrowserHost? = null
        @Volatile internal var activeSessionId: String? = null
        @Volatile internal var desktopModeEnabled: Boolean = true
        @Volatile internal var desktopModeInitialized: Boolean = false
        @Volatile internal var pendingExternalOpenRequest: PendingExternalOpenRequest? = null
    }

    internal val historyStore by lazy { WebSessionHistoryStore.getInstance(context.applicationContext) }
    private val userscriptRepository by lazy { UserscriptRepository.getInstance(context.applicationContext) }
    internal val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val userscriptManager by lazy {
        WebSessionUserscriptManager(
            context = context.applicationContext,
            onOpenUserscriptUi = {
                mainHandler.post {
                    openUserscriptSheetOnMain()
                }
            },
            onOpenTab = { url, active ->
                runOnMainSync<String?> {
                    openUserscriptTabOnMain(context.applicationContext, url, active)
                }
            },
            onActivateSession = { sessionId ->
                runOnMainSync<Unit> {
                    activateSessionOnMain(sessionId)
                }
            },
            onCloseSession = { sessionId ->
                closeSession(sessionId)
            },
            onDownload = { sessionId, url, fileName ->
                runOnMainSync<Unit> {
                    handleUserscriptDownloadOnMain(sessionId, url, fileName)
                }
            },
            onMenuCommandsChanged = { sessionId ->
                mainHandler.post {
                    refreshSessionUiOnMain(sessionId)
                }
            },
            onToast = { message -> showToast(message) }
        )
    }

    init {
        ensureDesktopModeInitialized()
        initializeBrowserDownloadSupport()
    }

    internal data class WebSession(
        val id: String,
        val webView: WebView,
        val sessionName: String?,
        val customUserAgent: String? = null,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        @Volatile var currentUrl: String = "about:blank"
        @Volatile var pageTitle: String = ""
        @Volatile var pageLoaded: Boolean = false
        @Volatile var isLoading: Boolean = false
        @Volatile var canGoBack: Boolean = false
        @Volatile var canGoForward: Boolean = false
        @Volatile var hasSslError: Boolean = false
        @Volatile var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
        @Volatile var lastFileChooserRequestAt: Long = 0L
        @Volatile var lastDownloadEvent: WebDownloadEvent? = null
        @Volatile var lastDownloadEventAt: Long = 0L
        @Volatile var pendingDialog: PendingDialog? = null
        @Volatile var viewportWidthPx: Int? = null
        @Volatile var viewportHeightPx: Int? = null
        @Volatile var hasExplicitViewportSize: Boolean = false
        @Volatile var lastSnapshot: BrowserSnapshot? = null

        // ---- 双引擎支持：默认 WebView，可切换到 CloakBrowser ----
        @Volatile
        // 单内核化（2026-10-29）：默认引擎改为 CLOAK。
        // 原默认是 WEBVIEW，导致每个新 tab 都要先 switch_engine 才走反检测内核，
        // 忘了切就用内置 WebView 的裸指纹去撞风控，等于白做。
        // 现在 Cloak 是唯一主内核，WebView 只在 Cloak 启动失败时兜底（见 resolveEngine）。
        var engine: com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.BrowserEngine =
            com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.BrowserEngine.CLOAK

        /** 该会话的指纹配置（仅 CLOAK 引擎使用）。 */
        @Volatile
        var cloakFingerprint: com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.CloakFingerprint? = null

        /** CloakBrowser 的 CDP 客户端（仅 CLOAK 引擎使用）。 */
        @Volatile
        var cloakClient: com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.CloakCdpClient? = null

        /**
         * 是否为「外部反检测内核」（CloakBrowser 或 Camoufox）。
         *
         * 语义是「不是系统 WebView」，不是「就是 Cloak」。
         * 加了 CAMOUFOX 之后若仍写成 == CLOAK，
         * camoufox 会话会被所有调用点当 WebView 处理，走错分支。
         */
        val isCloak: Boolean
            get() = engine != com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.BrowserEngine.WEBVIEW

        /** 是否为 Camoufox 内核，用于需要区分两者的分支。 */
        val isCamoufox: Boolean
            get() = engine == com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.BrowserEngine.CAMOUFOX

        val stateSignal: Object = Object()
        @Volatile var stateVersion: Long = 0L
        val consoleEntries: MutableList<BrowserConsoleEntry> = mutableListOf()
        val networkEntries: MutableList<BrowserNetworkRequestEntry> = mutableListOf()
    }

    internal data class BrowserActionSettlementPolicy(
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val waitForDocumentReady: Boolean = false,
        val waitForNavigationChange: Boolean = false,
        val waitForText: String? = null,
        val waitForTextGone: String? = null,
        val waitForTimeSeconds: Double? = null,
        val allowActivePageSwitch: Boolean = true,
        val captureSnapshot: Boolean = true
    )

    internal data class BrowserActionMarkers(
        val initialSessionId: String,
        val initialUrl: String,
        val consoleTimestamp: Long,
        val downloadTimestamp: Long,
        val snapshotGeneration: Long,
        val startedAt: Long
    )

    internal data class BrowserActionSettlement(
        val registry: BrowserPageRegistry,
        val session: WebSession,
        val snapshot: BrowserSnapshot?,
        val consoleMarker: Long,
        val downloadMarker: Long,
        val timedOut: Boolean = false
    )

    override fun invoke(tool: AITool): ToolResult {
        return try {
            when (tool.name) {
                "browser_click" -> browserClick(tool)
                "browser_close" -> browserClose(tool)
                "browser_close_all" -> browserCloseAll(tool)
                "browser_console_messages" -> browserConsoleMessages(tool)
                "browser_drag" -> browserDrag(tool)
                "browser_evaluate" -> browserEvaluate(tool)
                "browser_file_upload" -> browserFileUpload(tool)
                "browser_fill_form" -> browserFillForm(tool)
                "browser_handle_dialog" -> browserHandleDialog(tool)
                "browser_hover" -> browserHover(tool)
                "browser_switch_engine" -> browserSwitchEngine(tool)
                "browser_cloak_diag" -> browserCloakDiag(tool)
                "browser_navigate" -> browserNavigate(tool)
                "browser_navigate_back" -> browserNavigateBack(tool)
                "browser_network_requests" -> browserNetworkRequests(tool)
                "browser_press_key" -> browserPressKey(tool)
                "browser_resize" -> browserResize(tool)
                "browser_run_code" -> browserRunCode(tool)
                "browser_select_option" -> browserSelectOption(tool)
                "browser_wait_for" -> browserWaitFor(tool)
                "browser_snapshot" -> browserSnapshot(tool)
                "browser_tabs" -> browserTabs(tool)
                "browser_take_screenshot" -> browserTakeScreenshot(tool)
                "browser_type" -> browserType(tool)
                else -> error(tool.name, "Unsupported browser session tool: ${tool.name}")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Tool execution failed: ${tool.name}", e)
            error(tool.name, e.message ?: "Unknown error")
        }
    }

    /**
     * 切换当前浏览器会话的渲染引擎，并返回切换结果。
     *
     * action=status   仅查询当前引擎与 Cloak 运行状态
     * action=switch   切换引擎（默认 webview），可选传 fingerprint 种子
     * action=close    关闭 Cloak 进程并回退到 webview
     */
    /**
     * 把会话降级到 WebView。
     *
     * 场景：Cloak 是默认引擎，但它的前提是 proot 里有 chromium 二进制（或能自动拉到）。
     * 老设备、清了数据、网络不通、Xvfb 起不来时，Cloak 必然拉不起来。
     * 这种情况下如果死守 Cloak，用户会看到「浏览器打不开」——比指纹弱严重得多。
     *
     * 因此约定：**Cloak 任何一步失败，就地切回 WebView 继续本次操作**，
     * 并把原因写进返回体，让上层知道现在是降级态。
     */
    /**
     * 显式降级到 WebView。
     *
     * 注意：自 2026-10-30 起，**自动化路径不再自动调用本方法**。
     * 保留它只给两处用：用户手动 browser_switch_engine engine=webview，
     * 以及 close/restart 时清状态。
     * 内核失败一律走 [engineUnavailable]，不再静默降级。
     */
    private fun degradeToWebView(session: WebSession, reason: String): String {
        val prev = session.engine
        session.engine =
            com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.BrowserEngine.WEBVIEW
        session.cloakClient = null
        // 降级时一并释放 camoufox 的 BiDi 连接，避免留下悬挂会话
        runCatching {
            com.ai.assistance.operit.core.tools.defaultTool.websession.browser
                .camoufox.CamoufoxBrowserTools.detach(session.id)
        }
        com.ai.assistance.operit.util.AppLogger.w(
            "BrowserTools",
            "会话 ${session.id} 引擎降级 $prev -> WEBVIEW，原因: $reason"
        )
        return reason
    }

    /**
     * 尝试在 Cloak 上执行一个动作；失败则降级 WebView 并返回 null。
     * 成功返回结果字符串。
     */
    private inline fun tryCloakOrDegrade(
        session: WebSession,
        action: String,
        block: () -> String
    ): String? {
        return try {
            block()
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            degradeToWebView(session, "cloak $action 失败: $msg")
            null
        }
    }

    /** 本进程内已经确认不可用的会话，后续动作直接走 WebView，不再每次重试拉进程。 */
    private val cloakDeadSessions: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 判断该会话的「防检测内核」是否真的可用。
     *
     * 覆盖两个内核（CloakBrowser / Camoufox），语义是「不是 WebView 且真的能连上」。
     *
     * 只看 session.isCloak 当初不够：引擎标记是 CLOAK，但当 proot 里没有二进制、
     * 自动拉取失败、Xvfb 起不来时，实际请求必然抛异常，用户看到的会是「浏览器打不开」。
     * 因此这里做一次真实探测：能拿到客户端才算 ready。
     *
     * 探测失败**不再静默降级**（主人 2026-10-30 决定）：
     * 把会话标记为内核不可用，由调用点返回明确错误，避免用 WebView 的裸指纹
     * 伪装成「正常」去打目标站点——那比直接报错更危险。
     */
    private fun cloakReady(session: WebSession): Boolean {
        if (!session.isCloak) return false
        if (cloakDeadSessions.contains(session.id)) return false

        val client = runCatching {
            val bridge =
                com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.BrowserEngineBridge
            bridge.ensureEngine(
                context = context.applicationContext,
                sessionId = session.id,
                engine = session.engine
            )
            // 按引擎分别取客户端：两者是不同实现，不能混用
            if (session.isCamoufox) {
                com.ai.assistance.operit.core.tools.defaultTool.websession.browser
                    .camoufox.CamoufoxBrowserTools.hasClient(session.id)
            } else {
                com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.CloakBrowserTools
                    .clientOf(session.id) != null
            }
        }.getOrElse { false }

        if (!client) {
            cloakDeadSessions.add(session.id)
            com.ai.assistance.operit.util.AppLogger.e(
                "BrowserTools",
                "会话 ${session.id} 内核(${session.engine.name})不可用，后续动作将返回明确错误而不是降级 WebView"
            )
            return false
        }

        // [WHITE-SCREEN-FIX] 2026-11-01 真机复测：
        // ensureEngine 只把 CDP 客户端存进了 CloakBrowserTools.sessions 这张表，
        // WebSession.cloakClient 全仓从未被赋过非 null 值。
        // 而 buildBrowserState 用 `session.cloakClient != null` 来判定 isCloakEngine，
        // 于是这个标记恒为 false，UI 永远走 WebView 分支 —— 但该会话根本没建过 WebView，
        // 结果就是顶部显示 Cloak、正文整片白。
        // 修法：探测成功后把客户端回写到会话字段，UI 才能切到 CloakTextureView。
        if (!session.isCamoufox) {
            session.cloakClient =
                com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.CloakBrowserTools
                    .clientOf(session.id)
        }
        return true
    }

    /**
     * [CLOAK-DIAG] 2026-11-02 新增工具入口。
     *
     * 主人明确要求：不要再让 AI 靠翻源码 + 拼命令绕半天才知道
     * 「指纹能不能换 / Cookie 有没有丢 / 现在什么身份」。
     * 一次调用直接给结论。
     */
    private fun browserCloakDiag(tool: AITool): ToolResult {
        val action = (param(tool, "action")?.trim()?.lowercase()).orEmpty().ifBlank { "status" }
        val slotName = param(tool, "slot")?.trim().orEmpty()
        val proxyArg = param(tool, "proxy")?.trim().orEmpty()
        val session = getSession(null)
        val sid = session?.id
        val diag =
            com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.CloakDiagnostics

        return when (action) {
            "status" -> ok(tool.name, diag.fullReport(context.applicationContext, sid))
            "slots" -> ok(tool.name, diag.slots(context.applicationContext))
            "probe" -> ok(tool.name, diag.liveProbe(sid))
            "new" -> ok(
                tool.name,
                diag.newSlot(context.applicationContext, slotName.ifBlank { null }, proxyArg)
            )
            "proxytest" -> {
                if (proxyArg.isBlank()) error(tool.name, "action=proxytest 需要 proxy 参数")
                else ok(tool.name, diag.proxyTest(proxyArg))
            }
            "use" -> {
                if (slotName.isBlank()) error(tool.name, "action=use 需要 slot 参数")
                else ok(tool.name, diag.useSlot(context.applicationContext, sid, slotName))
            }
            "delete" -> {
                if (slotName.isBlank()) error(tool.name, "action=delete 需要 slot 参数")
                else ok(tool.name, diag.deleteSlot(context.applicationContext, slotName))
            }
            else -> error(
                tool.name,
                "unsupported action '$action'; use status / slots / probe / new / use / delete"
            )
        }
    }

    /**
     * 内核不可用时的统一错误返回。
     *
     * 刻意不再调用 degradeToWebView —— 用了 WebView 的裸指纹去访问目标站点，
     * 等于主动暴露真实环境，比失败更糟。
     */
    private fun engineUnavailable(toolName: String, session: WebSession?): ToolResult {
        val engine = session?.engine?.name?.lowercase() ?: "none"
        return error(
            toolName,
            "anti-detect engine unavailable (engine=$engine). " +
                "Install the kernel binary or run browser_switch_engine action=status to see details. " +
                "Refusing to fall back to WebView: its fingerprint would expose the real device."
        )
    }

    private fun okCloak(tool: AITool, session: WebSession, code: String, result: String): ToolResult =
        ok(
            tool.name,
            buildBrowserResponse(
                code = code,
                openTabs = renderOpenTabs(),
                pageState = renderPageState(session),
                result = result
            )
        )

    private fun browserSwitchEngine(tool: AITool): ToolResult {
        // 双内核（2026-10-30 起）。
        //
        // 历史：2026-10-29 曾做「单内核化」，只留 Cloak，去掉切换能力。
        // 现在接入 Camoufox（Firefox 158 源码级反检测）后必须恢复切换：
        // 两个外部内核各有所长，风控场景不同，需要按目标站点选择。
        //
        // 定位（主人 2026-10-30 明确）：
        //   **官方 WebView 不是主内核，只做最后兜底。**
        //   主管线永远是「防检测内核」——Cloak（Chromium 146，58 处补丁）
        //   或 Camoufox（Firefox 158，46 处 C++ 补丁）。
        //   WebView 仅在两者都启动失败时接管，避免整条 browser_navigate 直接失败。
        //
        // 支持的 action：
        //   status        查状态（默认），同时回报两个内核的可用性
        //   restart/close 释放当前内核，下次导航重新拉起
        //   switch        切到 engine 指定的内核（cloak / camoufox / webview）
        val session = getSession(null)
        val action = (param(tool, "action")?.trim()?.lowercase()).orEmpty().ifBlank { "status" }
        val engineParam = (param(tool, "engine")?.trim()?.lowercase()).orEmpty()

        val bridge = com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.BrowserEngineBridge
        val engineEnum = com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.BrowserEngine

        return when (action) {
            "status" -> {
                val cloakDetail = bridge.statusWithEnv(context.applicationContext)
                val camoufoxDetail =
                    com.ai.assistance.operit.core.tools.defaultTool.websession.browser
                        .camoufox.CamoufoxBrowserTools.statusWithEnv(context.applicationContext)
                val current = session?.engine?.name?.lowercase() ?: "none"
                val body = buildString {
                    append("browser_engine:").append(current).append('\n')
                    append("primary:anti-detect\n")
                    append("available:cloak,camoufox,webview\n")
                    append("webview_role:fallback-only\n")
                    append("--- cloak ---\n").append(cloakDetail).append('\n')
                    append("--- camoufox ---\n").append(camoufoxDetail)
                }
                if (session != null) {
                    okCloak(tool, session, "engine:status", body)
                } else {
                    ok(tool.name, body)
                }
            }

            "restart", "close" -> {
                val sid = session?.id ?: ""
                bridge.releaseEngine(sid)
                if (session != null) {
                    session.cloakClient = null
                }
                val detail = bridge.statusWithEnv(context.applicationContext)
                ok(
                    tool.name,
                    "engine released (cloak + camoufox). Will relaunch on next navigation.\n" + detail
                )
            }

            "switch" -> {
                if (engineParam.isBlank()) {
                    return error(
                        tool.name,
                        "action=switch 需要 engine 参数：cloak / camoufox / webview"
                    )
                }
                val target = engineEnum.fromName(engineParam)
                if (session == null) {
                    return error(tool.name, "没有活动会话，先调用 browser_navigate 建一个")
                }
                val sid = session.id

                // [IDENTITY-SLOT] 2026-11-01 最终版：
                //
                // 参数语义（fingerprint 字段沿用旧名，避免破坏已有调用）：
                //   · 不传          → 沿用会话当前绑定槽位；若没有则用 default
                //   · slot=new      → 新建一个随机身份槽位（新 Cookie 目录 + 新指纹）
                //   · slot=reg001   → 切到指定槽位（Cookie 与指纹一起切）
                //   · 传其它任意值  → 当作「槽位名」，不存在则创建
                //
                // 为什么不再「不传就随机换」：
                //   profileId 决定了 Cookie 的落盘目录。若每次切换都换 profileId，
                //   等于每次清空登录态 —— 今天注册的号明天打开就要重登，
                //   而且服务端看到「同账号天天换设备」反而更可疑。
                //   指纹必须**跟着账号走**，不跟着窗口走。
                val slotStore = com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak
                    .CloakSlotStore
                val slotParam = param(tool, "fingerprint")?.trim().orEmpty()
                // [GEO-SYNC] 代理参数：新建槽位时传入，绑到该身份上长期不变。
                // 主人说"代理随时换" —— 那就是每次换代理都开新槽位，
                // 旧槽位的 IP 记录保持原样，风控看到的是「不同账号」，不是「同一账号瞬移」。
                val proxyParam = param(tool, "proxy")?.trim().orEmpty()
                val slotName: String
                val slotSeed: String
                val slotProfile: String
                if (slotParam.isNotBlank()) {
                    val spec = if (slotParam.startsWith("slot=")) slotParam.removePrefix("slot=") else slotParam
                    if (spec.equals("new", ignoreCase = true) || spec.isBlank()) {
                        val s = slotStore.createSlot(context.applicationContext, proxy = proxyParam)
                        slotName = s.name; slotSeed = s.seed; slotProfile = s.profileId
                    } else {
                        val existing = slotStore.find(context.applicationContext, spec)
                            ?: slotStore.createSlot(context.applicationContext, spec, proxyParam)
                        slotName = existing.name; slotSeed = existing.seed; slotProfile = existing.profileId
                    }
                } else {
                    val current = com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak
                        .CloakBrowserTools.slotOf(sid)
                    val s = if (current.isNullOrBlank()) {
                        slotStore.defaultSlot(context.applicationContext)
                    } else {
                        slotStore.find(context.applicationContext, current)
                            ?: slotStore.defaultSlot(context.applicationContext)
                    }
                    slotName = s.name; slotSeed = s.seed; slotProfile = s.profileId
                }

                // 切换前先释放旧内核资源，避免两个引擎同时占端口
                runCatching { session.cloakClient?.close() }
                session.cloakClient = null
                session.engine = target

                // 绑定槽位 + 写入该槽位的指纹，switchEngine 内部 launch 会读到它
                runCatching {
                    val cloakTools =
                        com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.CloakBrowserTools
                    cloakTools.bindSlot(sid, slotName)
                    // [GEO-SYNC] 用槽位绑定的代理反查出口，覆盖时区/语言
                    val slotObj = slotStore.find(context.applicationContext, slotName)
                    val geo = slotObj?.proxy?.takeIf { it.isNotBlank() }?.let {
                        com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak
                            .CloakGeoResolver.resolve(it)
                    }
                    cloakTools.setFingerprint(
                        sid,
                        com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak
                            .CloakFingerprintFactory.build(
                                seed = slotSeed,
                                profileId = slotProfile,
                                proxyServer = slotObj?.proxy?.takeIf { it.isNotBlank() },
                                geo = geo
                            )
                    )
                }

                val resultText = bridge.switchEngine(context.applicationContext, sid, target)
                val slotInfo = "\n--- slot ---\nname=$slotName\nprofile=$slotProfile\n" +
                    "cookieDir=/root/.operit_cloak/profiles/$slotProfile\n" +
                    slotStore.describe(context.applicationContext)
                okCloak(tool, session, "engine:${target.name.lowercase()}", resultText + slotInfo)
            }

            else -> error(
                tool.name,
                "unsupported action '$action'; use status / restart / switch"
            )
        }
    }

    private fun browserNavigate(tool: AITool): ToolResult {
        val targetUrl = param(tool, "url")?.trim()
        if (targetUrl.isNullOrBlank()) {
            return error(tool.name, "url is required")
        }
        ensureOverlayPermission(tool.name)?.let { return it }
        val headers = parseHeaders(param(tool, "headers"))
        val session =
            runOnMainSync {
                getSession(null) ?: createSessionTabOnMain(context.applicationContext, "about:blank")
            }
        if (cloakReady(session)) {
            // Cloak 是默认引擎，但依赖 proot 里的 chromium 二进制。
            // 拉不起来时（老设备/清过数据/网络不通/Xvfb 缺席）必须降级回 WebView 继续导航，
            // 否则用户看到的是「浏览器打不开」——比指纹弱严重得多。
            val cloakOutcome = tryCloakOrDegrade(session, "navigate") {
                com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.BrowserEngineBridge.navigate(
                    context = context.applicationContext,
                    sessionId = session.id,
                    engine = session.engine,
                    url = targetUrl
                )
            }
            if (cloakOutcome != null) {
                session.currentUrl = targetUrl
                session.pageLoaded = true
                // [UI-SYNC-FIX] notifySessionStateChanged 只唤醒等到者，不刷 Compose。
                // 真正刷新 UI 的是 refreshSessionUiOnMain，之前这条路径漏了它，
                // 表现就是「画面已经变了，地址栏和标题还停在 about:blank」。
                syncCloakTitleAndUrl(session)
                notifySessionStateChanged(session)
                refreshSessionUiOnMain(session.id)
                return ok(
                    tool.name,
                    buildBrowserResponse(
                        code = "cloak:goto(" + quoteJsCode(targetUrl) + ")",
                        openTabs = renderOpenTabs(),
                        pageState = renderPageState(session),
                        result = cloakOutcome
                    )
                )
            }
            // 走到这里说明已降级到 WEBVIEW，下面的 WebView 路径继续执行。
        }
        val markers = captureActionMarkers(session)

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
            navigateSessionOnMain(session, targetUrl, headers)
        }
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy =
                    BrowserActionSettlementPolicy(
                        timeoutMs = DEFAULT_TIMEOUT_MS.coerceAtLeast(6_000L),
                        waitForDocumentReady = true,
                        waitForNavigationChange = true,
                        captureSnapshot = false
                    )
            )

        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = "await page.goto(${quoteJsCode(targetUrl)});",
                result = "Navigated to ${settlement.session.currentUrl.ifBlank { targetUrl }}"
            )
        )
    }

    /**
     * 从 Cloak 内核读回真实 title/url。
     *
     * Cloak 是外置 chromium，跳转不会触发 WebView 的 onReceivedTitle，
     * session.pageTitle 一直是空，UI 标签标题就不更新。
     */
    private fun syncCloakTitleAndUrl(session: WebSession) {
        val js = "(function(){try{return JSON.stringify({u:location.href,t:document.title});}catch(e){return '';}})()"
        val raw = runCatching {
            com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.CloakBrowserTools
                .eval(session.id, js)
        }.getOrNull() ?: return
        var body = raw.trim()
        if (body.length >= 2 && body.startsWith("\"")) {
            body = body.substring(1, body.length - 1)
        }
        body = body.replace("\\\"", "\"")
        if (!body.startsWith("{")) return
        val o = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return
        val u = o.optString("u")
        val t = o.optString("t")
        if (u.isNotBlank() && !u.startsWith("about:")) session.currentUrl = u
        if (t.isNotBlank()) session.pageTitle = t
    }

    private fun browserClick(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        if (cloakReady(session)) {
            val sel = param(tool, "selector")?.trim() ?: param(tool, "ref")?.trim()
                ?: return error(tool.name, "cloak 引擎需要 selector 参数")
            val out = com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.BrowserEngineBridge.clickSelector(
                sessionId = session.id,
                engine = session.engine,
                selector = sel
            )
            return okCloak(tool, session, "cloak:click", out)
        }
        val ref = param(tool, "ref")?.trim()?.takeIf { it.isNotBlank() }
        val selector = param(tool, "selector")?.trim()?.takeIf { it.isNotBlank() }
        if (ref == null && selector == null) {
            return error(tool.name, "ref or selector is required")
        }

        val buttonRaw = param(tool, "button")?.trim()
        val button =
            when {
                buttonRaw.isNullOrBlank() -> "left"
                buttonRaw == "left" || buttonRaw == "right" || buttonRaw == "middle" -> buttonRaw
                else -> return error(tool.name, "button must be one of: left, right, middle")
            }

        val doubleClick = boolParam(tool, "doubleClick", false)

        val (modifiers, invalidModifiers) = parseClickModifiers(param(tool, "modifiers"))
        if (invalidModifiers.isNotEmpty()) {
            return error(
                tool.name,
                "Invalid modifiers: ${invalidModifiers.joinToString(", ")}. Allowed: Alt, Control, ControlOrMeta, Meta, Shift"
            )
        }

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        if (ref != null && requireSnapshotNode(session, ref) == null) {
            return pageError(
                tool.name,
                session,
                "Ref $ref was not found in the current snapshot. Capture a new snapshot and retry."
            )
        }
        val markers = captureActionMarkers(session)
        val code =
            when {
                ref != null -> buildClickCode(session, ref, button, doubleClick, modifiers)
                else -> buildClickCodeForSelector(selector!!, button, doubleClick, modifiers)
            }
        val useNativeTap =
            ref != null &&
                button == "left" &&
                !doubleClick &&
                modifiers.isEmpty()
        val jsResult =
            when {
                useNativeTap && dispatchNativeTapByRef(session.webView, ref!!) == true ->
                    JSONObject()
                        .put("ok", true)
                        .put("ref", ref)
                        .put("button", button)
                        .put("doubleClick", false)
                        .put("activationMethod", "native_webview_tap")
                ref != null ->
                    dispatchClickByRef(
                        webView = session.webView,
                        ref = ref,
                        button = button,
                        modifiers = modifiers,
                        doubleClick = doubleClick
                    )
                else ->
                    dispatchClickBySelector(
                        webView = session.webView,
                        selector = selector!!,
                        button = button,
                        modifiers = modifiers,
                        doubleClick = doubleClick
                    )
            }
        if (jsResult?.optBoolean("ok", false) != true) {
            if (jsResult?.optString("error") == "ref_not_found") {
                return pageError(
                    tool.name,
                    session,
                    "Ref $ref was not found in the current snapshot. Capture a new snapshot and retry."
                )
            }
            if (jsResult?.optString("error") == "selector_not_found") {
                return pageError(
                    tool.name,
                    session,
                    "Selector ${selector ?: ""} did not match any elements."
                )
            }
            return error(tool.name, "Click failed: ${jsResult?.optString("error") ?: "unknown"}")
        }
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy =
                    BrowserActionSettlementPolicy(
                        waitForDocumentReady = true,
                        waitForTimeSeconds = 0.5
                    )
            )
        val downloadEvent = latestBrowserDownloadEventAfter(settlement.downloadMarker)
        if (downloadEvent?.status == "failed") {
            return pageError(
                tool.name,
                settlement.session,
                "Click triggered a download, but it failed: ${downloadEvent.error ?: downloadEvent.fileName}"
            )
        }

        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = code,
                result =
                    when {
                        ref != null -> "Clicked ref=$ref with button=$button${if (doubleClick) " (double)" else ""}"
                        else -> "Clicked selector=${selector ?: ""} with button=$button${if (doubleClick) " (double)" else ""}"
                    }
            )
        )
    }

    private fun dispatchNativeTapByRef(webView: WebView, ref: String): Boolean {
        val rect = resolveElementRect(webView, ref) ?: return false
        val pageScale = webView.scale.takeIf { it > 0f } ?: 1f
        val x =
            (((rect.left + rect.right) / 2f) * pageScale)
                .coerceIn(1f, (webView.width - 1).coerceAtLeast(1).toFloat())
        val y =
            (((rect.top + rect.bottom) / 2f) * pageScale)
                .coerceIn(1f, (webView.height - 1).coerceAtLeast(1).toFloat())
        return runOnMainSync {
            try {
                webView.requestFocus()
                val downTime = SystemClock.uptimeMillis()
                val down =
                    MotionEvent.obtain(
                        downTime,
                        downTime,
                        MotionEvent.ACTION_DOWN,
                        x,
                        y,
                        0
                    )
                val up =
                    MotionEvent.obtain(
                        downTime,
                        downTime + 16L,
                        MotionEvent.ACTION_UP,
                        x,
                        y,
                        0
                    )
                try {
                    val downHandled = webView.dispatchTouchEvent(down)
                    val upHandled = webView.dispatchTouchEvent(up)
                    downHandled || upHandled
                } finally {
                    down.recycle()
                    up.recycle()
                }
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun buildClickCodeForSelector(
        selector: String,
        button: String,
        doubleClick: Boolean,
        modifiers: Set<String>
    ): String {
        val method = if (doubleClick) "dblclick" else "click"
        val options = mutableListOf<String>()
        if (button != "left") {
            options += "button: ${quoteJsCode(button)}"
        }
        if (modifiers.isNotEmpty()) {
            options += "modifiers: ${renderJsArrayCode(modifiers.toList())}"
        }
        val locator = "page.locator(${quoteJsCode(selector)})"
        return if (options.isEmpty()) {
            "await $locator.$method();"
        } else {
            "await $locator.$method({ ${options.joinToString(", ")} });"
        }
    }

    private fun dispatchClickByRef(
        webView: WebView,
        ref: String,
        button: String,
        modifiers: Set<String>,
        doubleClick: Boolean
    ): JSONObject? {
        val buttonValue =
            when (button) {
                "middle" -> 1
                "right" -> 2
                else -> 0
            }
        val buttonsValue =
            when (button) {
                "middle" -> 4
                "right" -> 2
                else -> 1
            }

        val altKey = modifiers.contains("Alt")
        val controlKey = modifiers.contains("Control") || modifiers.contains("ControlOrMeta")
        val metaKey = modifiers.contains("Meta") || modifiers.contains("ControlOrMeta")
        val shiftKey = modifiers.contains("Shift")

        val script =
            """
            (function() {
                try {
                    const refValue = ${quoteJs(ref)};
                    ${browserRefResolverScript()}
                    const resolved = __operitResolveRef(refValue);
                    if (!resolved || !resolved.element) {
                        return JSON.stringify({ ok: false, error: "ref_not_found", ref: refValue });
                    }
                    const target = resolved.element;
                    const targetWindow = resolved.window || window;
                    const anchor = target.closest('a[href]');
                    try { target.scrollIntoView({ block: "center", inline: "center" }); } catch (_) {}
                    const rect = target.getBoundingClientRect();
                    const x = rect.left + rect.width / 2;
                    const y = rect.top + rect.height / 2;

                    try { target.focus({ preventScroll: true }); } catch (_) {}

                    const buttonValue = ${buttonValue};
                    const buttonsValue = ${buttonsValue};
                    const altKey = ${if (altKey) "true" else "false"};
                    const ctrlKey = ${if (controlKey) "true" else "false"};
                    const metaKey = ${if (metaKey) "true" else "false"};
                    const shiftKey = ${if (shiftKey) "true" else "false"};

                    function emit(type, detail) {
                        try {
                            const MouseEventCtor = targetWindow.MouseEvent || MouseEvent;
                            target.dispatchEvent(new MouseEventCtor(type, {
                                bubbles: true,
                                cancelable: true,
                                composed: true,
                                view: targetWindow,
                                detail: detail,
                                clientX: x,
                                clientY: y,
                                screenX: x,
                                screenY: y,
                                button: buttonValue,
                                buttons: buttonsValue,
                                altKey,
                                ctrlKey,
                                metaKey,
                                shiftKey
                            }));
                        } catch (_) {}
                    }

                    function clickOnce(detail) {
                        emit("mousedown", detail);
                        emit("mouseup", detail);
                        emit("click", detail);
                    }

                    let activationMethod = "mouse_event";
                    let activationTag = String(target.tagName || "").toLowerCase();
                    const nativeAnchorClickEligible = !${if (doubleClick) "true" else "false"} &&
                        buttonValue === 0 && !altKey && !ctrlKey && !metaKey && !shiftKey &&
                        !!anchor && typeof anchor.click === "function";

                    setTimeout(() => {
                        try {
                            if (nativeAnchorClickEligible) {
                                activationMethod = "native_anchor_click";
                                activationTag = String(anchor.tagName || "").toLowerCase();
                                anchor.click();
                            } else if (${if (doubleClick) "true" else "false"}) {
                                clickOnce(1);
                                clickOnce(2);
                                emit("dblclick", 2);
                            } else {
                                clickOnce(1);
                            }
                        } catch (_) {}
                    }, 0);

                    return JSON.stringify({
                        ok: true,
                        ref: refValue,
                        button: ${quoteJs(button)},
                        doubleClick: ${if (doubleClick) "true" else "false"},
                        tag: String(target.tagName || "").toLowerCase(),
                        activationMethod,
                        activationTag,
                        href: anchor ? String(anchor.href || "") : ""
                    });
                } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                }
            })();
            """.trimIndent()

        return runJsonScript(webView, script, "click_dispatch_error")
    }

    private fun dispatchHoverByRef(webView: WebView, ref: String): JSONObject? {
        val script =
            """
            (function() {
                try {
                    const refValue = ${quoteJs(ref)};
                    ${browserRefResolverScript()}
                    const resolved = __operitResolveRef(refValue);
                    if (!resolved || !resolved.element) {
                        return JSON.stringify({ ok: false, error: "ref_not_found" });
                    }
                    const target = resolved.element;
                    const targetWindow = resolved.window || window;
                    try { target.scrollIntoView({ block: "center", inline: "center" }); } catch (_) {}
                    const rect = target.getBoundingClientRect();
                    const x = rect.left + rect.width / 2;
                    const y = rect.top + rect.height / 2;
                    ["pointerover", "mouseover", "mouseenter", "mousemove"].forEach((type) => {
                        try {
                            const MouseEventCtor = targetWindow.MouseEvent || MouseEvent;
                            target.dispatchEvent(new MouseEventCtor(type, {
                                bubbles: true,
                                cancelable: true,
                                composed: true,
                                view: targetWindow,
                                clientX: x,
                                clientY: y
                            }));
                        } catch (_) {}
                    });
                    return JSON.stringify({ ok: true, ref: refValue });
                } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                }
            })();
            """.trimIndent()
        return runJsonScript(webView, script, "hover_dispatch_error")
    }

    private fun dispatchDragByRef(webView: WebView, startRef: String, endRef: String): JSONObject? {
        val script =
            """
            (function() {
                try {
                    ${browserRefResolverScript()}
                    const startResolved = __operitResolveRef(${quoteJs(startRef)});
                    const endResolved = __operitResolveRef(${quoteJs(endRef)});
                    const start = startResolved && startResolved.element;
                    const end = endResolved && endResolved.element;
                    if (!start || !end) {
                        return JSON.stringify({ ok: false, error: !start ? "start_ref_not_found" : "end_ref_not_found" });
                    }
                    const startWindow = (startResolved && startResolved.window) || window;
                    const endWindow = (endResolved && endResolved.window) || window;
                    try { start.scrollIntoView({ block: "center", inline: "center" }); } catch (_) {}
                    try { end.scrollIntoView({ block: "center", inline: "center" }); } catch (_) {}
                    const DataTransferCtor = startWindow.DataTransfer || endWindow.DataTransfer || (typeof DataTransfer === "function" ? DataTransfer : null);
                    const DragEventCtor = startWindow.DragEvent || endWindow.DragEvent || (typeof DragEvent === "function" ? DragEvent : null);
                    const EventCtor = startWindow.Event || endWindow.Event || Event;
                    const dataTransfer = typeof DataTransferCtor === "function" ? new DataTransferCtor() : null;
                    const dispatchDrag = (target, type) => {
                        try {
                            const event = DragEventCtor ? new DragEventCtor(type, {
                                bubbles: true,
                                cancelable: true,
                                composed: true,
                                dataTransfer
                            }) : new EventCtor(type, { bubbles: true, cancelable: true, composed: true });
                            target.dispatchEvent(event);
                        } catch (_) {
                            try {
                                const fallback = new EventCtor(type, { bubbles: true, cancelable: true, composed: true });
                                fallback.dataTransfer = dataTransfer;
                                target.dispatchEvent(fallback);
                            } catch (_) {}
                        }
                    };
                    dispatchDrag(start, "dragstart");
                    dispatchDrag(end, "dragenter");
                    dispatchDrag(end, "dragover");
                    dispatchDrag(end, "drop");
                    dispatchDrag(start, "dragend");
                    return JSON.stringify({ ok: true, startRef: ${quoteJs(startRef)}, endRef: ${quoteJs(endRef)} });
                } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                }
            })();
            """.trimIndent()
        return runJsonScript(webView, script, "drag_dispatch_error")
    }

    private fun dispatchClickBySelector(
        webView: WebView,
        selector: String,
        button: String,
        modifiers: Set<String>,
        doubleClick: Boolean
    ): JSONObject? {
        val buttonValue =
            when (button) {
                "middle" -> 1
                "right" -> 2
                else -> 0
            }
        val buttonsValue =
            when (button) {
                "middle" -> 4
                "right" -> 2
                else -> 1
            }

        val altKey = modifiers.contains("Alt")
        val controlKey = modifiers.contains("Control") || modifiers.contains("ControlOrMeta")
        val metaKey = modifiers.contains("Meta") || modifiers.contains("ControlOrMeta")
        val shiftKey = modifiers.contains("Shift")

        val script =
            """
            (function() {
                try {
                    const selectorValue = ${quoteJs(selector)};
                    const target = document.querySelector(selectorValue);
                    if (!target) {
                        return JSON.stringify({ ok: false, error: "selector_not_found", selector: selectorValue });
                    }
                    const targetWindow = target.ownerDocument && target.ownerDocument.defaultView ? target.ownerDocument.defaultView : window;
                    const anchor = target.closest('a[href]');
                    try { target.scrollIntoView({ block: "center", inline: "center" }); } catch (_) {}
                    const rect = target.getBoundingClientRect();
                    const x = rect.left + rect.width / 2;
                    const y = rect.top + rect.height / 2;

                    try { target.focus({ preventScroll: true }); } catch (_) {}

                    const buttonValue = ${buttonValue};
                    const buttonsValue = ${buttonsValue};
                    const altKey = ${if (altKey) "true" else "false"};
                    const ctrlKey = ${if (controlKey) "true" else "false"};
                    const metaKey = ${if (metaKey) "true" else "false"};
                    const shiftKey = ${if (shiftKey) "true" else "false"};

                    function emit(type, detail) {
                        try {
                            const MouseEventCtor = targetWindow.MouseEvent || MouseEvent;
                            target.dispatchEvent(new MouseEventCtor(type, {
                                bubbles: true,
                                cancelable: true,
                                composed: true,
                                view: targetWindow,
                                detail: detail,
                                clientX: x,
                                clientY: y,
                                screenX: x,
                                screenY: y,
                                button: buttonValue,
                                buttons: buttonsValue,
                                altKey,
                                ctrlKey,
                                metaKey,
                                shiftKey
                            }));
                        } catch (_) {}
                    }

                    function clickOnce(detail) {
                        emit("mousedown", detail);
                        emit("mouseup", detail);
                        emit("click", detail);
                    }

                    let activationMethod = "mouse_event";
                    let activationTag = String(target.tagName || "").toLowerCase();
                    const nativeAnchorClickEligible = !${if (doubleClick) "true" else "false"} &&
                        buttonValue === 0 && !altKey && !ctrlKey && !metaKey && !shiftKey &&
                        !!anchor && typeof anchor.click === "function";

                    setTimeout(() => {
                        try {
                            if (nativeAnchorClickEligible) {
                                activationMethod = "anchor_click";
                                activationTag = String(anchor.tagName || "").toLowerCase();
                                anchor.click();
                                return;
                            }
                            clickOnce(1);
                            if (${if (doubleClick) "true" else "false"}) {
                                emit("mousedown", 2);
                                emit("mouseup", 2);
                                emit("click", 2);
                                emit("dblclick", 2);
                            }
                            if (buttonValue === 0 && typeof target.click === "function") {
                                activationMethod = "native_click";
                                target.click();
                            }
                        } catch (_) {}
                    }, 0);

                    return JSON.stringify({
                        ok: true,
                        selector: selectorValue,
                        button: ${quoteJs(button)},
                        doubleClick: ${if (doubleClick) "true" else "false"},
                        activationMethod,
                        activationTag
                    });
                } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                }
            })();
            """.trimIndent()
        return runJsonScript(webView, script, "click_dispatch_error")
    }

    internal fun runJsonScript(webView: WebView, script: String, fallbackError: String): JSONObject? {
        return try {
            val payload =
                evaluateJavascriptAsync(
                    webView,
                    script,
                    DEFAULT_TIMEOUT_MS.coerceIn(2_000L, 8_000L)
                )
            JSONObject(extractAsyncJsValue(payload))
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: fallbackError)
        }
    }

    private fun parseClickModifiers(raw: String?): Pair<Set<String>, List<String>> {
        if (raw.isNullOrBlank()) {
            return emptySet<String>() to emptyList()
        }

        val allowed = setOf("Alt", "Control", "ControlOrMeta", "Meta", "Shift")
        val parsed = linkedSetOf<String>()
        val invalid = mutableListOf<String>()

        val arr =
            try {
                JSONArray(raw)
            } catch (_: Exception) {
                return emptySet<String>() to listOf(raw)
            }

        for (i in 0 until arr.length()) {
            val token = arr.optString(i, "").trim()
            if (token in allowed) {
                parsed.add(token)
            } else {
                invalid.add(token.ifBlank { "<empty>" })
            }
        }

        return parsed to invalid
    }

    internal fun readCurrentUrl(webView: WebView, fallback: String): String {
        return runCatching {
            extractAsyncJsValue(
                evaluateJavascriptAsync(
                    webView,
                    "(function(){ return String(location.href || ''); })();",
                    2_000L
                )
            )
        }.getOrDefault(fallback)
    }

    private fun browserFileUpload(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")

        val rawPaths = param(tool, "paths")?.trim().orEmpty()
        val shouldCancel = rawPaths.isBlank()

        val files: List<File> =
            if (shouldCancel) {
                emptyList()
            } else {
                val pathList = parseStringArrayParam(rawPaths)
                    ?: return error(tool.name, "paths must be a JSON array")

                val resolved = mutableListOf<File>()
                for (rawPath in pathList) {
                    val path = rawPath.trim()
                    if (path.isBlank()) {
                        return error(tool.name, "paths contains an empty item")
                    }
                    val file = File(path)
                    if (!file.isAbsolute) {
                        return error(tool.name, "path must be absolute: $path")
                    }
                    if (!file.exists() || !file.isFile) {
                        return error(tool.name, "file does not exist: $path")
                    }
                    resolved.add(file)
                }
                resolved
            }

        var resolvedCode: String? = null
        var resolvedResult: String? = null
        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        val markers = captureActionMarkers(session)
        val callbackError =
            runOnMainSync {
                ensureSessionAttachedOnMain(session.id)

                val callback = session.pendingFileChooserCallback
                    ?: return@runOnMainSync "No file chooser is active"

                return@runOnMainSync try {
                    if (shouldCancel) {
                        callback.onReceiveValue(null)
                        session.pendingFileChooserCallback = null
                        notifySessionStateChanged(session)
                        resolvedCode = "await fileChooser.cancel();"
                        resolvedResult = "Cancelled the active file chooser."
                    } else {
                        val uris = files.map { Uri.fromFile(it) }.toTypedArray()
                        callback.onReceiveValue(uris)
                        session.pendingFileChooserCallback = null
                        notifySessionStateChanged(session)
                        resolvedCode =
                            "await fileChooser.setFiles(${renderJsArrayCode(files.map { it.absolutePath })});"
                        resolvedResult =
                            "Uploaded ${files.size} file(s): ${files.joinToString(", ") { it.absolutePath }}"
                    }
                    null
                } catch (e: Exception) {
                    "Failed to resolve file chooser: ${e.message}"
                }
            }

        if (callbackError != null) {
            return error(tool.name, callbackError)
        }
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy = BrowserActionSettlementPolicy(waitForDocumentReady = true)
            )
        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = resolvedCode,
                result = resolvedResult
            )
        )
    }

    private fun browserWaitFor(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        if (cloakReady(session)) {
            val tRaw = param(tool, "time")?.trim()?.toDoubleOrNull()
            val txt = param(tool, "text")?.takeIf { it.isNotBlank() }
            val txtGone = param(tool, "textGone")?.takeIf { it.isNotBlank() }
            if (tRaw == null && txt == null && txtGone == null) return error(tool.name, "One of time, text, or textGone is required")
            val deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS.coerceAtLeast(10_000L)
            if (tRaw != null && tRaw > 0) Thread.sleep((tRaw * 1000.0).toLong().coerceIn(0L, 30_000L))
            if (txt != null) {
                var found = false
                while (System.currentTimeMillis() < deadline) {
                    val body = runCatching { com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.CloakBrowserTools.text(session.id, 20000) }.getOrElse { "" }
                    if (body.contains(txt)) { found = true; break }
                    Thread.sleep(300)
                }
                if (!found) return error(tool.name, "Timeout waiting for the requested condition")
            }
            if (txtGone != null) {
                var gone = false
                while (System.currentTimeMillis() < deadline) {
                    val body2 = runCatching { com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.CloakBrowserTools.text(session.id, 20000) }.getOrElse { "" }
                    if (!body2.contains(txtGone)) { gone = true; break }
                    Thread.sleep(300)
                }
                if (!gone) return error(tool.name, "Timeout waiting for the requested condition")
            }
            return ok(tool.name, buildBrowserResponse(code = "cloak:waitFor()", openTabs = renderOpenTabs(), pageState = renderPageState(session), result = "waited"))
        }
        val timeRaw = param(tool, "time")?.trim()?.takeIf { it.isNotBlank() }
        val parsedTimeSeconds = timeRaw?.toDoubleOrNull()
        if (timeRaw != null && parsedTimeSeconds == null) {
            return error(tool.name, "time must be a number")
        }
        val timeSeconds = parsedTimeSeconds?.takeIf { it > 0.0 }
        val text = param(tool, "text")?.takeIf { it.isNotBlank() }
        val textGone = param(tool, "textGone")?.takeIf { it.isNotBlank() }
        if (timeSeconds == null && text == null && textGone == null) {
            return error(tool.name, "One of time, text, or textGone is required")
        }

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        val markers = captureActionMarkers(session)
        timeSeconds?.let { seconds ->
            Thread.sleep(((seconds * 1000.0).toLong()).coerceAtLeast(0L).coerceAtMost(30_000L))
        }
        if (textGone != null && !waitForTextState(session, textGone = textGone)) {
            return error(tool.name, "Timeout waiting for the requested condition")
        }
        if (text != null && !waitForTextState(session, text = text)) {
            return error(tool.name, "Timeout waiting for the requested condition")
        }
        val finalSession = sessionById(session.id) ?: session
        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(finalSession.id)
        }
        val settlement =
            BrowserActionSettlement(
                registry = buildPageRegistry(),
                session = finalSession,
                snapshot = latestSnapshot(finalSession),
                consoleMarker = markers.consoleTimestamp,
                downloadMarker = markers.downloadTimestamp,
                timedOut = false
            )

        val summary =
            when {
                text != null && textGone != null -> "Waited until \"$text\" appeared and \"$textGone\" disappeared."
                text != null -> "Waited until \"$text\" appeared."
                textGone != null -> "Waited until \"$textGone\" disappeared."
                else -> "Waited for ${timeSeconds ?: 0.0} second(s)."
            }

        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = buildWaitForCode(timeSeconds, text, textGone),
                result = summary
            )
        )
    }

    private fun browserSnapshot(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val selector = param(tool, "selector")?.trim()?.takeIf { it.isNotBlank() }
        if (cloakReady(session)) {
            val html = runCatching { com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.CloakBrowserTools.html(session.id, 12000) }.getOrElse { "" }
            return ok(tool.name, buildBrowserResponse(code = "cloak:snapshot()", openTabs = renderOpenTabs(), pageState = renderPageState(session), result = html))
        }
        val depthRaw = param(tool, "depth")?.trim()?.takeIf { it.isNotBlank() }
        val depth =
            when {
                depthRaw == null -> null
                depthRaw.toIntOrNull() == null -> return error(tool.name, "depth must be a non-negative integer")
                depthRaw.toInt() < 0 -> return error(tool.name, "depth must be a non-negative integer")
                else -> depthRaw.toInt()
            }

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }

        val snapshot = captureSnapshotModel(session, selector = selector, depth = depth)
        session.lastSnapshot = snapshot
        val filename = param(tool, "filename")?.trim()?.takeIf { it.isNotBlank() }
        val snapshotSection =
            if (filename != null) {
                formatBrowserFileLink(
                    "Snapshot",
                    writeBrowserTextOutput(filename, snapshot.yaml, "page", "yml")
                )
            } else {
                snapshot.yaml
            }

        return ok(
            tool.name,
            buildBrowserResponse(
                openTabs = renderOpenTabs(),
                pageState = renderPageState(session),
                snapshot = snapshotSection,
                modalState = renderModalState(session),
                downloads = renderManagedDownloads(session, marker = 0L, includeAll = true)
            )
        )
    }

    private fun browserNavigateBack(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        if (cloakReady(session)) {
            val out = runCatching { com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.CloakBrowserTools.eval(session.id, "history.back(); 'ok'") }.getOrElse { it.message ?: "back failed" }
            notifySessionStateChanged(session)
            return ok(tool.name, buildBrowserResponse(code = "cloak:goBack()", openTabs = renderOpenTabs(), pageState = renderPageState(session), result = out))
        }
        val couldGoBack = session.canGoBack || runCatching { session.webView.canGoBack() }.getOrDefault(false)
        val markers = captureActionMarkers(session)

        runOnMainSync(timeoutMs = 8_000L) {
            ensureSessionAttachedOnMain(session.id)
            if (session.webView.canGoBack()) {
                session.webView.goBack()
            }
            refreshNavigationStateAsync(session)
            Unit
        }
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy =
                    BrowserActionSettlementPolicy(
                        timeoutMs = DEFAULT_TIMEOUT_MS.coerceAtLeast(4_000L),
                        waitForDocumentReady = true,
                        waitForNavigationChange = couldGoBack,
                        captureSnapshot = false
                    )
            )

        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = "await page.goBack();",
                result =
                    if (couldGoBack) {
                        "Navigated back."
                    } else {
                        "No back history entry was available."
                    }
            )
        )
    }

    private fun browserHover(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        if (cloakReady(session)) {
            val ref = param(tool, "ref")?.trim()?.takeIf { it.isNotBlank() }
                ?: return error(tool.name, "ref is required")
            val js = "(function(){var e=document.querySelector(" + quoteJsCode(ref) + ");if(!e)return 'not_found';e.dispatchEvent(new MouseEvent('mouseover',{bubbles:true}));return 'hovered';})()"
            val out = runCatching { com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.CloakBrowserTools.eval(session.id, js) }.getOrElse { it.message ?: "hover failed" }
            return ok(tool.name, buildBrowserResponse(code = "cloak:hover()", openTabs = renderOpenTabs(), pageState = renderPageState(session), result = out))
        }
        val ref = param(tool, "ref")?.trim()?.takeIf { it.isNotBlank() }
            ?: return error(tool.name, "ref is required")

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        if (requireSnapshotNode(session, ref) == null) {
            return pageError(
                tool.name,
                session,
                "Ref $ref was not found in the current snapshot. Capture a new snapshot and retry."
            )
        }
        val markers = captureActionMarkers(session)
        val jsResult = dispatchHoverByRef(session.webView, ref)
        if (jsResult?.optBoolean("ok", false) != true) {
            return if (jsResult?.optString("error") == "ref_not_found") {
                pageError(
                    tool.name,
                    session,
                    "Ref $ref was not found in the current snapshot. Capture a new snapshot and retry."
                )
            } else {
                error(tool.name, "Hover failed: ${jsResult?.optString("error") ?: "unknown"}")
            }
        }
        val settlement = settleBrowserAction(session, markers)

        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = "await ${locatorExpressionForRef(session, ref)}.hover();",
                result = "Hovered ref=$ref"
            )
        )
    }

    private fun browserDrag(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val startRef = param(tool, "startRef")?.trim()?.takeIf { it.isNotBlank() }
            ?: return error(tool.name, "startRef is required")
        val endRef = param(tool, "endRef")?.trim()?.takeIf { it.isNotBlank() }
            ?: return error(tool.name, "endRef is required")

        if (cloakReady(session)) {
            // Cloak 引擎：用 CDP Input.dispatchDragEvent 之外的通用鼠标序列实现拖拽
            val coordsJs =
                """
                (function(){
                  function center(ref){
                    var el = document.querySelector('[data-operit-ref="'+ref+'"]');
                    if(!el) return null;
                    var r = el.getBoundingClientRect();
                    return {x: r.left + r.width/2, y: r.top + r.height/2};
                  }
                  var a = center(${org.json.JSONObject.quote(startRef)});
                  var b = center(${org.json.JSONObject.quote(endRef)});
                  if(!a || !b) return null;
                  return {sx: a.x, sy: a.y, ex: b.x, ey: b.y};
                })()
                """.trimIndent()
            val raw = runCatching {
                com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.CloakBrowserTools
                    .eval(session.id, coordsJs)
            }.getOrElse { null }
            val obj = runCatching { org.json.JSONObject(raw ?: "") }.getOrNull()
                ?: return error(
                    tool.name,
                    "drag 失败：未在页面中定位到 ref=$startRef → $endRef 的中心坐标"
                )
            val sx = obj.optDouble("sx", Double.NaN)
            val sy = obj.optDouble("sy", Double.NaN)
            val ex = obj.optDouble("ex", Double.NaN)
            val ey = obj.optDouble("ey", Double.NaN)
            if (sx.isNaN() || sy.isNaN() || ex.isNaN() || ey.isNaN()) {
                return error(tool.name, "drag 失败：坐标解析异常")
            }
            val client = com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak
                .CloakBrowserTools.clientOf(session.id)
                ?: return error(tool.name, "drag 失败：会话 ${session.id} 无 CDP 连接")
            val okDrag = runCatching {
                // 鼠标按下 → 分步移动（触发 dragstart/dragover）→ 松开
                client.mouseEvent("mousePressed", sx, sy, button = "left", clickCount = 1)
                val steps = 12
                for (i in 1..steps) {
                    val t = i.toDouble() / steps
                    client.mouseEvent(
                        "mouseMoved",
                        sx + (ex - sx) * t,
                        sy + (ey - sy) * t,
                        button = "left",
                        buttons = 1
                    )
                    Thread.sleep(16)
                }
                client.mouseEvent("mouseReleased", ex, ey, button = "left", clickCount = 1)
                true
            }.getOrElse { false }
            return if (okDrag) {
                ok(tool.name, "Dragged ref=$startRef -> ref=$endRef (cloak)")
            } else {
                error(tool.name, "drag 失败：CDP 鼠标事件派发异常")
            }
        }

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        if (requireSnapshotNode(session, startRef) == null) {
            return pageError(
                tool.name,
                session,
                "Ref $startRef was not found in the current snapshot. Capture a new snapshot and retry."
            )
        }
        if (requireSnapshotNode(session, endRef) == null) {
            return pageError(
                tool.name,
                session,
                "Ref $endRef was not found in the current snapshot. Capture a new snapshot and retry."
            )
        }
        val markers = captureActionMarkers(session)
        val jsResult = dispatchDragByRef(session.webView, startRef, endRef)
        if (jsResult?.optBoolean("ok", false) != true) {
            return when (jsResult?.optString("error")) {
                "start_ref_not_found" ->
                    pageError(
                        tool.name,
                        session,
                        "Ref $startRef was not found in the current snapshot. Capture a new snapshot and retry."
                    )
                "end_ref_not_found" ->
                    pageError(
                        tool.name,
                        session,
                        "Ref $endRef was not found in the current snapshot. Capture a new snapshot and retry."
                    )
                else -> error(tool.name, "Drag failed: ${jsResult?.optString("error") ?: "unknown"}")
            }
        }
        val settlement = settleBrowserAction(session, markers)
        val startLocator = locatorExpressionForRef(session, startRef)
        val endLocator = locatorExpressionForRef(session, endRef)

        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = "await $startLocator.dragTo($endLocator);",
                result = "Dragged ref=$startRef to ref=$endRef"
            )
        )
    }

    private fun browserEvaluate(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        if (cloakReady(session)) {
            val fn = param(tool, "function")?.trim()
            if (fn.isNullOrBlank()) return error(tool.name, "function is required")
            val out = com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.BrowserEngineBridge.evaluate(
                sessionId = session.id,
                engine = session.engine,
                script = "(function(){return (" + fn + ")();})()"
            )
            return okCloak(tool, session, fn, out)
        }
        val functionSource = param(tool, "function")?.trim()
        if (functionSource.isNullOrBlank()) {
            return error(tool.name, "function is required")
        }
        val ref = param(tool, "ref")?.trim()?.takeIf { it.isNotBlank() }
        val elementDescription = param(tool, "element")?.trim()?.takeIf { it.isNotBlank() }
        if (!elementDescription.isNullOrBlank() && ref == null) {
            return error(tool.name, "ref is required when element is provided")
        }

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        if (ref != null && requireSnapshotNode(session, ref) == null) {
            return pageError(
                tool.name,
                session,
                "Ref $ref was not found in the current snapshot. Capture a new snapshot and retry."
            )
        }
        val markers = captureActionMarkers(session)
        val value = evaluatePageFunction(session.webView, functionSource, ref, DEFAULT_TIMEOUT_MS)
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy = BrowserActionSettlementPolicy(waitForDocumentReady = true)
            )
        val code =
            if (ref != null) {
                "await ${locatorExpressionForRef(session, ref)}.evaluate(${functionSource});"
            } else {
                "await page.evaluate(${functionSource});"
            }
        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = code,
                result = value
            )
        )
    }

    private fun browserFillForm(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val rawFields = param(tool, "fields")?.trim()
        if (rawFields.isNullOrBlank()) {
            return error(tool.name, "fields is required")
        }
        val fields = parseFormFields(rawFields) ?: return error(tool.name, "fields must be a JSON array")
        if (fields.isEmpty()) {
            return error(tool.name, "fields must not be empty")
        }

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        fields.forEach { field ->
            val ref = field.optString("ref").trim()
            if (ref.isNotBlank() && requireSnapshotNode(session, ref) == null) {
                return pageError(
                    tool.name,
                    session,
                    "Ref $ref was not found in the current snapshot. Capture a new snapshot and retry."
                )
            }
        }
        val markers = captureActionMarkers(session)
        val result = fillFormFields(session.webView, rawFields)
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy = BrowserActionSettlementPolicy(waitForDocumentReady = true)
            )
        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = "await page.fillForm(/* ${fields.size} field(s) */);",
                result = result
            )
        )
    }

    private fun browserHandleDialog(tool: AITool): ToolResult {
        val acceptRaw = param(tool, "accept")
            ?: return error(tool.name, "accept is required")
        val accept =
            acceptRaw.equals("true", ignoreCase = true) ||
                acceptRaw == "1" ||
                acceptRaw.equals("yes", ignoreCase = true)
        val promptText = param(tool, "promptText")
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val pending = session.pendingDialog ?: return error(tool.name, "No dialog is currently open")
        val markers = captureActionMarkers(session)

        runOnMainSync<Unit> {
            resolvePendingDialogOnMain(
                session = session,
                accept = accept,
                promptText = promptText
            )
        }
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy = BrowserActionSettlementPolicy(waitForDocumentReady = true)
            )

        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code =
                    if (accept) {
                        "await dialog.accept(${quoteJsCode(promptText ?: pending.defaultValue.orEmpty())});"
                    } else {
                        "await dialog.dismiss();"
                    },
                modalState = "No modal dialog is currently open.",
                result = "Handled ${pending.type} dialog."
            )
        )
    }

    private fun browserConsoleMessages(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val level = param(tool, "level")?.trim()?.lowercase(Locale.ROOT).orEmpty().ifBlank { "info" }
        if (level !in setOf("error", "warning", "warn", "info", "debug")) {
            return error(tool.name, "level must be one of: error, warning, info, debug")
        }
        val rendered = renderAllConsoleMessages(session, level)
        val filename = param(tool, "filename")?.trim()?.takeIf { it.isNotBlank() }
        val sectionContent =
            if (filename != null) {
                "Saved console messages to ${writeBrowserTextOutput(filename, rendered, "console_messages", "log")}"
            } else {
                rendered
            }
        return ok(
            tool.name,
            buildBrowserResponse(
                openTabs = renderOpenTabs(),
                pageState = renderPageState(session),
                consoleMessages = sectionContent
            )
        )
    }

    private fun browserNetworkRequests(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        val includeStatic = boolParam(tool, "includeStatic", false)
        val rendered = renderNetworkRequestLog(session, includeStatic)
        val filename = param(tool, "filename")?.trim()?.takeIf { it.isNotBlank() }
        val resultText =
            if (filename != null) {
                "Saved network requests to ${writeBrowserTextOutput(filename, rendered, "network_requests", "log")}"
            } else {
                rendered
            }
        return ok(
            tool.name,
            buildBrowserResponse(
                openTabs = renderOpenTabs(),
                pageState = renderPageState(session),
                result = resultText
            )
        )
    }

    private fun browserPressKey(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        if (cloakReady(session)) {
            val key = param(tool, "key")?.trim().orEmpty()
            if (key.isBlank()) return error(tool.name, "key is required")
            val out = runCatching { com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.CloakBrowserTools.pressEnter(session.id) }.getOrElse { it.message ?: "press failed" }
            notifySessionStateChanged(session)
            return ok(tool.name, buildBrowserResponse(code = "cloak:pressKey()", openTabs = renderOpenTabs(), pageState = renderPageState(session), result = out))
        }
        val key = param(tool, "key")?.trim()
        if (key.isNullOrBlank()) {
            return error(tool.name, "key is required")
        }

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        val markers = captureActionMarkers(session)
        val result = pressKeyOnPage(session.webView, key)
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy =
                    if (key.equals("Enter", ignoreCase = true)) {
                        BrowserActionSettlementPolicy(
                            waitForDocumentReady = true,
                            waitForTimeSeconds = 0.5
                        )
                    } else {
                        BrowserActionSettlementPolicy()
                    }
            )
        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = "await page.keyboard.press(${quoteJsCode(key)});",
                result = result
            )
        )
    }

    private fun browserResize(tool: AITool): ToolResult {
        ensureOverlayPermission(tool.name)?.let { return it }
        val width = intParam(tool, "width", -1)
        val height = intParam(tool, "height", -1)
        if (width <= 0 || height <= 0) {
            return error(tool.name, "width and height must be positive integers")
        }
        val density =
            context.resources.displayMetrics.density
                .takeIf { it.isFinite() && it > 0f }
                ?: 1f
        val cssPixelCount = width.toLong() * height.toLong()
        val layoutWidth = kotlin.math.ceil(width.toDouble() * density).toLong()
        val layoutHeight = kotlin.math.ceil(height.toDouble() * density).toLong()
        val layoutPixelCount = layoutWidth * layoutHeight
        if (
            width > MAX_VIEWPORT_DIMENSION_CSS_PX ||
                height > MAX_VIEWPORT_DIMENSION_CSS_PX ||
                cssPixelCount > MAX_VIEWPORT_PIXEL_COUNT ||
                layoutPixelCount > MAX_VIEWPORT_LAYOUT_PIXEL_COUNT
        ) {
            return error(
                tool.name,
                "viewport is too large for this device; maximum side is " +
                    "$MAX_VIEWPORT_DIMENSION_CSS_PX CSS px and the requested pixel area " +
                    "must fit the device rendering budget"
            )
        }

        val session =
            runOnMainSync {
                getSession(null) ?: createSessionTabOnMain(context.applicationContext, "about:blank")
            }
        if (cloakReady(session)) {
            session.cloakClient?.setDeviceMetrics(width.toInt(), height.toInt(), density.toDouble(), false)
            session.viewportWidthPx = width
            session.viewportHeightPx = height
            notifySessionStateChanged(session)
            return ok(tool.name, buildBrowserResponse(code = "cloak:resize()", openTabs = renderOpenTabs(), pageState = renderPageState(session), result = "Resized cloak viewport to " + width.toInt() + "x" + height.toInt()))
        }
        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
            session.viewportWidthPx = width
            session.viewportHeightPx = height
            session.hasExplicitViewportSize = true
            browserHost?.setViewportSize(width, height)
            applyViewportOverride(session)
            refreshSessionUiOnMain(session.id)
        }
        Thread.sleep(100)

        return ok(
            tool.name,
            buildBrowserResponse(
                code = "await page.setViewportSize({ width: $width, height: $height });",
                openTabs = renderOpenTabs(),
                pageState = renderPageState(session),
                snapshot = captureSnapshotText(session),
                result = "Resized browser viewport to ${width}x${height}."
            )
        )
    }

    private fun browserRunCode(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        if (cloakReady(session)) {
            val code = param(tool, "code")?.trim()
            if (code.isNullOrBlank()) return error(tool.name, "code is required")
            val out = runCatching { com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.CloakBrowserTools.eval(session.id, code) }.getOrElse { it.message ?: "runCode failed" }
            return ok(tool.name, buildBrowserResponse(code = code, openTabs = renderOpenTabs(), pageState = renderPageState(session), result = out))
        }
        val code = param(tool, "code")?.trim()
        if (code.isNullOrBlank()) {
            return error(tool.name, "code is required")
        }

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        val markers = captureActionMarkers(session)
        val result = runPlaywrightLikeCode(session, code)
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy = BrowserActionSettlementPolicy(waitForDocumentReady = true)
            )
        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = code,
                result = result
            )
        )
    }

    private fun browserSelectOption(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        if (cloakReady(session)) {
            val ref = param(tool, "ref")?.trim()?.takeIf { it.isNotBlank() }
                ?: return error(tool.name, "ref is required")
            val values = parseStringArrayParam(param(tool, "values"))
                ?: return error(tool.name, "values must be a JSON array")
            if (values.isEmpty()) return error(tool.name, "values must not be empty")
            val v = values.first()
            val js = "(function(){var e=document.querySelector(" + quoteJsCode(ref) + ");if(!e)return 'not_found';e.value=" + quoteJsCode(v) + ";e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'selected:'+e.value;})()"
            val out = runCatching { com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.CloakBrowserTools.eval(session.id, js) }.getOrElse { it.message ?: "select failed" }
            return ok(tool.name, buildBrowserResponse(code = "cloak:selectOption()", openTabs = renderOpenTabs(), pageState = renderPageState(session), result = out))
        }
        val ref = param(tool, "ref")?.trim()?.takeIf { it.isNotBlank() }
            ?: return error(tool.name, "ref is required")
        val values = parseStringArrayParam(param(tool, "values"))
            ?: return error(tool.name, "values must be a JSON array")
        if (values.isEmpty()) {
            return error(tool.name, "values must not be empty")
        }

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        if (requireSnapshotNode(session, ref) == null) {
            return pageError(
                tool.name,
                session,
                "Ref $ref was not found in the current snapshot. Capture a new snapshot and retry."
            )
        }
        val markers = captureActionMarkers(session)
        val result = selectOptionsByRef(session.webView, ref, values)
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy = BrowserActionSettlementPolicy(waitForDocumentReady = true)
            )
        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = "await ${locatorExpressionForRef(session, ref)}.selectOption(${renderJsArrayCode(values)});",
                result = result
            )
        )
    }

    private fun browserTabs(tool: AITool): ToolResult {
        val action = param(tool, "action")?.trim()?.lowercase(Locale.ROOT)
            ?: return error(tool.name, "action is required")
        return when (action) {
            "list" -> {
                val registry = buildPageRegistry()
                val session = registry.activeSessionId?.let { sessionId -> sessionById(sessionId) }
                ok(
                    tool.name,
                    buildBrowserResponse(
                        openTabs = renderOpenTabs(registry),
                        pageState = session?.let { activeSession -> renderPageState(activeSession) } ?: "No active page.",
                        snapshot = session?.let { activeSession -> captureSnapshotText(activeSession) },
                        result = "Listed open tabs."
                    )
                )
            }

            "create" -> {
                ensureOverlayPermission(tool.name)?.let { return it }
                val session =
                    runOnMainSync {
                        createSessionTabOnMain(context.applicationContext, initialUrl = "about:blank")
                    }
                val settlement = settleBrowserAction(session, captureActionMarkers(session))
                ok(
                    tool.name,
                    buildSettledBrowserResponse(
                        settlement = settlement,
                        result = "Created tab ${currentTabIndex(session.id)}."
                    )
                )
            }

            "select" -> {
                val index = intParam(tool, "index", -1)
                if (index < 0) {
                    return error(tool.name, "index is required for action=select")
                }
                val targetId = sessionIdAtIndex(index) ?: return error(tool.name, "Tab index out of range: $index")
                runOnMainSync<Unit> {
                    ensureSessionAttachedOnMain(targetId)
                }
                val session = sessions[targetId] ?: return error(tool.name, "Tab index out of range: $index")
                val settlement = settleBrowserAction(session, captureActionMarkers(session))
                ok(
                    tool.name,
                    buildSettledBrowserResponse(
                        settlement = settlement,
                        result = "Selected tab $index."
                    )
                )
            }

            "close" -> {
                val requestedIndex = param(tool, "index")?.trim()?.toIntOrNull()
                val targetId =
                    when {
                        requestedIndex != null -> sessionIdAtIndex(requestedIndex)
                        else -> resolvePreferredSessionId()
                    } ?: return error(tool.name, "No tab available to close")
                if (!closeSession(targetId)) {
                    return error(tool.name, "Failed to close tab")
                }
                val registry = buildPageRegistry()
                val active = registry.activeSessionId?.let { sessionId -> sessionById(sessionId) }
                ok(
                    tool.name,
                    buildBrowserResponse(
                        openTabs = renderOpenTabs(registry),
                        pageState = active?.let { activeSession -> renderPageState(activeSession) } ?: "No active page.",
                        snapshot = active?.let { activeSession -> captureSnapshotText(activeSession) },
                        result =
                            if (active != null) {
                                "Closed tab. Active tab is now ${currentTabIndex(active.id)}."
                            } else {
                                "Closed the last tab."
                            }
                    )
                )
            }

            else -> error(tool.name, "action must be one of: list, create, select, close")
        }
    }

    private fun browserTakeScreenshot(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        if (cloakReady(session)) {
            val path = com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.BrowserEngineBridge.screenshot(
                context = context.applicationContext,
                sessionId = session.id,
                engine = session.engine
            )
            return okCloak(tool, session, "cloak:screenshot", path)
        }
        val type = param(tool, "type")?.trim()?.lowercase(Locale.ROOT).orEmpty().ifBlank { "png" }
        if (type !in setOf("png", "jpeg", "jpg")) {
            return error(tool.name, "type must be png or jpeg")
        }
        val element = param(tool, "element")?.trim()?.takeIf { it.isNotBlank() }
        val ref = param(tool, "ref")?.trim()?.takeIf { it.isNotBlank() }
        val fullPage = boolParam(tool, "fullPage", false)
        if (element != null && ref == null) {
            return error(tool.name, "ref is required when element is provided")
        }
        if (fullPage && ref != null) {
            return error(tool.name, "fullPage cannot be used with element screenshots")
        }
        val filename = param(tool, "filename")?.trim()?.takeIf { it.isNotBlank() }
        val savedPath = takeScreenshot(session, filename, type, fullPage, ref)
        return ok(
            tool.name,
            buildBrowserResponse(
                openTabs = renderOpenTabs(),
                pageState = renderPageState(session),
                result = "Saved screenshot to $savedPath"
            )
        )
    }

    private fun browserType(tool: AITool): ToolResult {
        val session = getSession(null) ?: return error(tool.name, "No active browser tab")
        if (cloakReady(session)) {
            val sel = param(tool, "selector")?.trim() ?: param(tool, "ref")?.trim()
                ?: return error(tool.name, "cloak 引擎需要 selector 参数")
            val txt = param(tool, "text") ?: return error(tool.name, "text is required")
            val out = com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.BrowserEngineBridge.typeInto(
                sessionId = session.id,
                engine = session.engine,
                selector = sel,
                text = txt
            )
            return okCloak(tool, session, "cloak:type", out)
        }
        val ref = param(tool, "ref")?.trim()?.takeIf { it.isNotBlank() }
            ?: return error(tool.name, "ref is required")
        val text = param(tool, "text") ?: return error(tool.name, "text is required")
        val submit = boolParam(tool, "submit", false)
        val slowly = boolParam(tool, "slowly", false)

        runOnMainSync<Unit> {
            ensureSessionAttachedOnMain(session.id)
        }
        if (requireSnapshotNode(session, ref) == null) {
            return pageError(
                tool.name,
                session,
                "Ref $ref was not found in the current snapshot. Capture a new snapshot and retry."
            )
        }
        val markers = captureActionMarkers(session)
        val result = typeIntoElementByRef(session.webView, ref, text, submit, slowly)
        val settlement =
            settleBrowserAction(
                initialSession = session,
                markers = markers,
                policy =
                    if (submit || slowly) {
                        BrowserActionSettlementPolicy(
                            waitForDocumentReady = true,
                            waitForTimeSeconds = 0.5
                        )
                    } else {
                        BrowserActionSettlementPolicy()
                    }
            )
        val code =
            buildString {
                val locator = locatorExpressionForRef(session, ref)
                if (slowly) {
                    append("await $locator.pressSequentially(${quoteJsCode(text)});")
                } else {
                    append("await $locator.fill(${quoteJsCode(text)});")
                }
                if (submit) {
                    append("\nawait $locator.press('Enter');")
                }
            }
        return ok(
            tool.name,
            buildSettledBrowserResponse(
                settlement = settlement,
                code = code,
                result = result
            )
        )
    }

    private fun browserClose(tool: AITool): ToolResult {
        val activeId = resolvePreferredSessionId()
            ?: return ok(tool.name, buildBrowserResponse(openTabs = "No open tabs.", result = "No browser tab was open."))
        if (!closeSession(activeId)) {
            return error(tool.name, "Failed to close the current tab")
        }
        val registry = buildPageRegistry()
        val active = registry.activeSessionId?.let { sessionId -> sessionById(sessionId) }
        return ok(
            tool.name,
            buildBrowserResponse(
                openTabs = renderOpenTabs(registry),
                pageState = active?.let { activeSession -> renderPageState(activeSession) } ?: "No active page.",
                snapshot = active?.let { activeSession -> captureSnapshotText(activeSession) },
                result =
                    if (active != null) {
                        "Closed the current tab."
                    } else {
                        "Closed the last tab and browser overlay."
                    }
            )
        )
    }

    private fun browserCloseAll(tool: AITool): ToolResult {
        val ids = orderedSessionIds()
        if (ids.isEmpty()) {
            return ok(tool.name, buildBrowserResponse(openTabs = "No open tabs.", result = "No browser tabs were open."))
        }

        ids.forEach { sessionId ->
            closeSession(sessionId)
        }

        return ok(
            tool.name,
            buildBrowserResponse(
                openTabs = "No open tabs.",
                pageState = "No active page.",
                result = "Closed all browser tabs."
            )
        )
    }

    private fun determineExtensionFromMimeType(mimeType: String): String =
        when {
            mimeType.lowercase(Locale.ROOT).startsWith("image/") -> ".${mimeType.lowercase(Locale.ROOT).substringAfter('/')}"
            mimeType.lowercase(Locale.ROOT).startsWith("audio/") -> ".${mimeType.lowercase(Locale.ROOT).substringAfter('/')}"
            mimeType.lowercase(Locale.ROOT).startsWith("video/") -> ".${mimeType.lowercase(Locale.ROOT).substringAfter('/')}"
            mimeType.lowercase(Locale.ROOT).contains("pdf") -> ".pdf"
            mimeType.lowercase(Locale.ROOT).contains("json") -> ".json"
            mimeType.lowercase(Locale.ROOT).contains("xml") -> ".xml"
            mimeType.lowercase(Locale.ROOT).contains("csv") -> ".csv"
            mimeType.lowercase(Locale.ROOT).contains("zip") -> ".zip"
            mimeType.lowercase(Locale.ROOT).contains("html") -> ".html"
            mimeType.lowercase(Locale.ROOT).contains("javascript") -> ".js"
            mimeType.lowercase(Locale.ROOT).contains("plain") -> ".txt"
            else -> ".bin"
        }

    private fun quoteJs(value: String): String = JSONObject.quote(value)

    internal fun quoteJsCode(value: String): String {
        val escaped =
            buildString(value.length + 8) {
                value.forEach { ch ->
                    when (ch) {
                        '\\' -> append("\\\\")
                        '\'' -> append("\\'")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(ch)
                    }
                }
            }
        return "'$escaped'"
    }

    internal fun renderJsArrayCode(values: Collection<String>): String =
        values.joinToString(prefix = "[", postfix = "]", separator = ", ") { quoteJsCode(it) }

    internal fun nextSnapshotGeneration(): Long = snapshotGenerationSeed.incrementAndGet()
    internal fun <T> runOnMainSync(timeoutMs: Long = 8_000L, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }

        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null

        mainHandler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw RuntimeException("Main-thread operation timeout (${timeoutMs}ms)")
        }

        if (error != null) {
            throw RuntimeException(error)
        }

        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}

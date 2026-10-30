package com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.camoufox.CamoufoxBrowserTools
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.camoufox.CamoufoxFingerprint

/**
 * 统一浏览器操作门面。
 *
 * 设计目标：**一个浏览器，双引擎**。
 * 调用方只面对本接口，内部根据 WebSession.engine 分发到：
 *   - WEBVIEW   → 原有 Android WebView 路径
 *   - CLOAK     → proot 内 CloakBrowser（CDP 控制）
 *
 * 这样 UI、工具、AI 调用方都不需要感知引擎差异。
 */
internal object BrowserEngineBridge {
    private const val TAG = "BrowserBridge"

    // ---------------------------------------------------------------- 生命周期

    /**
     * 确保指定会话的引擎已就绪。
     * CLOAK 引擎会按需启动 Chromium 并建立 CDP 连接。
     */
    fun ensureEngine(context: Context, sessionId: String, engine: BrowserEngine): String {
        when (engine) {
            BrowserEngine.CLOAK -> {
                val cdp = CloakLauncher.launch(context, currentFingerprint(sessionId))
                CloakBrowserTools.attach(sessionId, cdp)
                return "cloak"
            }
            BrowserEngine.CAMOUFOX -> {
                val port = CamoufoxBrowserTools.attach(context, sessionId, currentCamoufoxFingerprint(sessionId))
                return if (port > 0) "camoufox" else "camoufox:failed"
            }
            BrowserEngine.WEBVIEW -> return "webview"
        }
    }

    /** 切换引擎。会关闭旧引擎资源，返回真实启动结果（含失败原因）。 */
    fun switchEngine(context: Context, sessionId: String, target: BrowserEngine): String {
        AppLogger.i(TAG, "会话 $sessionId 切换引擎 → $target")
        return when (target) {
            BrowserEngine.CLOAK -> {
                try {
                    val cdp = CloakLauncher.launch(context, currentFingerprint(sessionId))
                    CloakBrowserTools.attach(sessionId, cdp)
                    val st = CloakLauncher.status()
                    buildString {
                        append("engine=cloak\n")
                        append("running=").append(st.running).append('\n')
                        append("port=").append(st.port).append('\n')
                        append("pid=").append(st.pid).append('\n')
                        append("version=").append(st.version).append('\n')
                        append("environment=").append(CloakLauncher.environmentSummary(context))
                        if (!st.lastError.isNullOrBlank()) {
                            append("\nwarning=").append(st.lastError)
                        }
                    }
                } catch (e: Throwable) {
                    AppLogger.e(TAG, "切换到 cloak 失败", e)
                    "engine=cloak\nrunning=false\n" +
                        "environment=" + CloakLauncher.environmentSummary(context) +
                        "\nerror=" + (e.message ?: e.javaClass.simpleName)
                }
            }
            BrowserEngine.CAMOUFOX -> {
                try {
                    val port = CamoufoxBrowserTools.attach(
                        context, sessionId, currentCamoufoxFingerprint(sessionId)
                    )
                    val st = com.ai.assistance.operit.core.tools.defaultTool.websession
                        .browser.camoufox.CamoufoxLauncher.status()
                    buildString {
                        append("engine=camoufox\n")
                        append("running=").append(st.running).append('\n')
                        append("bidiPort=").append(st.bidiPort).append('\n')
                        append("marionettePort=").append(st.marionettePort).append('\n')
                        append("pid=").append(st.pid).append('\n')
                        append("version=").append(st.version).append('\n')
                        append("attached=").append(port > 0)
                        append("\nenvironment=").append(
                            com.ai.assistance.operit.core.tools.defaultTool.websession
                                .browser.camoufox.CamoufoxLauncher.environmentSummary(context)
                        )
                        if (!st.lastError.isNullOrBlank()) {
                            append("\nwarning=").append(st.lastError)
                        }
                    }
                } catch (e: Throwable) {
                    AppLogger.e(TAG, "切换到 camoufox 失败", e)
                    "engine=camoufox\nrunning=false\nerror=" +
                        (e.message ?: e.javaClass.simpleName)
                }
            }
            BrowserEngine.WEBVIEW -> "engine=webview"
        }
    }

    /** 释放某会话的引擎资源。 */
    fun releaseEngine(sessionId: String) {
        CloakBrowserTools.detach(sessionId)
        CamoufoxBrowserTools.detach(sessionId)
    }

    // ---------------------------------------------------------------- 统一操作

    /** 导航。 */
    fun navigate(context: Context, sessionId: String, engine: BrowserEngine, url: String): String {
        return when (engine) {
            BrowserEngine.CLOAK -> {
                ensureEngine(context, sessionId, engine)
                CloakBrowserTools.navigate(sessionId, url)
            }
            BrowserEngine.CAMOUFOX -> {
                ensureEngine(context, sessionId, engine)
                CamoufoxBrowserTools.navigate(sessionId, url)
            }
            BrowserEngine.WEBVIEW -> "webview:navigate:$url"
        }
    }

    /** 历史后退（Camoufox 走 BiDi 的 history.back；Cloak 走 CDP）。 */
    fun navigateBack(sessionId: String, engine: BrowserEngine): String =
        when (engine) {
            BrowserEngine.CAMOUFOX -> CamoufoxBrowserTools.navigateBack(sessionId)
            BrowserEngine.CLOAK -> CloakBrowserTools.eval(sessionId, "history.back();")
            BrowserEngine.WEBVIEW ->
                throw IllegalStateException("WEBVIEW 引擎请走原有 goBack 路径")
        }

    /** 执行 JS。 */
    fun evaluate(sessionId: String, engine: BrowserEngine, script: String): String =
        when (engine) {
            BrowserEngine.CLOAK -> CloakBrowserTools.eval(sessionId, script)
            BrowserEngine.CAMOUFOX -> CamoufoxBrowserTools.eval(sessionId, script)
            BrowserEngine.WEBVIEW ->
                throw IllegalStateException("WEBVIEW 引擎请走原有 evaluateJavascript 路径")
        }

    /** 截图，返回文件路径。 */
    fun screenshot(context: Context, sessionId: String, engine: BrowserEngine): String =
        when (engine) {
            BrowserEngine.CLOAK -> CloakBrowserTools.screenshot(sessionId, context)
            BrowserEngine.CAMOUFOX -> CamoufoxBrowserTools.screenshot(sessionId, context)
            BrowserEngine.WEBVIEW ->
                throw IllegalStateException("WEBVIEW 引擎请走原有截图路径")
        }

    /** 点击选择器。 */
    fun clickSelector(sessionId: String, engine: BrowserEngine, selector: String): String =
        when (engine) {
            BrowserEngine.CLOAK -> CloakBrowserTools.clickSelector(sessionId, selector)
            BrowserEngine.CAMOUFOX -> CamoufoxBrowserTools.clickSelector(sessionId, selector)
            BrowserEngine.WEBVIEW ->
                throw IllegalStateException("WEBVIEW 引擎请走原有 JS 点击路径")
        }

    /** 输入文本。 */
    fun typeInto(sessionId: String, engine: BrowserEngine, selector: String, text: String): String =
        when (engine) {
            BrowserEngine.CLOAK -> CloakBrowserTools.typeInto(sessionId, selector, text)
            BrowserEngine.CAMOUFOX -> CamoufoxBrowserTools.typeInto(sessionId, selector, text)
            BrowserEngine.WEBVIEW ->
                throw IllegalStateException("WEBVIEW 引擎请走原有 JS 输入路径")
        }

    /** 页面文本。 */
    fun pageText(sessionId: String, engine: BrowserEngine, maxChars: Int = 8000): String =
        when (engine) {
            BrowserEngine.CLOAK -> CloakBrowserTools.text(sessionId, maxChars)
            BrowserEngine.CAMOUFOX -> CamoufoxBrowserTools.text(sessionId, maxChars)
            BrowserEngine.WEBVIEW ->
                throw IllegalStateException("WEBVIEW 引擎请走原有 innerText 路径")
        }

    /** 设置指纹（仅 CLOAK 生效）。 */
    fun applyFingerprint(sessionId: String, config: CloakFingerprint): String {
        CloakBrowserTools.setFingerprint(sessionId, config)
        return "applied"
    }

    /** 引擎状态。 */
    fun status(): String = CloakBrowserTools.status()

    /** 引擎状态 + 环境自检。 */
    fun statusWithEnv(context: Context?): String = CloakBrowserTools.statusWithEnv(context)

    // ---------------------------------------------------------------- 内部

    private fun currentFingerprint(sessionId: String): CloakFingerprint =
        CloakBrowserTools.fingerprintOf(sessionId) ?: CloakFingerprint(profileId = sessionId)

    /** Camoufox 的指纹，缺省按 sessionId 生成一个 Windows persona。 */
    private fun currentCamoufoxFingerprint(sessionId: String): CamoufoxFingerprint =
        CamoufoxBrowserTools.fingerprintOf(sessionId)
            ?: CamoufoxFingerprint(profileId = sessionId)
}

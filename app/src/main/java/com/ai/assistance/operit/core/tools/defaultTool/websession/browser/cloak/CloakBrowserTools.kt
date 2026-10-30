package com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话级 CloakBrowser 工具门面。
 *
 * 支持多会话：每个 sessionId 独立持有 CDP 客户端与指纹配置，
 * 与 v1 原有的多标签浏览语义保持一致。
 */
internal object CloakBrowserTools {
    private const val TAG = "CloakTools"

    private class SessionState(
        @Volatile var client: CloakCdpClient? = null,
        @Volatile var fingerprint: CloakFingerprint = CloakFingerprint()
    )

    private val sessions = ConcurrentHashMap<String, SessionState>()

    private fun stateOf(sessionId: String): SessionState =
        sessions.getOrPut(sessionId) { SessionState() }

    /** 绑定已有 CDP 客户端到会话。 */
    fun attach(sessionId: String, client: CloakCdpClient) {
        stateOf(sessionId).client = client
        AppLogger.i(TAG, "会话 $sessionId 已绑定 CloakBrowser")
    }

    /** 解绑会话。 */
    fun detach(sessionId: String) {
        val st = sessions.remove(sessionId) ?: return
        runCatching { st.client?.close() }
        AppLogger.i(TAG, "会话 $sessionId 已解绑")
    }

    /** 读取会话指纹。 */
    fun fingerprintOf(sessionId: String): CloakFingerprint? =
        sessions[sessionId]?.fingerprint

    /** 启动并绑定。 */
    fun start(context: Context, sessionId: String, config: CloakFingerprint): String {
        val st = stateOf(sessionId)
        st.fingerprint = config
        val c = CloakLauncher.launch(context, config)
        st.client = c
        return JSONObject().apply {
            put("engine", "cloak")
            put("session", sessionId)
            put("port", CloakLauncher.status().port)
            put("pid", CloakLauncher.status().pid)
            put("version", CloakLauncher.status().version)
            put("profile", config.profileId)
        }.toString()
    }

    /** 导航。 */
    fun navigate(sessionId: String, url: String, timeoutMs: Int = 30_000): String {
        val c = requireClient(sessionId)
        c.navigate(url, timeoutMs)
        return JSONObject().apply {
            put("url", c.evaluate("location.href")?.toString() ?: url)
            put("title", c.evaluate("document.title")?.toString() ?: "")
            put("readyState", c.evaluate("document.readyState")?.toString() ?: "")
        }.toString()
    }

    /** 执行 JS。 */
    fun eval(sessionId: String, script: String): String {
        val v = requireClient(sessionId).evaluate(script)
        return v?.toString() ?: "null"
    }

    /** 截图，返回 PNG 路径。 */
    fun screenshot(sessionId: String, context: Context): String {
        val bytes = requireClient(sessionId).screenshot()
        val dir = java.io.File(context.cacheDir, "cloak_shots").apply { mkdirs() }
        val f = java.io.File(dir, "shot_${sessionId}_${System.currentTimeMillis()}.png")
        f.writeBytes(bytes)
        return f.absolutePath
    }

    /** 按选择器点击。 */
    fun clickSelector(sessionId: String, selector: String): String {
        val c = requireClient(sessionId)
        val box = c.evaluate(
            """
            (function(){
              var el = document.querySelector(${JSONObject.quote(selector)});
              if (!el) return null;
              var r = el.getBoundingClientRect();
              return {x: r.left + r.width/2, y: r.top + r.height/2};
            })()
            """.trimIndent()
        ) as? JSONObject ?: throw IllegalStateException("选择器无匹配: $selector")
        c.click(box.optDouble("x"), box.optDouble("y"))
        return JSONObject().put("clicked", selector).toString()
    }

    /** 按坐标点击。 */
    fun clickAt(sessionId: String, x: Double, y: Double): String {
        requireClient(sessionId).click(x, y)
        return JSONObject().put("clicked", "$x,$y").toString()
    }

    /** 输入文本。 */
    fun typeInto(sessionId: String, selector: String, text: String): String {
        val c = requireClient(sessionId)
        c.evaluate(
            "(function(){var el=document.querySelector(${JSONObject.quote(selector)});if(!el)return false;el.focus();return true;})()"
        )
        c.typeText(text)
        return JSONObject().put("typed", text).put("into", selector).toString()
    }

    /** 回车。 */
    fun pressEnter(sessionId: String): String {
        requireClient(sessionId).pressKey("Enter", "Enter", 13)
        return "{\"key\":\"Enter\"}"
    }

    /** 滚动。 */
    fun scroll(sessionId: String, deltaY: Double): String {
        requireClient(sessionId).scroll(deltaY)
        return JSONObject().put("scrolled", deltaY).toString()
    }

    /** 页面文本。 */
    fun text(sessionId: String, maxChars: Int = 8000): String {
        val t = requireClient(sessionId)
            .evaluate("document.body ? document.body.innerText : ''")?.toString() ?: ""
        return if (t.length > maxChars) t.substring(0, maxChars) else t
    }

    /** 页面 HTML。 */
    fun html(sessionId: String, maxChars: Int = 20000): String {
        val t = requireClient(sessionId)
            .evaluate("document.documentElement.outerHTML")?.toString() ?: ""
        return if (t.length > maxChars) t.substring(0, maxChars) else t
    }

    /** 设置指纹。 */
    fun setFingerprint(sessionId: String, config: CloakFingerprint): String {
        val st = stateOf(sessionId)
        st.fingerprint = config
        val c = st.client ?: return "{\"applied\":false,\"reason\":\"not started\"}"
        c.setUserAgent(config.userAgent, config.platform)
        c.setTimezone(config.timezone)
        c.setDeviceMetrics(config.viewportWidth, config.viewportHeight, config.deviceScale, config.mobile)
        if (config.latitude != null && config.longitude != null) {
            c.setGeolocation(config.latitude, config.longitude)
        }
        return JSONObject().apply {
            put("applied", true)
            put("platform", config.platform)
            put("timezone", config.timezone)
        }.toString()
    }

    /** 引擎状态。 */
    fun status(): String {
        val s = CloakLauncher.status()
        return JSONObject().apply {
            put("running", s.running)
            put("port", s.port)
            put("pid", s.pid)
            put("profile", s.profileId)
            put("version", s.version)
            put("error", s.lastError ?: "")
            put("sessions", sessions.size)
        }.toString()
    }

    /** 引擎状态 + 环境自检（chrome/xvfb/headless），用于 switch_engine 诊断。 */
    fun statusWithEnv(context: Context?): String {
        val s = CloakLauncher.status()
        return JSONObject().apply {
            put("running", s.running)
            put("port", s.port)
            put("pid", s.pid)
            put("profile", s.profileId)
            put("version", s.version)
            put("error", s.lastError ?: "")
            put("sessions", sessions.size)
            if (context != null) {
                put("environment", CloakLauncher.environmentSummary(context))
            }
        }.toString()
    }

    /** 关闭全部。 */
    fun stopAll(context: Context? = null): String {
        sessions.keys.toList().forEach { detach(it) }
        if (context != null) CloakLauncher.shutdown(context, stopXvfb = false)
        else CloakLauncher.shutdown()
        return "{\"stopped\":true}"
    }

    private fun requireClient(sessionId: String): CloakCdpClient =
        sessions[sessionId]?.client
            ?: throw IllegalStateException("会话 $sessionId 未启动 CloakBrowser 引擎")

    /** 暴露会话 CDP 客户端，供 drag / 底层事件使用；无连接返回 null。 */
    fun clientOf(sessionId: String): CloakCdpClient? = sessions[sessionId]?.client
}

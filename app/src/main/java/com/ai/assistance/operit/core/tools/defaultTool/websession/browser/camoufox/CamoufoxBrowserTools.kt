package com.ai.assistance.operit.core.tools.defaultTool.websession.browser.camoufox

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Camoufox 引擎的工具门面，与 CloakBrowserTools 同构。
 *
 * 上层（BrowserEngineBridge / JsTools / 工具注册）只面对这些方法，
 * 不关心底层是 CDP 还是 BiDi。
 *
 * 会话隔离：每个 sessionId 持有一条独立 BiDi 连接与指纹。
 */
internal object CamoufoxBrowserTools {

    private const val TAG = "CamoufoxTools"

    private val clients = ConcurrentHashMap<String, CamoufoxBidiClient>()
    private val fingerprints = ConcurrentHashMap<String, CamoufoxFingerprint>()

    // ------------------------------------------------------------ 生命周期

    /**
     * 附着到一个会话：确保引擎在跑并建立 BiDi 会话。
     *
     * @return 实际的 BiDi 端口，失败返回 0。
     */
    fun attach(context: Context, sessionId: String, fp: CamoufoxFingerprint? = null): Int {
        val fingerprint = fp ?: fingerprints[sessionId] ?: CamoufoxFingerprint(profileId = sessionId)
        fingerprints[sessionId] = fingerprint

        val port = CamoufoxLauncher.launch(context, fingerprint)
        if (port <= 0) {
            AppLogger.e(TAG, "attach 失败：引擎未启动 (${CamoufoxLauncher.status().lastError})")
            return 0
        }

        val c = CamoufoxBidiClient(port = port)
        if (!c.connect()) {
            AppLogger.e(TAG, "attach 失败：BiDi 连接不上")
            return 0
        }
        if (!c.startSession()) {
            AppLogger.e(TAG, "attach 失败：session.new 失败")
            return 0
        }
        clients[sessionId]?.close()
        clients[sessionId] = c
        AppLogger.i(TAG, "attach 成功 session=$sessionId port=$port")
        return port
    }

    /** 解绑并释放。 */
    fun detach(sessionId: String) {
        clients.remove(sessionId)?.close()
    }

    private fun clientOf(sessionId: String): CamoufoxBidiClient? = clients[sessionId]

    /**
     * 该会话是否已有可用客户端。
     *
     * 供 StandardBrowserSessionTools.cloakReady 探测用 —— 那边跨包调用，
     * 拿不到 private 的 clientOf，只能暴露一个布尔。
     */
    fun hasClient(sessionId: String): Boolean {
        val c = clients[sessionId] ?: return false
        return c.isConnected()
    }

    // ------------------------------------------------------------ 操作

    fun navigate(sessionId: String, url: String): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        return if (c.navigate(url)) "ok:$url" else "error:" + (c.lastErrorText() ?: "navigate 失败")
    }

    fun eval(sessionId: String, script: String): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        return c.evaluate(script) ?: ("error:" + (c.lastErrorText() ?: "evaluate 失败"))
    }

    /** 截图并落盘，返回文件路径。 */
    fun screenshot(sessionId: String, context: Context): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        val b64 = c.screenshot() ?: return "error:" + (c.lastErrorText() ?: "截图失败")
        return try {
            val dir = File(context.cacheDir, "camoufox_shots").apply { if (!exists()) mkdirs() }
            val f = File(dir, "shot_${System.currentTimeMillis()}.png")
            f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            f.absolutePath
        } catch (e: Throwable) {
            AppLogger.e(TAG, "截图落盘失败", e)
            "error:" + (e.message ?: "写入失败")
        }
    }

    fun text(sessionId: String, maxChars: Int = 8000): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        return c.pageText(maxChars)
    }

    /**
     * 按选择器点击。
     *
     * 用 BiDi 的 script.evaluate 在页面里触发 element.click()。
     * 相比坐标点击，不受 DPR 与滚动影响，也不需要模拟触摸。
     */
    fun clickSelector(sessionId: String, selector: String): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        val js = """
            const el = document.querySelector(${jsStr(selector)});
            if (!el) return 'NOTFOUND';
            el.scrollIntoView({block:'center'});
            el.click();
            return 'OK';
        """.trimIndent()
        val r = c.evaluate(js) ?: return "error:" + (c.lastErrorText() ?: "点击失败")
        return if (r == "OK") "clicked:$selector" else "notfound:$selector"
    }

    /** 向输入框填文本。先聚焦再赋值，触发 input/change 事件。 */
    fun typeInto(sessionId: String, selector: String, value: String): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        val js = """
            const el = document.querySelector(${jsStr(selector)});
            if (!el) return 'NOTFOUND';
            el.focus();
            const proto = el instanceof HTMLTextAreaElement
              ? HTMLTextAreaElement.prototype
              : HTMLInputElement.prototype;
            const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
            setter.call(el, ${jsStr(value)});
            el.dispatchEvent(new Event('input', {bubbles:true}));
            el.dispatchEvent(new Event('change', {bubbles:true}));
            return 'OK';
        """.trimIndent()
        val r = c.evaluate(js) ?: return "error:" + (c.lastErrorText() ?: "输入失败")
        return if (r == "OK") "typed:$selector" else "notfound:$selector"
    }

    /**
     * 点击页面坐标。
     *
     * Camoufox 没有 Chromium 的 Input.dispatchMouseEvent，
     * 这里用 elementFromPoint + click 实现等效语义。
     */
    fun clickPoint(sessionId: String, x: Int, y: Int): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        val js = """
            const el = document.elementFromPoint($x, $y);
            if (!el) return 'NOTFOUND';
            el.click();
            return 'OK';
        """.trimIndent()
        val r = c.evaluate(js) ?: return "error:" + (c.lastErrorText() ?: "点击失败")
        return if (r == "OK") "clicked:($x,$y)" else "notfound:($x,$y)"
    }

    /** 滚动页面。 */
    fun scroll(sessionId: String, deltaY: Int): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        val r = c.evaluate("window.scrollBy(0, $deltaY); return String(window.scrollY);")
        return "scrollY=" + (r ?: "?")
    }

    /** 历史后退。 */
    fun navigateBack(sessionId: String): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        return c.historyBack()
    }

    /** 悬停。 */
    fun hover(sessionId: String, selector: String): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        return c.hover(selector)
    }

    /** 拖拽。 */
    fun drag(sessionId: String, from: String, to: String): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        return c.drag(from, to)
    }

    /** 按键。 */
    fun pressKey(sessionId: String, key: String): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        return c.pressKey(key)
    }

    /** 下拉选择。 */
    fun selectOption(sessionId: String, selector: String, value: String): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        return c.selectOption(selector, value)
    }

    /** 批量填表。fieldsJson 形如 [{"selector":"#a","value":"b"}]。 */
    fun fillForm(sessionId: String, fieldsJson: String): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        return c.fillForm(fieldsJson)
    }

    /** 设置视口。 */
    fun resize(sessionId: String, width: Int, height: Int): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        return if (c.resize(width, height)) "resized:${width}x${height}"
        else "error:" + (c.lastErrorText() ?: "resize 失败")
    }

    /** 文件上传（构造 File 塞进 input）。 */
    fun fileUpload(sessionId: String, selector: String, fileName: String, content: String): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        return c.fileUpload(selector, fileName, content)
    }

    /** 弹窗处理。 */
    fun handleDialog(sessionId: String, accept: Boolean, promptText: String = ""): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        return c.handleDialog(accept, promptText)
    }

    /** 控制台消息（从事件缓冲取）。 */
    fun consoleMessages(sessionId: String, max: Int = 60): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        c.subscribeAll()
        val evs = c.drainEvents("log.entryAdded", max)
        if (evs.isEmpty()) return "[]"
        val sb = StringBuilder("[")
        evs.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            val p = e.optJSONObject("params")?.optJSONObject("entry")
            sb.append(org.json.JSONObject().apply {
                put("level", p?.optString("level", "") ?: "")
                put("text", p?.optString("text", "") ?: "")
                put("timestamp", p?.optLong("timestamp", 0L) ?: 0L)
            }.toString())
        }
        sb.append(']')
        return sb.toString()
    }

    /** 网络请求（从事件缓冲取）。 */
    fun networkRequests(sessionId: String, max: Int = 60): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        c.subscribeAll()
        val reqs = c.drainEvents("network.beforeRequestSent", max)
        val resps = c.drainEvents("network.responseCompleted", max)
        val sb = StringBuilder("[")
        var first = true
        for (e in reqs) {
            val p = e.optJSONObject("params") ?: continue
            val req = p.optJSONObject("request") ?: continue
            if (!first) sb.append(',')
            first = false
            sb.append(org.json.JSONObject().apply {
                put("url", req.optString("url", ""))
                put("method", req.optString("method", ""))
                put("phase", "request")
            }.toString())
        }
        for (e in resps) {
            val p = e.optJSONObject("params") ?: continue
            val resp = p.optJSONObject("response") ?: continue
            if (!first) sb.append(',')
            first = false
            sb.append(org.json.JSONObject().apply {
                put("url", resp.optString("url", ""))
                put("status", resp.optInt("status", 0))
                put("phase", "response")
            }.toString())
        }
        sb.append(']')
        return sb.toString()
    }

    /** 标签页列表。 */
    fun tabs(sessionId: String): String {
        val c = clientOf(sessionId) ?: return "error:未附着 camoufox 引擎"
        val ctx = c.ensureContext() ?: return "error:无可用标签"
        return org.json.JSONObject().apply {
            put("count", 1)
            put("active", ctx)
        }.toString()
    }

    // ------------------------------------------------------------ 指纹与状态

    fun setFingerprint(sessionId: String, fp: CamoufoxFingerprint) {
        fingerprints[sessionId] = fp
    }

    fun fingerprintOf(sessionId: String): CamoufoxFingerprint? = fingerprints[sessionId]

    fun status(): String {
        val st = CamoufoxLauncher.status()
        return buildString {
            append("engine=camoufox\n")
            append("running=").append(st.running).append('\n')
            append("bidiPort=").append(st.bidiPort).append('\n')
            append("marionettePort=").append(st.marionettePort).append('\n')
            append("pid=").append(st.pid).append('\n')
            append("version=").append(st.version).append('\n')
            append("sessions=").append(clients.size)
            if (!st.lastError.isNullOrBlank()) append("\nwarning=").append(st.lastError)
        }
    }

    fun statusWithEnv(context: Context?): String {
        val base = status()
        if (context == null) return base
        return base + "\nenvironment=" + CamoufoxLauncher.environmentSummary(context)
    }

    /** JSON 字符串字面量转义。 */
    private fun jsStr(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}

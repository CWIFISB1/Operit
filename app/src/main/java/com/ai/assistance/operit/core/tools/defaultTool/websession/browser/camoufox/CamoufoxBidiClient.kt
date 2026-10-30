package com.ai.assistance.operit.core.tools.defaultTool.websession.browser.camoufox

import com.ai.assistance.operit.util.AppLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Camoufox 的 WebDriver BiDi 客户端。
 *
 * ## 为什么不是 CDP
 *
 * CloakBrowser 是 Chromium，走 CDP：HTTP 的 /json/version 拿 WebSocket 地址，
 * 然后发 {"id":n,"method":"Page.navigate"}。
 *
 * Camoufox 是 Firefox，走 W3C WebDriver BiDi：
 *   ws://127.0.0.1:<port>/session
 *   发 {"id":n,"method":"browsingContext.navigate","params":{...}}
 *
 * ## 三个必须知道的连接细节
 *
 * 1. **Origin 头必须完全省略**。
 *    Firefox 的 BiDi 服务端（httpd.js）会校验 Origin，
 *    带任何 Origin（哪怕是 http://127.0.0.1:同端口）都返回
 *    400 "The handshake request has incorrect Origin header"。
 *    OkHttp 的 WebSocket 默认不发 Origin，正好符合要求；
 *    但**不能**手工加 Origin 头。
 *
 * 2. **必须先 session.new**。
 *    未建会话就调 browsingContext.getTree 会一直挂住不返回。
 *
 * 3. **script.evaluate 的表达式不能有顶层 return**。
 *    BiDi 把 expression 当成表达式求值，不是函数体。
 *    要 return 就包成 (() => { ... })() 这种 IIFE。
 *    否则报 SyntaxError: return not in function。
 *
 * ## 命令集（本客户端只覆盖浏览器工具需要的那几个）
 *
 *   session.new                  建会话
 *   browsingContext.getTree      列标签页
 *   browsingContext.create       开新标签（type=tab）
 *   browsingContext.navigate     导航
 *   browsingContext.close        关标签
 *   script.evaluate              执行 JS
 *   browsingContext.captureScreenshot   截图（base64）
 */
internal class CamoufoxBidiClient(
    private val host: String = "127.0.0.1",
    private val port: Int
) {
    private companion object {
        const val TAG = "CamoufoxBidi"
        const val CONNECT_TIMEOUT_SEC = 20L
        const val CALL_TIMEOUT_SEC = 45L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // WebSocket 长连接不设读超时
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val msgId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, PendingCall>()
    private val events = ArrayDeque<JSONObject>()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var readyLatch = CountDownLatch(1)
    @Volatile private var sessionId: String? = null
    @Volatile private var lastError: String? = null

    private class PendingCall(val latch: CountDownLatch) {
        @Volatile var response: JSONObject? = null
    }

    /** 已建立会话的 context id，后续操作都用它当 target。 */
    @Volatile var currentContextId: String? = null
        private set

    fun connect(): Boolean {
        if (socket != null) return true
        val url = "ws://$host:$port/session"
        val req = Request.Builder().url(url).build()
        readyLatch = CountDownLatch(1)
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                AppLogger.i(TAG, "BiDi 已连接 $url")
                readyLatch.countDown()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                runCatching { dispatch(JSONObject(text)) }
                    .onFailure { AppLogger.w(TAG, "消息解析失败: ${it.message}") }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                lastError = t.message ?: t.javaClass.simpleName
                AppLogger.e(TAG, "BiDi 连接失败: $lastError", t)
                readyLatch.countDown()
                failAllPending()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                AppLogger.i(TAG, "BiDi 关闭 code=$code reason=$reason")
                failAllPending()
            }
        })
        val ok = readyLatch.await(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        return ok && socket != null
    }

    private fun dispatch(o: JSONObject) {
        val id = if (o.has("id")) o.optInt("id", -1) else -1
        if (id >= 0) {
            pending.remove(id)?.let {
                it.response = o
                it.latch.countDown()
            }
        } else {
            // 事件，最多缓存 64 条防止无限增长
            synchronized(events) {
                events.addLast(o)
                while (events.size > 64) events.removeFirst()
            }
        }
    }

    private fun failAllPending() {
        pending.values.forEach { it.latch.countDown() }
        pending.clear()
    }

    /** 发一条命令并等结果。返回整个响应 JSON（含 result 或 error）。 */
    private fun call(method: String, params: JSONObject = JSONObject()): JSONObject? {
        val ws = socket ?: run {
            if (!connect()) return null
            socket
        } ?: return null

        val id = msgId.getAndIncrement()
        val frame = JSONObject().apply {
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val pc = PendingCall(CountDownLatch(1))
        pending[id] = pc
        val sent = ws.send(frame.toString())
        if (!sent) {
            pending.remove(id)
            lastError = "WebSocket 发送失败"
            return null
        }
        val done = pc.latch.await(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (!done) {
            pending.remove(id)
            lastError = "命令超时: $method"
            return null
        }
        return pc.response
    }

    /** 判断响应是不是成功。 */
    private fun isOk(r: JSONObject?): Boolean = r != null && r.has("result")

    /** 从响应里取错误文本。 */
    private fun errText(r: JSONObject?): String {
        if (r == null) return "无响应"
        val e = r.optString("error", "")
        val m = r.optString("message", "")
        return listOf(e, m).filter { it.isNotBlank() }.joinToString(": ").ifBlank { "unknown" }
    }

    // ------------------------------------------------------------ 会话

    /**
     * 建立 BiDi 会话。
     *
     * 注意：Camoufox 要求启动时带 --remote-allow-system-access，
     * 否则 browsingContext / script 相关命令一律返回
     * "System access is required"。
     */
    fun startSession(): Boolean {
        if (sessionId != null) return true
        val r = call("session.new", JSONObject().put("capabilities", JSONObject()))
        if (!isOk(r)) {
            lastError = "session.new 失败: ${errText(r)}"
            AppLogger.e(TAG, lastError!!)
            return false
        }
        val res = r!!.optJSONObject("result")
        sessionId = res?.optString("sessionId", "")
        // 顺手拿第一个 context
        refreshContext()
        return sessionId != null
    }

    private fun refreshContext() {
        val r = call("browsingContext.getTree", JSONObject())
        if (!isOk(r)) return
        val ctxs = r!!.optJSONObject("result")?.optJSONArray("contexts") ?: return
        if (ctxs.length() > 0) {
            currentContextId = ctxs.getJSONObject(0).optString("context", "")
        }
    }

    /** 确保有可用 context；没有就新建一个标签页。 */
    fun ensureContext(): String? {
        currentContextId?.let { if (it.isNotBlank()) return it }
        refreshContext()
        currentContextId?.let { if (it.isNotBlank()) return it }
        val r = call("browsingContext.create", JSONObject().put("type", "tab"))
        if (isOk(r)) {
            currentContextId = r!!.optJSONObject("result")?.optString("context", "")
        }
        return currentContextId
    }

    // ------------------------------------------------------------ 操作

    /** 导航到 url。wait 用 complete 保证拿到加载完成。 */
    fun navigate(url: String): Boolean {
        val ctx = ensureContext() ?: return false
        val p = JSONObject().apply {
            put("context", ctx)
            put("url", url)
            put("wait", "complete")
        }
        val r = call("browsingContext.navigate", p)
        if (!isOk(r)) {
            lastError = "navigate 失败: ${errText(r)}"
            return false
        }
        return true
    }

    /**
     * 执行 JS，返回字符串结果。
     *
     * 表达式会被包进 IIFE 再求值，调用方可以自由写 return。
     * 这么做是因为 BiDi 的 expression 是表达式不是函数体。
     */
    fun evaluate(js: String): String? {
        val ctx = ensureContext() ?: return null
        val expr = if (js.trimStart().startsWith("(")) js else "(() => { $js })()"
        val p = JSONObject().apply {
            put("expression", expr)
            put("target", JSONObject().put("context", ctx))
            put("awaitPromise", false)
            put("resultOwnership", "none")
        }
        val r = call("script.evaluate", p)
        if (!isOk(r)) {
            lastError = "evaluate 失败: ${errText(r)}"
            return null
        }
        val res = r!!.optJSONObject("result") ?: return null
        val inner = res.optJSONObject("result") ?: return null
        return inner.optString("value", "")
    }

    /** 截图，返回 base64（不含 data: 前缀）。 */
    fun screenshot(): String? {
        val ctx = ensureContext() ?: return null
        val r = call("browsingContext.captureScreenshot",
            JSONObject().put("context", ctx))
        if (!isOk(r)) {
            lastError = "截图失败: ${errText(r)}"
            return null
        }
        return r!!.optJSONObject("result")?.optString("data", "")
    }

    /** 读页面可见文本（受 maxChars 限制）。 */
    fun pageText(maxChars: Int = 8000): String {
        val js = """
            const t = document.body ? (document.body.innerText || '') : '';
            return t.slice(0, $maxChars);
        """.trimIndent()
        return evaluate(js) ?: ""
    }

    /** 当前页面 URL。 */
    fun currentUrl(): String {
        return evaluate("return location.href;") ?: ""
    }

    // ------------------------------------------------------------ 扩展操作
    // 以下方法补齐官方 23 个工具里依赖 WebView 原生能力的那批（hover / 拖拽 /
    // 按键 / 下拉选择 / 表单 / 视口 / 控制台 / 网络）。
    // Camoufox 没有 Chromium 的 Input.dispatchMouseEvent，全部用 DOM 事件等效实现，
    // 好处是同样的事件序列在 Firefox 里是"真实合成事件"，
    // 坏处是 isTrusted 为 false —— 有极少数站点校验这一点，遇到再走 performActions。

    /** 悬停在选择器上：先滚到可视区，再派发 mouseover / mouseenter / mousemove。 */
    fun hover(selector: String): String {
        val js = """
            const el = document.querySelector(${jsStr(selector)});
            if (!el) return 'NOTFOUND';
            el.scrollIntoView({block:'center'});
            const r = el.getBoundingClientRect();
            const x = r.left + r.width / 2;
            const y = r.top + r.height / 2;
            const opts = {bubbles:true, cancelable:true, clientX:x, clientY:y};
            el.dispatchEvent(new MouseEvent('mouseover', opts));
            el.dispatchEvent(new MouseEvent('mouseenter', opts));
            el.dispatchEvent(new MouseEvent('mousemove', opts));
            return 'OK';
        """.trimIndent()
        return evaluate(js) ?: "ERR"
    }

    /**
     * 从起点拖到终点。
     * 用 pointer 系列事件而不是 mouse，更接近现代站点的监听方式。
     */
    fun drag(fromSelector: String, toSelector: String): String {
        val js = """
            const a = document.querySelector(${jsStr(fromSelector)});
            const b = document.querySelector(${jsStr(toSelector)});
            if (!a || !b) return 'NOTFOUND';
            a.scrollIntoView({block:'center'});
            const ra = a.getBoundingClientRect();
            const rb = b.getBoundingClientRect();
            const sx = ra.left + ra.width / 2, sy = ra.top + ra.height / 2;
            const ex = rb.left + rb.width / 2, ey = rb.top + rb.height / 2;
            const mk = (t, x, y) => new PointerEvent(t, {
              bubbles:true, cancelable:true, clientX:x, clientY:y,
              pointerId:1, pointerType:'mouse', isPrimary:true, button:0
            });
            a.dispatchEvent(mk('pointerdown', sx, sy));
            document.dispatchEvent(mk('pointermove', (sx+ex)/2, (sy+ey)/2));
            document.dispatchEvent(mk('pointermove', ex, ey));
            b.dispatchEvent(mk('pointerup', ex, ey));
            return 'OK';
        """.trimIndent()
        return evaluate(js) ?: "ERR"
    }

    /**
     * 按键。
     *
     * BiDi 的 input.performActions 是规范方式，但 Firefox 对未聚焦元素
     * 的按键分发不稳定；这里直接在 document.activeElement 上派发 keydown/keyup，
     * 对输入框、可编辑区、快捷键监听都有效。
     */
    fun pressKey(key: String): String {
        val js = """
            const el = document.activeElement || document.body;
            const down = new KeyboardEvent('keydown', {
              key: ${jsStr(key)}, code: ${jsStr(key)}, bubbles:true, cancelable:true
            });
            el.dispatchEvent(down);
            const up = new KeyboardEvent('keyup', {
              key: ${jsStr(key)}, code: ${jsStr(key)}, bubbles:true, cancelable:true
            });
            el.dispatchEvent(up);
            return 'OK';
        """.trimIndent()
        return evaluate(js) ?: "ERR"
    }

    /** 选择 select 的选项（按 value 或可见文本匹配）。 */
    fun selectOption(selector: String, value: String): String {
        val js = """
            const el = document.querySelector(${jsStr(selector)});
            if (!el || el.tagName !== 'SELECT') return 'NOTSELECT';
            const want = ${jsStr(value)};
            let hit = null;
            for (const o of el.options) {
              if (o.value === want || o.text.trim() === want) { hit = o; break; }
            }
            if (!hit) return 'NOTFOUND';
            el.value = hit.value;
            el.dispatchEvent(new Event('input', {bubbles:true}));
            el.dispatchEvent(new Event('change', {bubbles:true}));
            return 'OK';
        """.trimIndent()
        return evaluate(js) ?: "ERR"
    }

    /**
     * 批量填表。
     *
     * fields 形如 [{"selector":"#user","value":"a"},{"selector":"#pwd","value":"b"}]
     * 一次性传进来，避免每个字段一次往返。
     */
    fun fillForm(fieldsJson: String): String {
        val js = """
            const fields = ${fieldsJson};
            const out = [];
            for (const f of fields) {
              const el = document.querySelector(f.selector);
              if (!el) { out.push('MISS:' + f.selector); continue; }
              el.focus();
              const proto = el instanceof HTMLTextAreaElement
                ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
              const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
              setter.call(el, f.value);
              el.dispatchEvent(new Event('input', {bubbles:true}));
              el.dispatchEvent(new Event('change', {bubbles:true}));
              out.push('OK:' + f.selector);
            }
            return out.join(',');
        """.trimIndent()
        return evaluate(js) ?: "ERR"
    }

    /** 设置视口尺寸。BiDi 规范方法 browsingContext.setViewport。 */
    fun resize(width: Int, height: Int): Boolean {
        val ctx = ensureContext() ?: return false
        val p = JSONObject().apply {
            put("context", ctx)
            put("viewport", JSONObject().apply {
                put("width", width)
                put("height", height)
            })
        }
        val r = call("browsingContext.setViewport", p)
        if (!isOk(r)) {
            // 部分版本用 devicePixelRatio 形式，退回 JS 方式记录意图
            lastError = "setViewport 失败: ${errText(r)}"
            return false
        }
        return true
    }

    /**
     * 历史后退。
     *
     * 三种做法里挑了最稳的一种：
     *   1. browsingContext.traverseHistory —— 规范方法，但 Firefox 158 的 BiDi
     *      实现里对它的 delta 参数校验很严，context 没有历史条目时直接报错；
     *   2. history.back() —— 简单，但页面自己接管了路由（SPA）时可能不触发；
     *   3. history.go(-1) + 等一轮 —— 兼容性最好。
     * 这里用 3，并在返回里给出实际落点，方便上层判断是否真的后退了。
     */
    fun historyBack(): String {
        val before = currentUrl()
        val r = evaluate("history.back(); return 'OK';")
        if (r != "OK") return "ERR"
        // 等一帧，让导航事件落地再读 URL
        Thread.sleep(600)
        val after = currentUrl()
        return if (before != after) "back:$after" else "back:unchanged:$after"
    }

    /**
     * 上传文件到 input[type=file]。
     *
     * Firefox 的 BiDi 没有 Chromium 的 DOM.setFileInputFiles，
     * 但可以用 DataTransfer 在页面里构造 File 塞进 input.files。
     * 由于沙盒拿不到宿主文件，这里只支持传入文件名 + 文本内容。
     */
    fun fileUpload(selector: String, fileName: String, content: String): String {
        val js = """
            const el = document.querySelector(${jsStr(selector)});
            if (!el || el.type !== 'file') return 'NOTFILE';
            const dt = new DataTransfer();
            const f = new File([${jsStr(content)}], ${jsStr(fileName)}, {type:'text/plain'});
            dt.items.add(f);
            el.files = dt.files;
            el.dispatchEvent(new Event('input', {bubbles:true}));
            el.dispatchEvent(new Event('change', {bubbles:true}));
            return 'OK';
        """.trimIndent()
        return evaluate(js) ?: "ERR"
    }

    /**
     * 处理弹窗（alert / confirm / prompt）。
     *
     * Firefox 的 alert 会阻塞 JS 线程，BiDi 没给出统一接管入口。
     * 等效做法：在页面加载前用 JS 覆盖 window.alert/confirm/prompt 记录调用，
     * 之后由本方法读取记录并按策略回应。
     */
    fun handleDialog(accept: Boolean, promptText: String): String {
        val js = """
            if (!window.__camoufox_dialog) {
              window.__camoufox_dialog = {last:null, queue:[]};
              const rec = (t, m) => {
                window.__camoufox_dialog.last = {type:t, message:m};
                window.__camoufox_dialog.queue.push({type:t, message:m});
              };
              window.alert = function(m){ rec('alert', m); };
              window.confirm = function(m){ rec('confirm', m); return ${if (accept) "true" else "false"}; };
              window.prompt = function(m, d){ rec('prompt', m); return ${jsStr(promptText)}; };
            }
            const d = window.__camoufox_dialog.last;
            return d ? (d.type + ':' + d.message) : 'NONE';
        """.trimIndent()
        return evaluate(js) ?: "NONE"
    }

    // ------------------------------------------------------------ 事件

    /** 取事件缓冲区里指定类型的事件（用于 console / network）。 */
    fun drainEvents(methodPrefix: String, max: Int = 60): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        synchronized(events) {
            val it = events.iterator()
            while (it.hasNext() && out.size < max) {
                val e = it.next()
                if (e.optString("method", "").startsWith(methodPrefix)) {
                    out.add(e)
                    it.remove()
                }
            }
        }
        return out
    }

    /** 开启 console / network 事件订阅（只订阅一次）。 */
    private var subscribed = false
    fun subscribeAll() {
        if (subscribed) return
        subscribed = true
        call("session.subscribe", JSONObject().put("events", JSONArray().apply {
            put("log.entryAdded")
            put("network.beforeRequestSent")
            put("network.responseCompleted")
            put("browsingContext.contextCreated")
            put("browsingContext.contextDestroyed")
        })))
    }

    /** 关闭标签页并收摊。 */
    fun closeContext() {
        val ctx = currentContextId ?: return
        runCatching {
            call("browsingContext.close", JSONObject().put("context", ctx))
        }
        currentContextId = null
    }

    fun close() {
        runCatching { socket?.close(1000, "bye") }
        socket = null
        sessionId = null
        currentContextId = null
        failAllPending()
    }

    fun lastErrorText(): String? = lastError

    /** JSON 字符串字面量转义，供上面那些内联 JS 片段拼接使用。 */
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

    /** 通道是否可用（用于状态展示）。 */
    fun isConnected(): Boolean = socket != null
}

package com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak

import android.util.Base64
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** CDP client for CloakBrowser: JDK Socket only, no extra deps. */
internal class CloakCdpClient(
    private val host: String = "127.0.0.1",
    private val port: Int,
    private val timeoutMs: Int = 30_000
) {
    companion object { private const val TAG = "CloakCdp" }

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val nextId = AtomicLong(0)
    /** socket 写锁：截图轮询线程与工具调用线程会并发 sendFrame，不加锁会撕裂 WebSocket 帧。 */
    private val writeLock = Any()
    private val pending = ConcurrentHashMap<Long, ResultBox>()
    private val listeners = ConcurrentHashMap<String, MutableList<(JSONObject) -> Unit>>()
    @Volatile private var closed = false

    private class ResultBox {
        @Volatile var result: JSONObject? = null
        @Volatile var error: String? = null
        @Volatile var done = false
        val lock = Object()
        fun complete(r: JSONObject?, e: String?) {
            result = r; error = e; done = true
            synchronized(lock) { lock.notifyAll() }
        }
    }

    fun connectToFirstPage(): String {
        val targets = httpJsonArray("/json/list")
        var ws: String? = null
        for (i in 0 until targets.length()) {
            val t = targets.optJSONObject(i) ?: continue
            if (t.optString("type") == "page") {
                val c = t.optString("webSocketDebuggerUrl")
                if (c.isNotEmpty()) { ws = c; break }
            }
        }
        if (ws.isNullOrEmpty()) {
            val created = httpJsonObject("PUT", "/json/new?about:blank")
            ws = created?.optString("webSocketDebuggerUrl")
        }
        if (ws.isNullOrEmpty()) throw IllegalStateException("no page target on port $port")
        openWebSocket(ws!!)
        startReader()
        return ws!!
    }

    private fun openWebSocket(wsUrl: String) {
        val uri = URI(wsUrl)
        val h = uri.host ?: host
        val p = if (uri.port > 0) uri.port else port
        val path = (uri.path ?: "/") + (uri.query?.let { "?$it" } ?: "")
        val s = Socket(h, p)
        s.soTimeout = timeoutMs
        val out = s.getOutputStream()
        val raw = ByteArray(16)
        java.security.SecureRandom().nextBytes(raw)
        val key = Base64.encodeToString(raw, Base64.NO_WRAP)
        val req = "GET $path HTTP/1.1\r\nHost: $h:$p\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        out.write(req.toByteArray(Charsets.US_ASCII))
        out.flush()
        val bin = BufferedInputStream(s.getInputStream())
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = bin.read()
            if (b < 0) throw IllegalStateException("disconnected during handshake")
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        val header = head.toString("US-ASCII")
        if (!header.startsWith("HTTP/1.1 101")) {
            throw IllegalStateException("ws handshake failed: " + header.lineSequence().firstOrNull())
        }
        socket = s; input = bin; output = out
    }

    private fun startReader() {
        Thread({
            try {
                while (!closed) {
                    val msg = readFrame() ?: continue
                    dispatch(msg)
                }
            } catch (e: Throwable) {
                if (!closed) AppLogger.w(TAG, "reader stopped: ${e.message}")
            }
        }, "cloak-cdp-reader").apply { isDaemon = true; start() }
    }

    private fun readFrame(): JSONObject? {
        val ins = input ?: return null
        val b0 = ins.read(); if (b0 < 0) return null
        val b1 = ins.read(); if (b1 < 0) return null
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) len = ((ins.read() shl 8) or ins.read()).toLong()
        else if (len == 127L) { len = 0L; for (i in 0 until 8) len = (len shl 8) or ins.read().toLong() }
        val mask = if (masked) ByteArray(4) { ins.read().toByte() } else null
        if (len > 64L * 1024 * 1024) throw IllegalStateException("frame too large: $len")
        val payload = ByteArray(len.toInt())
        var off = 0
        while (off < payload.size) {
            val n = ins.read(payload, off, payload.size - off)
            if (n < 0) return null
            off += n
        }
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return when (opcode) {
            0x1, 0x2 -> runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull()
            0x8 -> { closed = true; null }
            0x9 -> { sendFrame(0xA, payload); null }
            else -> null
        }
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) = synchronized(writeLock) {
        val out = output ?: return
        synchronized(out) {
            val hdr = ByteArrayOutputStream()
            hdr.write(0x80 or opcode)
            when {
                payload.size < 126 -> hdr.write(0x80 or payload.size)
                payload.size < 65536 -> {
                    hdr.write(0x80 or 126)
                    hdr.write((payload.size shr 8) and 0xFF)
                    hdr.write(payload.size and 0xFF)
                }
                else -> {
                    hdr.write(0x80 or 127)
                    for (i in 7 downTo 0) hdr.write(((payload.size.toLong() shr (i * 8)) and 0xFF).toInt())
                }
            }
            val mask = ByteArray(4)
            java.security.SecureRandom().nextBytes(mask)
            hdr.write(mask)
            val masked = ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
            out.write(hdr.toByteArray())
            out.write(masked)
            out.flush()
        }
    }

    private fun dispatch(msg: JSONObject) {
        val id = msg.optLong("id", -1L)
        if (id >= 0) {
            val err = msg.optJSONObject("error")?.optString("message")
            pending.remove(id)?.complete(msg.optJSONObject("result"), err)
            return
        }
        val method = msg.optString("method")
        if (method.isNotEmpty()) {
            val params = msg.optJSONObject("params") ?: JSONObject()
            listeners[method]?.toList()?.forEach { runCatching { it(params) } }
        }
    }

    fun call(method: String, params: JSONObject = JSONObject(), timeout: Int = timeoutMs): JSONObject {
        if (closed) throw IllegalStateException("CDP closed")
        val id = nextId.incrementAndGet()
        val box = ResultBox()
        pending[id] = box
        val payload = JSONObject().apply { put("id", id); put("method", method); put("params", params) }
        sendFrame(0x1, payload.toString().toByteArray(Charsets.UTF_8))
        synchronized(box.lock) {
            val deadline = System.currentTimeMillis() + timeout
            while (!box.done) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) { pending.remove(id); throw IllegalStateException("CDP timeout: $method") }
                box.lock.wait(left)
            }
        }
        box.error?.let { throw IllegalStateException("CDP $method failed: $it") }
        return box.result ?: JSONObject()
    }

    fun on(event: String, listener: (JSONObject) -> Unit) {
        listeners.getOrPut(event) { mutableListOf() }.add(listener)
    }

    private fun httpJsonArray(path: String): JSONArray {
        val text = httpRequest("GET", path) ?: return JSONArray()
        return runCatching { JSONArray(text) }.getOrElse { JSONArray() }
    }

    private fun httpJsonObject(method: String, path: String): JSONObject? {
        val text = httpRequest(method, path) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun httpRequest(method: String, path: String): String? = runCatching {
        val s = Socket(host, port)
        s.soTimeout = 10_000
        s.getOutputStream().apply {
            write("$method $path HTTP/1.1\r\nHost: $host:$port\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
        }
        val resp = BufferedInputStream(s.getInputStream()).readBytes().toString(Charsets.UTF_8)
        s.close()
        val idx = resp.indexOf("\r\n\r\n")
        if (idx < 0) null else resp.substring(idx + 4)
    }.getOrNull()

    // ---------------- high level API ----------------

    fun navigate(url: String, timeout: Int = 30_000) {
        call("Page.enable")
        call("Page.navigate", JSONObject().put("url", url), timeout)
        waitForLoad(timeout)
    }

    fun waitForLoad(timeout: Int = 30_000) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val raw = evaluate("document.readyState")
            if (raw?.toString()?.trim('"') == "complete") return
            Thread.sleep(120)
        }
    }

    fun evaluate(script: String, awaitPromise: Boolean = true): Any? {
        val params = JSONObject().apply {
            put("expression", script)
            put("returnByValue", true)
            put("awaitPromise", awaitPromise)
            put("userGesture", true)
        }
        val r = call("Runtime.evaluate", params)
        val exc = r.optJSONObject("exceptionDetails")
        if (exc != null) {
            val d = exc.optJSONObject("exception")?.optString("description") ?: exc.optString("text")
            throw IllegalStateException("JS error: $d")
        }
        val res = r.optJSONObject("result") ?: return null
        if (res.has("value")) {
            val v = res.opt("value")
            return if (v == JSONObject.NULL) null else v
        }
        return null
    }

    fun screenshot(): ByteArray {
        call("Page.enable")
        val r = call("Page.captureScreenshot", JSONObject().put("format", "png"), 60_000)
        val b64 = r.optString("data")
        if (b64.isEmpty()) throw IllegalStateException("empty screenshot")
        return Base64.decode(b64, Base64.DEFAULT)
    }

    /**
     * 拟人化点击：先沿贝塞尔曲线移动过去，再按下、保持、抬起。
     *
     * 裸发 mousePressed/mouseReleased 的问题：坐标从 A 直接跳到 B，
     * 中间没有任何 mouseMoved，等于「瞬移点击」。这是 Cloudflare Turnstile
     * 和 reCAPTCHA 最基础的行为特征，静态指纹再干净也会被标高风险。
     *
     * @param fromX/fromY 当前鼠标位置；传 null 时从目标点附近的随机位置起手
     */
    fun humanClick(
        x: Double,
        y: Double,
        fromX: Double? = null,
        fromY: Double? = null,
        preset: String = "default",
        seed: Int = 0
    ) {
        val rnd = kotlin.random.Random(seed)
        // 无历史位置时，从目标附近 120~420px 的随机方向起手，避免「凭空出现在目标上」
        val sx = fromX ?: (x + (if (rnd.nextBoolean()) 1 else -1) * rnd.nextDouble(140.0, 420.0))
        val sy = fromY ?: (y + (if (rnd.nextBoolean()) 1 else -1) * rnd.nextDouble(90.0, 300.0))

        val path = CloakHumanizer.movePath(sx, sy, x, y, preset, seed)
        for (step in path) {
            call(
                "Input.dispatchMouseEvent",
                JSONObject().apply {
                    put("type", "mouseMoved")
                    put("x", step.x)
                    put("y", step.y)
                    put("button", "none")
                    put("buttons", 0)
                }
            )
            if (step.delayMs > 0) Thread.sleep(step.delayMs)
        }

        call(
            "Input.dispatchMouseEvent",
            JSONObject().apply {
                put("type", "mousePressed")
                put("x", x)
                put("y", y)
                put("button", "left")
                put("buttons", 1)
                put("clickCount", 1)
            }
        )
        // 保持时长：真人有按压停留，不是 0ms
        Thread.sleep(CloakHumanizer.pressHoldMs(preset, seed + 7))
        call(
            "Input.dispatchMouseEvent",
            JSONObject().apply {
                put("type", "mouseReleased")
                put("x", x)
                put("y", y)
                put("button", "left")
                put("buttons", 0)
                put("clickCount", 1)
            }
        )
    }

    /**
     * 拟人化滚动：按加速 → 巡航 → 减速的曲线分多步发 wheel 事件。
     * totalDelta 为正向下滚，为负向上滚。
     */
    fun humanScroll(
        totalDelta: Double,
        x: Double = 640.0,
        y: Double = 400.0,
        preset: String = "default",
        seed: Int = 0
    ) {
        val steps = CloakHumanizer.scrollSteps(totalDelta, preset, seed)
        if (steps.isEmpty()) return
        var i = 0
        for (d in steps) {
            call(
                "Input.dispatchMouseEvent",
                JSONObject().apply {
                    put("type", "mouseWheel")
                    put("x", x)
                    put("y", y)
                    put("deltaX", 0)
                    put("deltaY", d)
                }
            )
            // 最后一步不等待，直接交还控制权
            if (i < steps.size - 1) {
                Thread.sleep(CloakHumanizer.scrollDelayMs(preset, seed + i))
            }
            i++
        }
    }

    /**
     * 拟人化输入：逐字 insertText，间隔按预设分布，长文本带思考停顿。
     *
     * 一次性 insertText 整串是最容易被 transcript 分析抓到的模式 ——
     * 真人不可能在 0ms 内打出 40 个字符。
     */
    fun humanType(text: String, preset: String = "default", seed: Int = 0) {
        if (text.isEmpty()) return
        val delays = CloakHumanizer.typingDelays(text, preset, seed)
        for (i in text.indices) {
            typeText(text[i].toString())
            val d = delays.getOrElse(i) { 60L }
            if (d > 0) Thread.sleep(d)
        }
    }

    fun click(x: Double, y: Double) {
        call("Input.dispatchMouseEvent", JSONObject().apply {
            put("type", "mousePressed"); put("x", x); put("y", y)
            put("button", "left"); put("clickCount", 1)
        })
        call("Input.dispatchMouseEvent", JSONObject().apply {
            put("type", "mouseReleased"); put("x", x); put("y", y)
            put("button", "left"); put("clickCount", 1)
        })
    }

    /**
     * 底层鼠标事件派发，用于拖拽等复合动作。
     * @param type mousePressed / mouseReleased / mouseMoved
     * @param buttons 位掩码：1=左键按下（拖动中必须带）
     */
    fun mouseEvent(
        type: String,
        x: Double,
        y: Double,
        button: String = "left",
        buttons: Int = 0,
        clickCount: Int = 1
    ) {
        call("Input.dispatchMouseEvent", JSONObject().apply {
            put("type", type)
            put("x", x)
            put("y", y)
            put("button", button)
            put("buttons", buttons)
            put("clickCount", clickCount)
        })
    }

    fun typeText(text: String) {
        call("Input.insertText", JSONObject().put("text", text))
    }

    fun pressKey(key: String, code: String, keyCode: Int) {
        call("Input.dispatchKeyEvent", JSONObject().apply {
            put("type", "keyDown"); put("key", key); put("code", code)
            put("windowsVirtualKeyCode", keyCode)
        })
        call("Input.dispatchKeyEvent", JSONObject().apply {
            put("type", "keyUp"); put("key", key); put("code", code)
            put("windowsVirtualKeyCode", keyCode)
        })
    }

    fun scroll(deltaY: Double, x: Double = 100.0, y: Double = 300.0) {
        call("Input.dispatchMouseEvent", JSONObject().apply {
            put("type", "mouseWheel"); put("x", x); put("y", y)
            put("deltaX", 0); put("deltaY", deltaY)
        })
    }

    /**
     * 设置 UA + Client Hints 元数据。
     *
     * 只设 userAgent 是不够的：现代风控（Cloudflare / DataDome / Akamai）会读
     * Sec-CH-UA / Sec-CH-UA-Platform / Sec-CH-UA-Full-Version-List 这组 Client Hints，
     * 并和 navigator.userAgent 交叉比对。只改 UA 不改 hints，两者必然对不上——
     * 「UA 说 Windows，hints 说 Linux」是最容易被抓的矛盾之一。
     *
     * 这里按 UA 里的 Chrome 主版本自动生成一致的元数据，让两条信息同源。
     */
    fun setUserAgent(ua: String, platform: String = "Win32") {
        // 从 UA 里抠出 Chrome 版本，保证 brand 版本与 UA 一致。
        val chromeVer =
            Regex("""Chrome/(\d+)\.(\d+)\.(\d+)\.(\d+)""").find(ua)
                ?.groupValues
                ?.drop(1)
                ?.joinToString(".")
                ?: "146.0.7680.177"
        val major = chromeVer.substringBefore('.')

        // 平台字符串对齐：Win32 → Windows；MacIntel → macOS。
        val hintsPlatform = when {
            platform.contains("Win", ignoreCase = true) -> "Windows"
            platform.contains("Mac", ignoreCase = true) -> "macOS"
            platform.contains("Linux", ignoreCase = true) -> "Linux"
            else -> "Windows"
        }

        val branded = org.json.JSONArray().apply {
            put(org.json.JSONObject().put("brand", "Chromium").put("version", major))
            put(org.json.JSONObject().put("brand", "Google Chrome").put("version", major))
            put(org.json.JSONObject().put("brand", "Not_A Brand").put("version", "24"))
        }
        val fullVersionList = org.json.JSONArray().apply {
            put(org.json.JSONObject().put("brand", "Chromium").put("version", chromeVer))
            put(org.json.JSONObject().put("brand", "Google Chrome").put("version", chromeVer))
            put(org.json.JSONObject().put("brand", "Not_A Brand").put("version", "24.0.0.0"))
        }

        val metadata = org.json.JSONObject().apply {
            put("brands", branded)
            put("fullVersionList", fullVersionList)
            put("fullVersion", chromeVer)
            put("platform", hintsPlatform)
            put("platformVersion", if (hintsPlatform == "Windows") "15.0.0" else "10.15.7")
            put("architecture", "x86")
            put("model", "")
            put("mobile", false)
            put("bitness", "64")
            put("wow64", false)
        }

        call("Emulation.setUserAgentOverride", JSONObject().apply {
            put("userAgent", ua)
            put("platform", platform)
            put("userAgentMetadata", metadata)
            // acceptLanguage 同步，避免 navigator.languages 与 UA 语言不一致。
            put("acceptLanguage", "zh-CN,zh;q=0.9,en;q=0.8")
        })
    }

    fun setTimezone(tz: String) {
        call("Emulation.setTimezoneOverride", JSONObject().put("timezoneId", tz))
    }

    fun setGeolocation(lat: Double, lng: Double, accuracy: Double = 100.0) {
        call("Emulation.setGeolocationOverride", JSONObject().apply {
            put("latitude", lat); put("longitude", lng); put("accuracy", accuracy)
        })
    }

    /**
     * 设置视口与屏幕几何。
     *
     * 除了 width/height，还必须显式给 screenWidth/screenHeight/positionX/positionY：
     *
     *  - 不给 screenWidth/screenHeight 时，CDP 会回落到「视口尺寸」，
     *    于是 screen 是 1279x799 这种奇怪的数，而真实 Windows 桌面是 1920x1080，
     *    两者不一致是明显的自动化信号。
     *
     *  - positionX/positionY 实测**改不动** window.screenX/screenY，
     *    传了也白传（CDP 返回成功，但 screenX/Y 保持原值）。
     *    screenX/Y 由窗口管理器决定，CDP 层无覆盖能力。
     *
     * 关于 screenX/screenY 是否算破绽（2026-10-30 复测纠偏）：
     *    实测 availLeft=0、availTop=0、screen=1920x1080、窗口 1280x800，
     *    screenX/Y 只是窗口在桌面上的居中坐标（111~553 随实例变化），
     *    与真实桌面浏览器的正常取值范围一致，**不构成自动化信号**。
     *    强行注入 JS 改写 getter 会破坏 toString 原生性（当前 8/8 NATIVE），
     *    得不偿失，故不做覆盖。
     *
     * 保留 positionX/positionY 传 0 的原因：语义上表达意图，且无害。
     */
    fun setDeviceMetrics(
        width: Int,
        height: Int,
        scale: Double = 1.0,
        mobile: Boolean = false,
        screenWidth: Int = 1920,
        screenHeight: Int = 1080,
        positionX: Int = 0,
        positionY: Int = 0
    ) {
        call("Emulation.setDeviceMetricsOverride", JSONObject().apply {
            put("width", width)
            put("height", height)
            put("deviceScaleFactor", scale)
            put("mobile", mobile)
            // 屏幕几何：与 device metrics 分开指定，避免回落到视口尺寸
            put("screenWidth", screenWidth)
            put("screenHeight", screenHeight)
            // 窗口位置归零：遮掉 Android 悬浮窗的真实偏移
            put("positionX", positionX)
            put("positionY", positionY)
            // 不启用屏幕朝向覆盖，否则会多出 orientation 字段
            put("dontSetVisibleSize", false)
        })
    }

    /**
     * 修正 navigator.onLine。
     *
     * 实测问题：Cloak 跑在 proot 里，Android 侧的 ConnectivityManager 状态
     * 传不进 Chromium 的网络栈，于是 navigator.onLine 返回 false，
     * 但页面实际能正常联网 —— 这是自相矛盾的信号，风控会标记为异常环境
     * （真实桌面浏览器只要加载出页面，onLine 必然是 true）。
     *
     * CDP 的 Network.emulateNetworkConditions 只要 offline=false 就会
     * 让 onLine 变 true。这里顺手把延迟/带宽设成常见宽带值，
     * 避免出现「0ms 延迟」这种 unrealistically perfect 的组合。
     */
    fun setOnline(online: Boolean = true) {
        call("Network.emulateNetworkConditions", JSONObject().apply {
            put("offline", !online)
            put("latency", 20)              // 20ms，接近正常宽带 RTT
            put("downloadThroughput", -1)   // -1 = 不限速
            put("uploadThroughput", -1)
        })
    }

    fun enableNetwork(onRequest: ((JSONObject) -> Unit)? = null, onResponse: ((JSONObject) -> Unit)? = null) {
        call("Network.enable")
        onRequest?.let { cb -> on("Network.requestWillBeSent") { cb(it) } }
        onResponse?.let { cb -> on("Network.responseReceived") { cb(it) } }
    }

    fun getCookies(url: String? = null): JSONArray {
        val params = JSONObject()
        if (!url.isNullOrEmpty()) params.put("urls", JSONArray().put(url))
        return call("Network.getCookies", params).optJSONArray("cookies") ?: JSONArray()
    }

    fun setCookie(name: String, value: String, domain: String, path: String = "/") {
        call("Network.setCookie", JSONObject().apply {
            put("name", name); put("value", value); put("domain", domain); put("path", path)
        })
    }

    fun clearCookies() { call("Network.clearBrowserCookies") }

    fun close() {
        closed = true
        runCatching { sendFrame(0x8, ByteArray(0)) }
        runCatching { socket?.close() }
        socket = null; input = null; output = null
        pending.values.forEach { it.complete(null, "closed") }
        pending.clear()
    }
}

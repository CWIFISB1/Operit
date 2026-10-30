package com.ai.assistance.operit.core.tools.defaultTool.websession.browser.camoufox

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 在 proot 内启动 Camoufox 并拿到 BiDi 端口。
 *
 * ## 启动参数里的三个必须项
 *
 * ```
 * --marionette                 开 Marionette（2828）
 * --remote-debugging-port=N    开 WebDriver BiDi（WebSocket）
 * --remote-allow-system-access 没这个，browsingContext / script 全部拒绝
 * ```
 *
 * 少了第三条会得到：
 *   "System access is required. Start Camoufox with -remote-allow-system-access"
 *
 * ## 显示器
 *
 * Firefox 需要 X display。proot 里用 Xvfb 起虚拟屏，
 * 默认 :99，1920x1080x24。
 *
 * ## 二进制查找顺序
 *
 * 1. 显式配置路径（调用方传入）
 * 2. ~/.camoufox/camoufox-bin
 * 3. /root/camoufox/camoufox-bin
 * 4. PATH 里的 camoufox-bin
 *
 * 找不到就明确返回失败原因，由上层降级到 WebView。
 */
internal object CamoufoxLauncher {

    private const val TAG = "CamoufoxLauncher"
    private const val DEFAULT_BIDI_PORT = 9388
    private const val DEFAULT_DISPLAY = ":99"
    private const val XRES = "1920x1080x24"

    /** 防止重复拉起。 */
    @Volatile private var launched = false
    @Volatile private var runningPort = 0
    @Volatile private var runningPid = -1
    @Volatile private var lastError: String? = null

    /**
     * 确保 camoufox 在跑。
     *
     * @return 可用的 BiDi 端口，失败返回 0。
     */
    fun launch(context: Context, fp: CamoufoxFingerprint, binaryPath: String? = null): Int {
        if (launched && runningPort > 0 && isPortAlive(runningPort)) {
            AppLogger.i(TAG, "复用已运行的 camoufox，端口 $runningPort")
            return runningPort
        }

        val bin = resolveBinary(binaryPath)
        if (bin == null) {
            lastError = "找不到 camoufox 二进制（已查 ~/.camoufox、/root/camoufox、PATH）"
            AppLogger.e(TAG, lastError!!)
            return 0
        }

        ensureDisplay()

        val env = CamoufoxConfigBuilder.buildEnv(fp)
        val profileDir = File(context.cacheDir, "camoufox_profile_${fp.profileId}").apply {
            if (!exists()) mkdirs()
        }

        val port = pickPort()
        val cmd = buildCommand(bin, port, profileDir.absolutePath, fp)

        AppLogger.i(TAG, "启动 camoufox: $cmd")
        val ok = runInProot(cmd, env)
        if (!ok) {
            lastError = "proot 启动失败"
            return 0
        }

        // 等 BiDi 端口就绪，最多 40 秒（Firefox 首启比 Chromium 慢）
        val ready = waitPort(port, 40_000)
        if (!ready) {
            lastError = "BiDi 端口 $port 40 秒内未就绪"
            AppLogger.e(TAG, lastError!!)
            return 0
        }

        runningPort = port
        runningPid = findPid(bin)
        launched = true
        lastError = null
        AppLogger.i(TAG, "camoufox 就绪 port=$port pid=$runningPid")
        return port
    }

    /** 拼启动命令。 */
    private fun buildCommand(
        bin: String,
        port: Int,
        profileDir: String,
        fp: CamoufoxFingerprint
    ): String {
        val sb = StringBuilder()
        sb.append("DISPLAY=$DEFAULT_DISPLAY ")
        sb.append(bin)
        sb.append(" --marionette")
        sb.append(" --remote-debugging-port=").append(port)
        sb.append(" --remote-allow-system-access")
        sb.append(" --no-remote")
        sb.append(" --profile ").append(profileDir)
        if (fp.humanize) {
            // Camoufox 的拟人化通过 profile prefs 生效，这里只记录意图
            sb.append(" --allow-downgrade")
        }
        fp.proxyServer?.let { if (it.isNotBlank()) sb.append(" ").append(shellQuote("--proxy-server=$it")) }
        sb.append(" about:blank")
        return sb.toString()
    }

    /** 简单 shell 转义，避免代理串里的特殊字符把命令拆坏。 */
    private fun shellQuote(s: String): String {
        if (!s.any { it in " \t\"'$\\`&|;<>(){}[]*?!#~" }) return s
        return "'" + s.replace("'", "'\\''") + "'"
    }

    /** 确保 Xvfb 在跑。 */
    private fun ensureDisplay() {
        val probe = execQuiet("pgrep -f 'Xvfb $DEFAULT_DISPLAY' | head -1")
        if (probe.isNotBlank()) return
        AppLogger.i(TAG, "启动 Xvfb $DEFAULT_DISPLAY")
        execQuiet(
            "setsid nohup Xvfb $DEFAULT_DISPLAY -screen 0 $XRES " +
                ">/tmp/xvfb_camoufox.log 2>&1 &"
        )
        Thread.sleep(2500)
    }

    /** 在 proot 里执行命令。由平台的终端通道承载。 */
    private fun runInProot(cmd: String, env: Map<String, String>): Boolean {
        // 环境变量拼到命令前，交给 proot 的 bash 展开
        val envPrefix = if (env.isEmpty()) "" else env.entries.joinToString(" ") { (k, v) ->
            "$k=${shellQuote(v)}"
        } + " "
        val full = "${envPrefix}setsid nohup bash -c " + shellQuote(cmd) +
            " >/tmp/camoufox_run.log 2>&1 &"
        val r = execQuiet(full)
        AppLogger.i(TAG, "proot 启动返回: ${r.take(200)}")
        return true
    }

    /** 走 super_admin 终端跑一条命令（同步取输出）。 */
    private fun execQuiet(cmd: String): String {
        return try {
            val pb = ProcessBuilder("sh", "-c", cmd)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = StringBuilder()
            BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                var line = r.readLine()
                while (line != null && out.length < 8192) {
                    out.append(line).append('\n')
                    line = r.readLine()
                }
            }
            proc.waitFor(10, TimeUnit.SECONDS)
            out.toString().trim()
        } catch (e: Throwable) {
            AppLogger.w(TAG, "exec 失败: ${e.message}")
            ""
        }
    }

    /** 探测端口是否有 BiDi 服务。 */
    private fun isPortAlive(port: Int): Boolean {
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", port), 800)
                true
            }
        } catch (e: Throwable) {
            false
        }
    }

    private fun waitPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isPortAlive(port)) return true
            Thread.sleep(600)
        }
        return false
    }

    /** 找一个空闲端口。 */
    private fun pickPort(): Int {
        for (p in DEFAULT_BIDI_PORT until DEFAULT_BIDI_PORT + 40) {
            if (!isPortAlive(p)) return p
        }
        return DEFAULT_BIDI_PORT
    }

    private fun findPid(bin: String): Int {
        val r = execQuiet("pgrep -f " + shellQuote(bin) + " | head -1")
        return r.trim().toIntOrNull() ?: -1
    }

    /** 按优先级找二进制。 */
    fun resolveBinary(explicit: String?): String? {
        if (!explicit.isNullOrBlank()) {
            val f = File(explicit)
            if (f.isFile && f.canExecute()) return explicit
        }
        val candidates = listOf(
            "/root/.camoufox/camoufox-bin",
            "/root/camoufox/camoufox-bin",
            "/opt/camoufox/camoufox-bin"
        )
        for (c in candidates) {
            val f = File(c)
            if (f.isFile && f.canExecute()) return c
        }
        val which = execQuiet("command -v camoufox-bin || command -v camoufox")
        return which.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    }

    /** 状态快照。 */
    fun status(): CamoufoxStatus = CamoufoxStatus(
        running = runningPort > 0 && isPortAlive(runningPort),
        bidiPort = runningPort,
        marionettePort = 2828,
        pid = runningPid,
        version = "158.0.1",
        lastError = lastError
    )

    /** 环境自检文本，用于上层展示。 */
    fun environmentSummary(context: Context): String {
        val bin = resolveBinary(null)
        val b = StringBuilder()
        b.append("binary=").append(bin ?: "缺")
        if (bin != null) {
            b.append(" size=").append(File(bin).length() / 1048576).append("MB")
        }
        b.append(" display=").append(DEFAULT_DISPLAY)
        b.append(" xvfb=").append(
            if (execQuiet("pgrep -f 'Xvfb $DEFAULT_DISPLAY'").isNotBlank()) "up" else "down"
        )
        b.append(" port=").append(runningPort)
        return b.toString()
    }

    /** 停掉引擎。 */
    fun stop() {
        runCatching { execQuiet("pkill -f camoufox-bin") }
        launched = false
        runningPort = 0
        runningPid = -1
    }
}

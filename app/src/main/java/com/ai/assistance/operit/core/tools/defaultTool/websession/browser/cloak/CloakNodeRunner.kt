package com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak

import android.content.Context
import com.ai.assistance.operit.util.AppLogger

/**
 * 节点运行器：把协议节点转成 CloakBrowser 能用的本地 socks5 端口。
 *
 * [NODE-SUB] 2026-11-02。
 *
 * ==================================================================
 * 完整链路
 * ==================================================================
 *
 *   主人给的订阅（vless/vmess/trojan/ss/hysteria2）
 *        ↓ CloakNodeParser.parse()
 *   结构化 Node 列表
 *        ↓ CloakNodeRunner.start()      ← 本类
 *   生成 mihomo config.yaml → 拉起 mihomo → 监听 127.0.0.1:PORT5
 *        ↓
 *   proxy = socks5://127.0.0.1:PORT   → 交给 CloakFingerprint.proxyServer
 *        ↓
 *   chromium --proxy-server=socks5://127.0.0.1:PORT
 *
 * ==================================================================
 * 为什么用 mihomo 而不是 sing-box
 * ==================================================================
 * 实测：mihomo v1.19.32 启动 0.8 秒即监听，日志明确
 * "Mixed(http+socks) proxy listening at: 127.0.0.1:PORT"。
 * 而且 mixed-port 一个端口同时支持 http 和 socks5，不需要配两个。
 * sing-box 也能用，但配置结构更繁琐，收益为零。
 *
 * ==================================================================
 * 端口策略
 * ==================================================================
 * 每个身份槽位独占一个端口（17000 + hash(slot) % 1000），
 * 避免多个槽位同时运行时互相覆盖。
 * 启动前先杀掉占该端口的旧 mihomo，防止配置不生效。
 */
internal object CloakNodeRunner {

    private const val TAG = "CloakNodeRunner"

    /**
     * mihomo 候选路径。
     *
     * 这些路径在开发机存在，但新设备装完 APK 后不一定有。
     * 全部按候选列表探测，探测不到就降级到下一级。
     */
    private val MIHOMO_CANDIDATES = listOf(
        "/root/nph/mihomo",
        "/root/bin/mihomo",
        "/usr/local/bin/mihomo",
        "/root/.operit_cloak/bin/mihomo",
    )

    /** sing-box 候选路径。 */
    private val SINGBOX_CANDIDATES = listOf(
        "/root/bin/sing-box",
        "/root/nph/sing-box",
        "/usr/local/bin/sing-box",
    )

    /** Python 隧道脚本在 proot 里的落地路径。 */
    private const val TUNNEL_SCRIPT = "/root/.operit_cloak/bin/operit-tunnel.py"

    /** assets 里隧道脚本的相对路径。 */
    private const val TUNNEL_ASSET = "tunnel/operit-tunnel.py"

    /** 工作目录根。 */
    private const val WORK_ROOT = "/root/.operit_cloak/nodes"

    /** 节点列表落盘文件。 */
    const val NODES_FILE = "/root/.operit_cloak/nodes.json"

    /** 端口基址。 */
    private const val PORT_BASE = 17000

    /** 运行状态。 */
    @Volatile private var runningPort: Int = 0
    @Volatile private var runningNodeName: String = ""
    @Volatile private var runningSlot: String = ""

    fun statusPort(): Int = runningPort
    fun statusNode(): String = runningNodeName
    fun statusSlot(): String = runningSlot

    /**
     * 为指定槽位启动代理，返回本地 socks5 地址。
     *
     * @param node 要使用的节点
     * @param slotName 槽位名，用于隔离端口与工作目录
     * @return socks5://127.0.0.1:PORT；失败返回 null
     */
    fun start(context: Context, node: CloakNodeParser.Node, slotName: String): String? {
        // http/socks 节点无需转换，直接可用
        if (node.isDirectProxy()) {
            val scheme = if (node.type.startsWith("socks")) "socks5" else "http"
            val auth = node.extra["userInfo"]?.let { "$it@" } ?: ""
            val direct = "$scheme://$auth${node.host}:${node.port}"
            AppLogger.i(TAG, "节点 ${node.name} 是直连代理，无需额外转换: $direct")
            runningPort = node.port
            runningNodeName = node.name
            runningSlot = slotName
            return direct
        }

        val port = portFor(slotName)

        // ---- 三级静默降级 ----
        // L1: mihomo（最稳，全协议）
        runCatching { startWithMihomo(context, node, slotName, port) }
            .getOrNull()?.let { return it }

        // L2: sing-box（次选）
        runCatching { startWithSingBox(context, node, slotName, port) }
            .getOrNull()?.let { return it }

        // L3: Python 隧道（零外部二进制，覆盖 ss/trojan/vless/http）
        runCatching { startWithPythonTunnel(context, node, slotName, port) }
            .getOrNull()?.let { return it }

        // 全败：静默返回 null，上层走直连，不把错误推给用户
        AppLogger.w(TAG, "节点 ${node.name} 无可用出口转换器，跳过代理")
        return null
    }

    /** L1：mihomo。找不到二进制返回 null。 */
    private fun startWithMihomo(
        context: Context,
        node: CloakNodeParser.Node,
        slotName: String,
        port: Int,
    ): String? {
        val bin = MIHOMO_CANDIDATES.firstOrNull { exists(context, it) } ?: return null
        val dir = "$WORK_ROOT/$slotName"
        val cfg = "$dir/config.yaml"
        CloakLauncher.prootPublic(
            context,
            "pkill -f 'mihomo.*$dir' 2>/dev/null; sleep 0.3; mkdir -p $dir",
            15_000L
        )
        val yaml = buildMihomoConfig(node, port)
        val b64 = android.util.Base64.encodeToString(
            yaml.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        val written = CloakLauncher.prootPublic(
            context,
            "echo '$b64' | base64 -d > $cfg; wc -c < $cfg",
            20_000L
        )
        if (written.isNullOrBlank() || written.trim() == "0") return null
        CloakLauncher.prootPublic(
            context,
            "cd $dir && nohup $bin -d $dir -f $cfg > $dir/mihomo.log 2>&1 & echo \$!",
            15_000L
        )
        if (!waitPort(context, port, 20_000L)) return null
        runningPort = port
        runningNodeName = node.name
        runningSlot = slotName
        return "socks5://127.0.0.1:$port"
    }

    /** L2：sing-box。 */
    private fun startWithSingBox(
        context: Context,
        node: CloakNodeParser.Node,
        slotName: String,
        port: Int,
    ): String? {
        val bin = SINGBOX_CANDIDATES.firstOrNull { exists(context, it) } ?: return null
        val dir = "$WORK_ROOT/$slotName"
        val cfg = "$dir/config.json"
        CloakLauncher.prootPublic(
            context,
            "pkill -f 'sing-box.*$dir' 2>/dev/null; mkdir -p $dir",
            12_000L
        )
        val json = buildSingBoxConfig(node, port)
        val b64 = android.util.Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        val written = CloakLauncher.prootPublic(
            context,
            "echo '$b64' | base64 -d > $cfg; wc -c < $cfg",
            20_000L
        )
        if (written.isNullOrBlank() || written.trim() == "0") return null
        CloakLauncher.prootPublic(
            context,
            "cd $dir && nohup $bin run -c $cfg > $dir/singbox.log 2>&1 & echo \$!",
            15_000L
        )
        if (!waitPort(context, port, 20_000L)) return null
        runningPort = port
        runningNodeName = node.name
        runningSlot = slotName
        return "socks5://127.0.0.1:$port"
    }

    /** L3：Python 隧道。 */
    private fun startWithPythonTunnel(
        context: Context,
        node: CloakNodeParser.Node,
        slotName: String,
        port: Int,
    ): String? {
        val t = node.type.lowercase()
        // hysteria2/tuic 需要 QUIC，Python 侧不支持，静默跳过
        if (t.contains("hysteria") || t.contains("tuic")) return null

        if (!ensureTunnelScript(context)) return null

        val dir = "$WORK_ROOT/$slotName"
        val cfg = "$dir/node.json"
        CloakLauncher.prootPublic(
            context,
            "pkill -f 'operit-tunnel.py.*--port $port' 2>/dev/null; mkdir -p $dir",
            12_000L
        )
        val json = nodeToJson(node)
        val b64 = android.util.Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        val written = CloakLauncher.prootPublic(
            context,
            "echo '$b64' | base64 -d > $cfg; wc -c < $cfg",
            20_000L
        )
        if (written.isNullOrBlank() || written.trim() == "0") return null

        CloakLauncher.prootPublic(
            context,
            "cd $dir && nohup python3 $TUNNEL_SCRIPT --config $cfg --port $port " +
                "> $dir/tunnel.log 2>&1 & echo \$!",
            15_000L
        )
        if (!waitPort(context, port, 20_000L)) return null
        runningPort = port
        runningNodeName = node.name
        runningSlot = slotName
        AppLogger.i(TAG, "Python 隧道就绪 → socks5://127.0.0.1:$port")
        return "socks5://127.0.0.1:$port"
    }

    /** 探测 proot 内可执行文件是否存在。 */
    private fun exists(context: Context, path: String): Boolean {
        val out = CloakLauncher.prootPublic(
            context,
            "test -x $path && echo YES || echo NO",
            8_000L
        )
        return out?.contains("YES") == true
    }

    /** 把 assets/tunnel/operit-tunnel.py 释放到 proot。返回是否可用。 */
    private fun ensureTunnelScript(context: Context): Boolean {
        val check = CloakLauncher.prootPublic(
            context,
            "test -s $TUNNEL_SCRIPT && echo OK || echo MISSING",
            8_000L
        )
        if (check?.contains("OK") == true) return true
        val body = runCatching {
            context.assets.open(TUNNEL_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return false
        val b64 = android.util.Base64.encodeToString(
            body.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        val out = CloakLauncher.prootPublic(
            context,
            "mkdir -p /root/.operit_cloak/bin 2>/dev/null; " +
                "echo '$b64' | base64 -d > $TUNNEL_SCRIPT; chmod 755 $TUNNEL_SCRIPT; " +
                "wc -c < $TUNNEL_SCRIPT",
            30_000L
        )
        return !out.isNullOrBlank()
    }

    /** Node → Python 隧道用的 JSON。 */
    private fun nodeToJson(node: CloakNodeParser.Node): String {
        val o = org.json.JSONObject()
        o.put("name", node.name)
        o.put("type", node.type.lowercase())
        o.put("host", node.host)
        o.put("port", node.port)
        o.put("extra", org.json.JSONObject(node.extra as Map<*, *>))
        return o.toString()
    }

    /** sing-box 最小配置。 */
    private fun buildSingBoxConfig(node: CloakNodeParser.Node, port: Int): String {
        val outbound = when (node.type.lowercase()) {
            "ss" -> org.json.JSONObject()
                .put("type", "shadowsocks")
                .put("tag", "proxy")
                .put("server", node.host)
                .put("server_port", node.port)
                .put("method", node.extra["method"] ?: "aes-256-gcm")
                .put("password", node.extra["password"] ?: "")
            "trojan" -> org.json.JSONObject()
                .put("type", "trojan")
                .put("tag", "proxy")
                .put("server", node.host)
                .put("server_port", node.port)
                .put("password", node.extra["password"] ?: "")
                .put(
                    "tls", org.json.JSONObject().put("enabled", true)
                        .put("server_name", node.extra["sni"] ?: node.host)
                )
            "vless" -> org.json.JSONObject()
                .put("type", "vless")
                .put("tag", "proxy")
                .put("server", node.host)
                .put("server_port", node.port)
                .put("uuid", node.extra["uuid"] ?: "")
                .put(
                    "tls", org.json.JSONObject()
                        .put("enabled", node.extra["tls"] == "1")
                        .put("server_name", node.extra["sni"] ?: node.host)
                )
            else -> org.json.JSONObject()
                .put("type", "socks")
                .put("tag", "proxy")
                .put("server", node.host)
                .put("server_port", node.port)
        }
        val root = org.json.JSONObject()
        root.put("log", org.json.JSONObject().put("level", "warn"))
        root.put(
            "inbounds", org.json.JSONArray().put(
                org.json.JSONObject()
                    .put("type", "socks")
                    .put("tag", "in")
                    .put("listen", "127.0.0.1")
                    .put("listen_port", port)
            )
        )
        root.put("outbounds", org.json.JSONArray().put(outbound))
        return root.toString()
    }



    /** 停止指定槽位的代理。 */
    fun stop(context: Context, slotName: String) {
        val dir = "$WORK_ROOT/$slotName"
        CloakLauncher.prootPublic(
            context,
            "pkill -f 'mihomo.*$dir' 2>/dev/null; " +
                "pkill -f 'sing-box.*$dir' 2>/dev/null; " +
                "pkill -f 'operit-tunnel.py.*$dir' 2>/dev/null; sleep 0.3; echo stopped",
            15_000L
        )
        if (runningSlot == slotName) {
            runningPort = 0
            runningNodeName = ""
            runningSlot = ""
        }
        AppLogger.i(TAG, "槽位 $slotName 的代理已停止")
    }

    /** 停止所有。 */
    fun stopAll(context: Context) {
        CloakLauncher.prootPublic(
            context,
            "pkill -f '$WORK_ROOT' 2>/dev/null; " +
                "pkill -f 'operit-tunnel.py' 2>/dev/null; sleep 0.3; echo stopped",
            15_000L
        )
        runningPort = 0
        runningNodeName = ""
        runningSlot = ""
    }

    /**
     * 生成 mihomo 配置。
     *
     * 只放一个节点 + 一个 select 组 + MATCH 规则，
     * 不做分流：我们的目标是「整个浏览器走这一个出口」，
     * 分流反而会让 IP 判定变复杂。
     */
    private fun buildMihomoConfig(node: CloakNodeParser.Node, port: Int): String {
        val sb = StringBuilder()
        sb.append("mixed-port: ").append(port).append('\n')
        sb.append("allow-lan: false\n")
        sb.append("bind-address: 127.0.0.1\n")
        sb.append("mode: rule\n")
        sb.append("log-level: info\n")
        sb.append("ipv6: false\n")
        sb.append("unified-delay: true\n")
        sb.append("tcp-concurrent: true\n")
        sb.append('\n')
        sb.append("proxies:\n")
        sb.append("  - ").append(proxyYaml(node)).append('\n')
        sb.append('\n')
        sb.append("proxy-groups:\n")
        sb.append("  - name: PROXY\n")
        sb.append("    type: select\n")
        sb.append("    proxies:\n")
        sb.append("      - ").append(yamlStr(node.name)).append('\n')
        sb.append('\n')
        sb.append("rules:\n")
        sb.append("  - MATCH,PROXY\n")
        return sb.toString()
    }

    /** 单个节点的 mihomo YAML 片段。 */
    private fun proxyYaml(n: CloakNodeParser.Node): String = when (n.type) {
        "vmess" -> buildString {
            append("{name: ").append(yamlStr(n.name))
            append(", type: vmess")
            append(", server: ").append(yamlStr(n.host))
            append(", port: ").append(n.port)
            append(", uuid: ").append(yamlStr(n.extra["uuid"] ?: ""))
            append(", alterId: ").append(n.extra["alterId"] ?: "0")
            append(", cipher: ").append(yamlStr(n.extra["cipher"] ?: "auto"))
            append(", udp: true")
            val net = n.extra["network"] ?: "tcp"
            append(", network: ").append(yamlStr(net))
            val tls = n.extra["tls"] ?: ""
            if (tls.isNotBlank() && tls != "none") {
                append(", tls: true")
                val sni = n.extra["sni"] ?: ""
                if (sni.isNotBlank()) append(", servername: ").append(yamlStr(sni))
            }
            if (net == "ws") {
                append(", ws-opts: {path: ")
                append(yamlStr(n.extra["path"] ?: "/"))
                val h = n.extra["wsHost"] ?: ""
                if (h.isNotBlank()) append(", headers: {Host: ").append(yamlStr(h)).append('}')
                append('}')
            }
            append('}')
        }

        "vless" -> buildString {
            append("{name: ").append(yamlStr(n.name))
            append(", type: vless")
            append(", server: ").append(yamlStr(n.host))
            append(", port: ").append(n.port)
            append(", uuid: ").append(yamlStr(n.extra["userInfo"] ?: ""))
            append(", udp: true")
            val flow = n.extra["flow"] ?: ""
            if (flow.isNotBlank()) append(", flow: ").append(yamlStr(flow))
            val sec = n.extra["security"] ?: ""
            if (sec.startsWith("tls") || sec == "reality") {
                append(", tls: true")
                val sni = n.extra["sni"] ?: n.extra["peer"] ?: ""
                if (sni.isNotBlank()) append(", servername: ").append(yamlStr(sni))
                if (sec == "reality") {
                    append(", reality-opts: {public-key: ")
                    append(yamlStr(n.extra["pbk"] ?: ""))
                    val sid = n.extra["sid"] ?: ""
                    if (sid.isNotBlank()) append(", short-id: ").append(yamlStr(sid))
                    append('}')
                }
                val fp = n.extra["fp"] ?: ""
                if (fp.isNotBlank()) append(", client-fingerprint: ").append(yamlStr(fp))
            }
            val net = n.extra["type"] ?: "tcp"
            append(", network: ").append(yamlStr(net))
            if (net == "ws") {
                append(", ws-opts: {path: ")
                append(yamlStr(n.extra["path"] ?: "/"))
                val h = n.extra["host"] ?: ""
                if (h.isNotBlank()) append(", headers: {Host: ").append(yamlStr(h)).append('}')
                append('}')
            } else if (net == "grpc") {
                append(", grpc-opts: {grpc-service-name: ")
                append(yamlStr(n.extra["serviceName"] ?: ""))
                append('}')
            }
            append('}')
        }

        "trojan" -> buildString {
            append("{name: ").append(yamlStr(n.name))
            append(", type: trojan")
            append(", server: ").append(yamlStr(n.host))
            append(", port: ").append(n.port)
            append(", password: ").append(yamlStr(n.extra["userInfo"] ?: ""))
            append(", udp: true")
            val sni = n.extra["sni"] ?: n.extra["peer"] ?: ""
            if (sni.isNotBlank()) append(", sni: ").append(yamlStr(sni))
            val allow = n.extra["allowInsecure"]
            if (allow == "1" || allow == "true") append(", skip-cert-verify: true")
            append('}')
        }

        "ss" -> buildString {
            append("{name: ").append(yamlStr(n.name))
            append(", type: ss")
            append(", server: ").append(yamlStr(n.host))
            append(", port: ").append(n.port)
            append(", cipher: ").append(yamlStr(n.extra["method"] ?: "aes-128-gcm"))
            append(", password: ").append(yamlStr(n.extra["password"] ?: ""))
            append(", udp: true")
            append('}')
        }

        "hysteria2", "hy2" -> buildString {
            append("{name: ").append(yamlStr(n.name))
            append(", type: hysteria2")
            append(", server: ").append(yamlStr(n.host))
            append(", port: ").append(n.port)
            append(", password: ").append(yamlStr(n.extra["userInfo"] ?: ""))
            val sni = n.extra["sni"] ?: ""
            if (sni.isNotBlank()) append(", sni: ").append(yamlStr(sni))
            val ins = n.extra["insecure"]
            if (ins == "1" || ins == "true") append(", skip-cert-verify: true")
            append('}')
        }

        else -> "{name: ${yamlStr(n.name)}, type: socks5, server: ${yamlStr(n.host)}, port: ${n.port}}"
    }

    /** YAML 字符串安全引用。 */
    private fun yamlStr(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    /** 槽位 → 端口，稳定映射。 */
    private fun portFor(slot: String): Int {
        var h = 0
        for (c in slot) h = 31 * h + c.code
        return PORT_BASE + (Math.abs(h) % 1000)
    }

    /** 轮询端口是否可连。 */
    private fun waitPort(context: Context, port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val out = CloakLauncher.prootPublic(
                context,
                "timeout 2 bash -c '</dev/tcp/127.0.0.1/$port' 2>/dev/null && echo OPEN || echo CLOSED",
                5_000L
            )
            if (out?.contains("OPEN") == true) return true
            Thread.sleep(400)
        }
        return false
    }

    /** 保存节点表（供下次免订阅复用）。 */
    fun saveNodes(context: Context, nodes: List<CloakNodeParser.Node>) {
        val arr = org.json.JSONArray()
        nodes.forEach { n ->
            arr.put(org.json.JSONObject().apply {
                put("name", n.name)
                put("type", n.type)
                put("host", n.host)
                put("port", n.port)
                put("raw", n.raw)
                put("extra", org.json.JSONObject(n.extra as Map<*, *>))
            })
        }
        val b64 = android.util.Base64.encodeToString(
            arr.toString().toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        CloakLauncher.prootPublic(
            context,
            "mkdir -p /root/.operit_cloak 2>/dev/null; echo '$b64' | base64 -d > $NODES_FILE; wc -c < $NODES_FILE",
            20_000L
        )
        AppLogger.i(TAG, "节点表已保存 ${nodes.size} 条")
    }

    /** 读取已保存的节点表。 */
    fun loadNodes(context: Context): List<CloakNodeParser.Node> {
        val raw = CloakLauncher.prootPublic(context, "cat $NODES_FILE 2>/dev/null", 15_000L)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val ex = mutableMapOf<String, String>()
                o.optJSONObject("extra")?.let { e ->
                    e.keys().forEach { k -> ex[k] = e.optString(k) }
                }
                CloakNodeParser.Node(
                    name = o.optString("name"),
                    type = o.optString("type"),
                    host = o.optString("host"),
                    port = o.optInt("port"),
                    raw = o.optString("raw"),
                    extra = ex
                )
            }
        }.getOrDefault(emptyList())
    }
}

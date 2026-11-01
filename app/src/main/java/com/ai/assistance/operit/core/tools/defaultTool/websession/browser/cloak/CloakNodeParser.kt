package com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak

import android.util.Base64
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONObject

/**
 * 节点订阅解析器。
 *
 * [NODE-SUB] 2026-11-02。
 *
 * ==================================================================
 * 为什么需要这一层
 * ==================================================================
 * CloakBrowser 的 `--proxy-server=` 只接受两种形态：
 *   http://[user:pass@]host:port
 *   socks5://[user:pass@]host:port
 *
 * 而主流机场订阅给的是「节点列表」：
 *   vless:// / vmess:// / trojan:// / ss:// / hysteria2://
 * 这些是**协议**，不是代理端口，chromium 一律不认。
 *
 * 所以链路必须是：
 *   订阅(协议节点) → mihomo 本地转换 → socks5://127.0.0.1:PORT → CloakBrowser
 *
 * 本类只负责第一步：把订阅文本解析成结构化的 Node 列表。
 * 起 mihomo、生成本地配置由 CloakNodeRunner 负责。
 *
 * ==================================================================
 * 支持的订阅格式
 * ==================================================================
 * 1. 纯 base64：`base64 -d sub.txt` 后是一行行节点 URI（最常见）
 * 2. 明文 URI 列表：同上但不带 base64
 * 3. Clash YAML：含 `proxies:` 段（mihomo 可直接吃，无需转换）
 *
 * 本解析器对 1/2 做 URI 解析；对 3 只做「识别 + 原样透传」，
 * 因为 Clash YAML 本身就是 mihomo 的原生配置格式，解析再拼是多余且易错。
 */
internal object CloakNodeParser {

    private const val TAG = "CloakNode"

    /**
     * 一个节点。
     *
     * @param name 显示名（订阅里 # 后面的部分，URL 解码后）
     * @param type 协议类型：vless / vmess / trojan / ss / hysteria2 / http / socks5
     * @param host 服务器地址
     * @param port 端口
     * @param raw 原始 URI，供 mihomo 配置生成时做兜底
     * @param extra 协议特有字段（uuid / password / sni / ws-path 等）
     */
    data class Node(
        val name: String,
        val type: String,
        val host: String,
        val port: Int,
        val raw: String,
        val extra: Map<String, String> = emptyMap()
    ) {
        /** 地区推断：从节点名里找国家关键词，用于和时区做一致性检查。 */
        fun regionHint(): String {
            val n = name
            val map = listOf(
                "香港" to "HK", "HK" to "HK", "Hong" to "HK",
                "台湾" to "TW", "台灣" to "TW", "TW" to "TW",
                "日本" to "JP", "东京" to "JP", "東京" to "JP", "JP" to "JP", "Japan" to "JP",
                "新加坡" to "SG", "狮城" to "SG", "SG" to "SG", "Singapore" to "SG",
                "美国" to "US", "洛杉" to "US", "硅谷" to "US", "US" to "US", "United" to "US",
                "韩国" to "KR", "首尔" to "KR", "KR" to "KR", "Korea" to "KR",
                "德国" to "DE", "法兰" to "DE", "DE" to "DE",
                "英国" to "GB", "伦敦" to "GB", "GB" to "GB",
                "法国" to "FR", "巴黎" to "FR", "FR" to "FR",
                "俄罗斯" to "RU", "RU" to "RU",
                "印度" to "IN", "IN" to "IN",
                "土耳其" to "TR", "TR" to "TR",
                "巴西" to "BR", "BR" to "BR",
                "加拿大" to "CA", "CA" to "CA"
            )
            for ((k, v) in map) if (n.contains(k, ignoreCase = true)) return v
            return ""
        }

        /** 是否是可直连的 http/socks 代理（无需 mihomo 转换）。 */
        fun isDirectProxy(): Boolean = type == "http" || type == "https" || type == "socks5" || type == "socks4"
    }

    /**
     * 解析订阅内容。
     *
     * @param body 订阅原始文本（可能是 base64，可能是明文，可能是 YAML）
     * @return 解析出的节点列表；解析失败返回空表
     */
    fun parse(body: String): List<Node> {
        val text = body.trim()
        if (text.isEmpty()) return emptyList()

        // 判断是不是 Clash YAML
        if (looksLikeClashYaml(text)) {
            AppLogger.i(TAG, "识别为 Clash YAML 订阅，交给 mihomo 原生处理")
            return parseClashYaml(text)
        }

        // 尝试 base64 解码
        val decoded = tryDecodeBase64(text)
        val payload = if (decoded != null && looksLikeNodeList(decoded)) {
            AppLogger.i(TAG, "订阅为 base64 编码，已解码，长度 ${decoded.length}")
            decoded
        } else {
            text
        }

        return parseUriList(payload)
    }

    private fun looksLikeClashYaml(t: String): Boolean =
        t.contains("proxies:") || t.contains("proxy-groups:") || t.startsWith("port:")

    private fun looksLikeNodeList(t: String): Boolean {
        val kw = listOf("://", "vless", "vmess", "trojan", "ss://", "hysteria")
        return kw.any { t.contains(it) }
    }

    /** base64 解码，容忍换行与无 padding。 */
    private fun tryDecodeBase64(t: String): String? {
        return runCatching {
            val cleaned = t.filterNot { it.isWhitespace() }
            // 补齐 padding
            val pad = (4 - cleaned.length % 4) % 4
            val padded = cleaned + "=".repeat(pad)
            val bytes = Base64.decode(padded, Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        }.getOrNull()
    }

    /**
     * 解析一行一节点的 URI 列表。
     */
    private fun parseUriList(payload: String): List<Node> {
        val out = mutableListOf<Node>()
        payload.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("//")) return@forEach
            val node = runCatching { parseOne(line) }.getOrNull()
            if (node != null) out.add(node)
        }
        AppLogger.i(TAG, "解析出 ${out.size} 个节点")
        return out
    }

    private fun parseOne(line: String): Node? {
        val scheme = line.substringBefore("://", "").lowercase()
        if (scheme.isEmpty()) return null
        return when (scheme) {
            "vmess" -> parseVmess(line)
            "vless" -> parseUriStyle(line, "vless")
            "trojan" -> parseUriStyle(line, "trojan")
            "ss" -> parseShadowsocks(line)
            "hysteria2", "hy2" -> parseUriStyle(line, "hysteria2")
            "hysteria" -> parseUriStyle(line, "hysteria")
            "http", "https" -> parseUriStyle(line, scheme)
            "socks5", "socks" -> parseUriStyle(line, "socks5")
            else -> null
        }
    }

    /** vmess:// 是 base64(JSON)。 */
    private fun parseVmess(line: String): Node? {
        val b64 = line.removePrefix("vmess://").substringBefore("#").trim()
        val json = tryDecodeBase64(b64) ?: return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val host = o.optString("add").ifBlank { return null }
        val port = o.opt("port").let {
            when (it) {
                is Int -> it
                is String -> it.toIntOrNull() ?: 0
                else -> 0
            }
        }
        if (port <= 0) return null
        return Node(
            name = o.optString("ps").ifBlank { "$host:$port" },
            type = "vmess",
            host = host,
            port = port,
            raw = line,
            extra = buildMap {
                put("uuid", o.optString("id"))
                put("alterId", o.optString("aid", "0"))
                put("cipher", o.optString("scy", "auto"))
                put("network", o.optString("net", "tcp"))
                put("tls", o.optString("tls"))
                put("sni", o.optString("sni").ifBlank { o.optString("host") })
                put("path", o.optString("path"))
                put("wsHost", o.optString("host"))
            }
        )
    }

    /**
     * 通用 URI 形态：scheme://[userinfo@]host:port?query#name
     * vless / trojan / hysteria2 / http / socks5 都走这条。
     */
    private fun parseUriStyle(line: String, type: String): Node? {
        val body = line.substringAfter("://")
        val name = runCatching {
            java.net.URLDecoder.decode(body.substringAfter("#", ""), "UTF-8")
        }.getOrDefault("").ifBlank { "" }

        val noFrag = body.substringBefore("#")
        val query = if (noFrag.contains("?")) noFrag.substringAfter("?") else ""
        val main = noFrag.substringBefore("?")

        // userinfo@host:port
        val userInfo = if (main.contains("@")) main.substringBefore("@") else ""
        val hostPort = if (main.contains("@")) main.substringAfter("@") else main

        // host 可能是 [ipv6]:port
        var host: String
        var portStr: String
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end < 0) return null
            host = hostPort.substring(1, end)
            portStr = hostPort.substring(end + 1).removePrefix(":")
        } else {
            host = hostPort.substringBefore(":")
            portStr = hostPort.substringAfter(":", "")
        }
        val port = portStr.toIntOrNull() ?: return null
        if (host.isBlank()) return null

        val extra = mutableMapOf<String, String>()
        if (userInfo.isNotBlank()) {
            extra["userInfo"] = runCatching {
                java.net.URLDecoder.decode(userInfo, "UTF-8")
            }.getOrDefault(userInfo)
        }
        if (query.isNotBlank()) {
            query.split("&").forEach { kv ->
                val k = kv.substringBefore("=")
                val v = runCatching {
                    java.net.URLDecoder.decode(kv.substringAfter("=", ""), "UTF-8")
                }.getOrDefault(kv.substringAfter("=", ""))
                if (k.isNotBlank()) extra[k] = v
            }
        }

        return Node(
            name = name.ifBlank { "$host:$port" },
            type = type,
            host = host,
            port = port,
            raw = line,
            extra = extra
        )
    }

    /**
     * ss:// 有两种形态：
     *   SIP002: ss://base64(method:pass)@host:port#name
     *   老式   : ss://base64(method:pass@host:port)#name
     */
    private fun parseShadowsocks(line: String): Node? {
        val body = line.removePrefix("ss://").substringBefore("#")
        val query = if (body.contains("?")) body.substringAfter("?") else ""
        val core = body.substringBefore("?")

        if (core.contains("@")) {
            // SIP002
            val userB64 = core.substringBefore("@")
            val hostPort = core.substringAfter("@")
            val cred = tryDecodeBase64(userB64) ?: return null
            val host = hostPort.substringBefore(":")
            val port = hostPort.substringAfter(":", "").toIntOrNull() ?: return null
            val nameRaw = line.substringAfter("#", "").ifBlank { "$host:$port" }
            val name = runCatching {
                java.net.URLDecoder.decode(nameRaw, "UTF-8")
            }.getOrDefault(nameRaw)
            return Node(
                name = name.ifBlank { "$host:$port" },
                type = "ss",
                host = host,
                port = port,
                raw = line,
                extra = buildMap {
                    put("method", cred.substringBefore(":"))
                    put("password", cred.substringAfter(":", ""))
                    if (query.isNotBlank()) put("plugin", query)
                }
            )
        } else {
            // 老式：整体 base64
            val dec = tryDecodeBase64(core) ?: return null
            val at = dec.lastIndexOf('@')
            if (at < 0) return null
            val cred = dec.substring(0, at)
            val hostPort = dec.substring(at + 1)
            val host = hostPort.substringBefore(":")
            val port = hostPort.substringAfter(":", "").toIntOrNull() ?: return null
            val nameRaw = line.substringAfter("#", "").ifBlank { "$host:$port" }
            val name = runCatching {
                java.net.URLDecoder.decode(nameRaw, "UTF-8")
            }.getOrDefault(nameRaw)
            return Node(
                name = name.ifBlank { "$host:$port" },
                type = "ss",
                host = host,
                port = port,
                raw = line,
                extra = mapOf(
                    "method" to cred.substringBefore(":"),
                    "password" to cred.substringAfter(":", "")
                )
            )
        }
    }

    /**
     * Clash YAML：只做最简识别（type/server/port/name），
     * 完整配置交给 mihomo 原生解析。
     */
    private fun parseClashYaml(text: String): List<Node> {
        val out = mutableListOf<Node>()
        var cur: MutableMap<String, String>? = null
        text.lineSequence().forEach { line ->
            val t = line.trim()
            when {
                t.startsWith("- {") -> {
                    // 单行写法：- {name: a, type: ss, server: b, port: 1}
                    val inner = t.removePrefix("- {").removeSuffix("}")
                    val m = mutableMapOf<String, String>()
                    inner.split(",").forEach { kv ->
                        val k = kv.substringBefore(":").trim()
                        val v = kv.substringAfter(":", "").trim().trim('"', '\'')
                        if (k.isNotBlank()) m[k] = v
                    }
                    emitClashNode(m)?.let { out.add(it) }
                }
                t.startsWith("- name:") || t.startsWith("-  name:") -> {
                    cur?.let { emitClashNode(it)?.let { n -> out.add(n) } }
                    cur = mutableMapOf("name" to t.substringAfter("name:").trim().trim('"', '\''))
                }
                cur != null && t.contains(":") && !t.startsWith("#") -> {
                    val k = t.substringBefore(":").trim()
                    val v = t.substringAfter(":", "").trim().trim('"', '\'')
                    if (k.isNotBlank() && v.isNotBlank()) cur!![k] = v
                }
            }
        }
        cur?.let { emitClashNode(it)?.let { n -> out.add(n) } }
        AppLogger.i(TAG, "Clash YAML 识别出 ${out.size} 个节点")
        return out
    }

    private fun emitClashNode(m: Map<String, String>): Node? {
        val host = m["server"] ?: return null
        val port = m["port"]?.toIntOrNull() ?: return null
        val type = m["type"] ?: "unknown"
        return Node(
            name = m["name"] ?: "$host:$port",
            type = type,
            host = host,
            port = port,
            raw = "",
            extra = m
        )
    }
}
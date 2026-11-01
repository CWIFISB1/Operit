package com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak

import com.ai.assistance.operit.util.AppLogger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 出口 IP 地理探测与「时区/语言同源」纠正。
 *
 * [GEO-SYNC] 2026-11-02。
 *
 * ==================================================================
 * 为什么必须做这件事
 * ==================================================================
 * 设备实测出口：20.249.148.30 → 韩国首尔 / Microsoft 云。
 * 而指纹生成器给的是 Europe/Paris + zh-CN。
 *
 * 于是同一会话里出现了三重矛盾：
 *   · IP 在首尔      ← 网络层
 *   · 时区说巴黎     ← JS 层 Intl.DateTimeFormat
 *   · 语言说中文     ← JS 层 navigator.languages
 *
 * 风控只要把「IP 归属地」与「浏览器时区」一比，立刻判定伪造。
 * 这是比 Canvas 哈希更廉价、更早被用到的判据。
 *
 * ==================================================================
 * 处理原则
 * ==================================================================
 * 时区与语言**必须由代理出口 IP 反查得出**，不能由种子随意生成。
 * 种子只负责 Canvas / WebGL / 字体这些图像级特征。
 *
 * 例外：没有代理时（直连），用设备真实出口 IP 反查，
 * 这样「韩国 IP + 首尔时区」至少是自洽的 —— 虽然机房 IP 仍差，
 * 但不至于出现地理矛盾的硬伤。
 *
 * ==================================================================
 * 反查源
 * ==================================================================
 * ip-api.com 的免费 JSON 接口，无需 key，限速 45 次/分钟。
 * 结果按 IP 缓存，同一 IP 不重复请求。
 */
internal object CloakGeoResolver {

    private const val TAG = "CloakGeo"

    /** 期望的查询字段。 */
    private const val FIELDS = "status,message,country,countryCode,regionName,city,timezone,isp,org,as,proxy,hosting,mobile,query"

    /** IP → 地理信息的缓存，避免重复请求触发限速。 */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, GeoInfo>()

    /**
     * 地理信息。
     *
     * @param ip 查询到的出口 IP
     * @param country 国家名
     * @param countryCode ISO 国家码
     * @param timezone IANA 时区，如 Asia/Seoul
     * @param isp 运营商
     * @param isHosting 是否为机房/云厂商 IP（true = 高危）
     * @param isProxy 是否被标记为已知代理
     * @param isMobile 是否移动网络
     */
    data class GeoInfo(
        val ip: String,
        val country: String = "",
        val countryCode: String = "",
        val city: String = "",
        val timezone: String = "",
        val isp: String = "",
        val isHosting: Boolean = false,
        val isProxy: Boolean = false,
        val isMobile: Boolean = false,
        val ok: Boolean = false,
        val error: String = ""
    ) {
        /** 是否适合作为长期身份出口。 */
        fun residentialGrade(): Boolean = ok && !isHosting && !isProxy

        /** 一句话风险描述。 */
        fun riskNote(): String = when {
            !ok -> "查询失败：$error"
            isProxy -> "⚠️ 已被标记为代理 IP，多数风控直接拒绝"
            isHosting -> "⚠️ 机房/云厂商 IP，权重很低（住宅代理才是正解）"
            isMobile -> "✅ 移动网络 IP，池大难聚类，质量良好"
            else -> "✅ 疑似住宅 IP，可用于长期养号"
        }
    }

    /**
     * 查询指定代理的出口 IP 及其地理信息。
     *
     * 注意：本方法**不**通过代理发请求，而是先查代理本身的出口。
     * 做法是带代理去请求 ip-api，拿到的是代理出口 IP。
     *
     * @param proxyServer 形如 socks5://user:pass@host:port 或 http://host:port
     *                    传 null/空 表示直连，走设备真实出口
     */
    fun resolve(proxyServer: String?): GeoInfo {
        val key = proxyServer?.trim().orEmpty()
        cache[key]?.let { return it }

        val info = if (key.isBlank()) {
            // 直连：直接用 ip-api 查本机出口
            queryGeo(null)
        } else {
            val parsed = parseProxy(key)
            if (parsed == null) {
                GeoInfo(ip = "", ok = false, error = "代理格式无法解析：$key")
            } else {
                queryGeo(parsed)
            }
        }
        cache[key] = info
        AppLogger.i(
            TAG,
            "geo(${key.ifBlank { "direct" }}): ip=${info.ip} ${info.country}/${info.timezone} " +
                "hosting=${info.isHosting} isp=${info.isp}"
        )
        return info
    }

    /** 解析代理串。支持 socks5 / socks4 / http / https。 */
    private fun parseProxy(raw: String): ProxySpec? {
        val s = raw.trim()
        val scheme = when {
            s.startsWith("socks5://", true) -> "socks"
            s.startsWith("socks4://", true) -> "socks4"
            s.startsWith("https://", true) -> "https"
            s.startsWith("http://", true) -> "http"
            else -> return null
        }
        var rest = s.substringAfter("://")
        var user: String? = null
        var pass: String? = null
        if (rest.contains("@")) {
            val cred = rest.substringBefore("@")
            rest = rest.substringAfter("@")
            if (cred.contains(":")) {
                user = cred.substringBefore(":")
                pass = cred.substringAfter(":")
            } else {
                user = cred
            }
        }
        val host = rest.substringBefore(":")
        val port = rest.substringAfter(":", "").toIntOrNull() ?: return null
        if (host.isBlank()) return null
        return ProxySpec(scheme, host, port, user, pass)
    }

    private data class ProxySpec(
        val scheme: String,
        val host: String,
        val port: Int,
        val user: String?,
        val pass: String?
    )

    /**
     * 通过（可选的）代理查询 ip-api。
     *
     * 之所以要带代理：只有让请求从代理出口出去，查到的才是代理的出口 IP。
     */
    private fun queryGeo(proxy: ProxySpec?): GeoInfo {
        val url = "http://ip-api.com/json/?fields=$FIELDS&lang=zh-CN"
        return try {
            val javaProxy: java.net.Proxy? = if (proxy == null) null else {
                val type = if (proxy.scheme.startsWith("socks")) {
                    java.net.Proxy.Type.SOCKS
                } else {
                    java.net.Proxy.Type.HTTP
                }
                // HttpURLConnection 不读 URL 里的 user:pass，认证要走 Authenticator
                if (!proxy.user.isNullOrBlank()) {
                    java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                        override fun getPasswordAuthentication(): java.net.PasswordAuthentication =
                            java.net.PasswordAuthentication(
                                proxy.user,
                                (proxy.pass ?: "").toCharArray()
                            )
                    })
                }
                java.net.Proxy(type, java.net.InetSocketAddress(proxy.host, proxy.port))
            }

            val conn = (if (javaProxy != null) URL(url).openConnection(javaProxy) else URL(url).openConnection())
                as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.requestMethod = "GET"
            val body = conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            conn.disconnect()
            parseGeo(body, if (proxy != null) proxy.host else "")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "geo 查询失败: ${t.message}")
            GeoInfo(ip = "", ok = false, error = t.message ?: t.javaClass.simpleName)
        }
    }

    private fun parseGeo(raw: String, fallbackIp: String): GeoInfo {
        return runCatching {
            val o = JSONObject(raw)
            if (o.optString("status") != "success") {
                return GeoInfo(ip = "", ok = false, error = o.optString("message", "unknown"))
            }
            GeoInfo(
                ip = o.optString("query", fallbackIp),
                country = o.optString("country"),
                countryCode = o.optString("countryCode"),
                city = o.optString("city"),
                timezone = o.optString("timezone"),
                isp = o.optString("isp").ifBlank { o.optString("org") },
                isHosting = o.optBoolean("hosting", false),
                isProxy = o.optBoolean("proxy", false),
                isMobile = o.optBoolean("mobile", false),
                ok = true
            )
        }.getOrElse {
            GeoInfo(ip = fallbackIp, ok = false, error = "JSON 解析失败")
        }
    }

    /** 清空缓存（换代理后强制重查）。 */
    fun clearCache() = cache.clear()

    /**
     * 由国家码推导最合适的语言。
     *
     * 桌面浏览器语言通常跟随操作系统区域设置，与 IP 归属国有强相关。
     * 这里只覆盖常见国家，其余回落英文。
     */
    fun languageFor(countryCode: String): String = when (countryCode.uppercase()) {
        "CN" -> "zh-CN"
        "TW" -> "zh-TW"
        "HK" -> "zh-HK"
        "JP" -> "ja-JP"
        "KR" -> "ko-KR"
        "DE" -> "de-DE"
        "FR" -> "fr-FR"
        "GB" -> "en-GB"
        "US", "CA" -> "en-US"
        "AU", "NZ" -> "en-AU"
        "SG" -> "en-SG"
        "MY" -> "ms-MY"
        "TH" -> "th-TH"
        "VN" -> "vi-VN"
        "ID" -> "id-ID"
        "PH" -> "en-PH"
        "IN" -> "en-IN"
        "BR" -> "pt-BR"
        "MX", "ES", "AR", "CL", "CO" -> "es-ES"
        "RU" -> "ru-RU"
        "IT" -> "it-IT"
        "NL" -> "nl-NL"
        "PL" -> "pl-PL"
        "TR" -> "tr-TR"
        "AE", "SA" -> "ar-AE"
        else -> "en-US"
    }
}
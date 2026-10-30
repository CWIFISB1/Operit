package com.ai.assistance.operit.core.tools.defaultTool.websession.browser.camoufox

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 [CamoufoxFingerprint] 翻译成 Camoufox 引擎认识的 CAMOU_CONFIG。
 *
 * ## 为什么不能直接传 JSON 字符串
 *
 * Camoufox 的 C++ 层 MaskConfig 从环境变量读配置，而 Linux 单个环境变量
 * 有长度上限（约 128KB 但实际受 execve 参数区限制，且长值在多进程继承时
 * 容易出问题）。官方做法是按固定大小切片，写成 CAMOU_CONFIG_1..N。
 *
 * ## 键名规则（容易踩坑）
 *
 * 官方 from_preset() 产出的键不是全小写：
 *   navigator.userAgent / navigator.platform / navigator.oscpu
 *   navigator.hardwareConcurrency / navigator.maxTouchPoints
 *   screen.width / screen.height / screen.colorDepth / screen.pixelDepth
 *   screen.availWidth / screen.availHeight
 *   webGl:vendor / webGl:renderer          ← 大写 G，冒号分隔
 *   audio:seed                              ← 冒号分隔
 *   fonts / voices / timezone
 *
 * 写成 webgl:vendor（小写）不会报错，但 C++ 层读不到，指纹保持宿主值。
 */
internal object CamoufoxConfigBuilder {

    /** 官方切块大小。与 camoufox/utils.py 的 chunk_size 保持一致。 */
    private const val CHUNK_SIZE = 4000

    /**
     * 构建扁平配置 JSON。
     *
     * 只写入非空字段，让未指定的项回落到 Camoufox 内置预设，
     * 避免把 0 / 空串当成有效值传下去。
     */
    fun buildConfigJson(fp: CamoufoxFingerprint): String {
        val o = JSONObject()

        // ---- navigator ----
        val ua = fp.userAgent.ifBlank { fp.derivedUserAgent() }
        o.put("navigator.userAgent", ua)
        o.put("navigator.platform", fp.derivedPlatform())
        o.put("navigator.oscpu", fp.derivedOscpu())
        o.put("navigator.appVersion", appVersionFrom(ua))
        if (fp.hardwareConcurrency > 0) {
            o.put("navigator.hardwareConcurrency", fp.hardwareConcurrency)
        }
        o.put("navigator.maxTouchPoints", fp.maxTouchPoints)

        // ---- screen ----
        if (fp.screenWidth > 0) o.put("screen.width", fp.screenWidth)
        if (fp.screenHeight > 0) o.put("screen.height", fp.screenHeight)
        if (fp.screenColorDepth > 0) {
            o.put("screen.colorDepth", fp.screenColorDepth)
            o.put("screen.pixelDepth", fp.screenColorDepth)
        }
        if (fp.screenAvailWidth > 0) o.put("screen.availWidth", fp.screenAvailWidth)
        if (fp.screenAvailHeight > 0) o.put("screen.availHeight", fp.screenAvailHeight)

        // ---- webgl（键名必须 webGl: 大写 G）----
        if (fp.webglVendor.isNotBlank()) o.put("webGl:vendor", fp.webglVendor)
        if (fp.webglRenderer.isNotBlank()) o.put("webGl:renderer", fp.webglRenderer)

        // ---- audio 噪声种子 ----
        val seed = fp.seed ?: ((System.nanoTime() and 0xFFFFFFFFL).toInt())
        o.put("audio:seed", if (seed == 0) 1 else Math.abs(seed))

        // ---- locale / timezone ----
        if (fp.timezone.isNotBlank()) o.put("timezone", fp.timezone)

        // ---- 字体 ----
        if (fp.fonts.isNotEmpty()) {
            val arr = JSONArray()
            fp.fonts.forEach { arr.put(it) }
            o.put("fonts", arr)
        }

        // ---- 语音：保留调用方给的原始 JSON 结构 ----
        if (fp.voicesJson.isNotBlank()) {
            runCatching { o.put("voices", JSONArray(fp.voicesJson)) }
        }

        return o.toString()
    }

    /**
     * 把配置 JSON 切成 CAMOU_CONFIG_<n> 环境变量表。
     *
     * 返回值可以直接喂给 ProcessBuilder.environment()。
     */
    fun buildEnv(configJson: String): Map<String, String> {
        if (configJson.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        var idx = 1
        var pos = 0
        while (pos < configJson.length) {
            val end = minOf(pos + CHUNK_SIZE, configJson.length)
            out["CAMOU_CONFIG_$idx"] = configJson.substring(pos, end)
            idx++
            pos = end
        }
        return out
    }

    /** 一步到位：指纹 → 环境变量表。 */
    fun buildEnv(fp: CamoufoxFingerprint): Map<String, String> =
        buildEnv(buildConfigJson(fp))

    /**
     * 从 UA 推导 navigator.appVersion。
     *
     * 官方注释里说得直白：appVersion 留空会回落到宿主值，
     * 于是 UA 说 Windows、appVersion 说 Linux，页面两个属性一读就露馅。
     * 形如 "5.0 (Windows)"。
     */
    private fun appVersionFrom(ua: String): String = when {
        ua.contains("Windows NT") -> "5.0 (Windows)"
        ua.contains("Macintosh") -> "5.0 (Macintosh)"
        ua.contains("Linux") || ua.contains("X11") -> "5.0 (X11)"
        ua.contains("Android") -> "5.0 (Android)"
        else -> "5.0"
    }
}

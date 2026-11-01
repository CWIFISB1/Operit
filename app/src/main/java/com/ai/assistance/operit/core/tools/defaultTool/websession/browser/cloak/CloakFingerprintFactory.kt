package com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak

import kotlin.math.abs
import kotlin.random.Random

/**
 * 指纹工厂：按种子生成一套**自洽**的 CloakFingerprint。
 *
 * [FINGERPRINT-ROTATION] 2026-11-01 新增。
 *
 * 背景：原先所有会话共用 `CloakFingerprint(profileId = sessionId)`，
 * fingerprintSeed 恒为 null，UA/屏幕/GPU/时区全用 data class 默认值。
 * 结果是每开一个会话指纹一模一样 —— 批量注册场景下等于当场自曝。
 *
 * 本工厂保证两件事：
 *
 * **一、确定性**：同一 seed 永远产出同一套配置。
 *   重新拉起引擎、进程重启、跨设备迁移都能复现同一身份，
 *   这对「同一个账号第二次登录不被风控」是必须的。
 *
 * **二、自洽性**：字段之间互相匹配，不能自相矛盾。
 *   · UA 的 Chrome 版本 ↔ 实际内核版本（146）
 *   · platform = Win32 ↔ oscpu = Windows NT 10.0 ↔ spoofPlatform = windows
 *   · 屏幕分辨率 ↔ GPU 型号（高分屏不配核显，核显不配 4K）
 *   · hardwareConcurrency ↔ deviceMemory ↔ GPU 档次
 *   · 时区 ↔ 语言（中文设备配 Asia/Shanghai，英文配 America/New_York）
 *
 * 注意：`--fingerprint=<seed>` 只驱动 Canvas / WebGL 图像 / 字体列表这些
 * **引擎内派生**项；UA、屏幕、时区、并发数是我们通过命令行与 CDP 显式设置的，
 * 所以两者必须一起随机，只改 seed 会得到「UA 是 A 机器、Canvas 是 B 机器」的割裂身份。
 */
internal object CloakFingerprintFactory {

    /** 与 proot 内实际 chromium 主版本保持一致，不可随意改。 */
    private const val CHROME_MAJOR = "146"

    /** 常见桌面分辨率（宽, 高）。刻意避开不常见的怪值。 */
    private val SCREENS = listOf(
        1920 to 1080,
        2560 to 1440,
        1680 to 1050,
        1600 to 900,
        1366 to 768,
        1440 to 900,
        3840 to 2160
    )

    /** GPU 型号库：按档次分组，保证和屏幕/核心数匹配。 */
    private val GPUS_HIGH = listOf(
        "NVIDIA GeForce RTX 4080",
        "NVIDIA GeForce RTX 4070 Ti",
        "NVIDIA GeForce RTX 3080",
        "AMD Radeon RX 7900 XTX",
        "AMD Radeon RX 6800 XT"
    )
    private val GPUS_MID = listOf(
        "NVIDIA GeForce RTX 3060",
        "NVIDIA GeForce GTX 1660 SUPER",
        "AMD Radeon RX 6600",
        "Intel Arc A750"
    )
    private val GPUS_LOW = listOf(
        "Intel UHD Graphics 630",
        "Intel HD Graphics 530",
        "AMD Radeon Vega 8"
    )

    /** 时区 ↔ 语言 组合。桌面指纹里这两项必须同源。 */
    private data class Locale(val tz: String, val lang: String, val primary: String)

    private val LOCALES = listOf(
        Locale("Asia/Shanghai", "zh-CN", "zh"),
        Locale("Asia/Taipei", "zh-TW", "zh"),
        Locale("America/New_York", "en-US", "en"),
        Locale("America/Los_Angeles", "en-US", "en"),
        Locale("Europe/London", "en-GB", "en"),
        Locale("Europe/Berlin", "de-DE", "de"),
        Locale("Asia/Tokyo", "ja-JP", "ja"),
        Locale("Europe/Paris", "fr-FR", "fr")
    )

    /**
     * 生成一套自洽指纹。
     *
     * @param seed 任意字符串种子。实际生效的是其稳定哈希。
     * @param profileId 独立 profile 目录名。**换身份必须换这个**，
     *   否则 cookie / localStorage / IndexedDB 全部沿用上一个账号，
     *   指纹换了也没用 —— 服务端照样能通过存储态把你串起来。
     */
    fun build(
        seed: String,
        profileId: String,
        proxyServer: String? = null,
        /**
         * [GEO-SYNC] 出口地理信息。传入则**强制覆盖**时区与语言。
         *
         * 为什么必须覆盖：种子只能决定 Canvas/WebGL/字体，
         * 它不知道这台"设备"的网络位置在哪。
         * 若 IP 在首尔而时区说巴黎，风控一眼看穿。
         * 时区与语言必须由出口 IP 反查得出，不能随机。
         */
        geo: CloakGeoResolver.GeoInfo? = null
    ): CloakFingerprint {
        val h = stableHash(seed)
        val r = Random(h)

        // ---------- 平台与架构 ----------
        // 只用 windows / macos 两种：Linux 桌面指纹在普通消费级风控里极稀缺，
        // 反而容易被单独标记。
        // macOS 不配 NVIDIA（苹果从 2016 起不用 N 卡）。
        val isMac = r.nextInt(100) < 15

        // ---------- 屏幕与 GPU（互相关联）----------
        val screenIdx = r.nextInt(SCREENS.size)
        val (sw, sh) = SCREENS[screenIdx]
        // 4K 只配独显高端；低分屏可以是核显
        val gpuTier = when {
            sw >= 2560 -> if (r.nextBoolean()) 0 else 1
            sw >= 1600 -> if (r.nextInt(100) < 40) 0 else 1
            else -> if (r.nextInt(100) < 50) 1 else 2
        }
        val gpuList = when (gpuTier) {
            0 -> GPUS_HIGH
            1 -> GPUS_MID
            else -> GPUS_LOW
        }
        val gpu = if (isMac) {
            // macOS 走 Apple Silicon / Intel Iris
            listOf("Apple M1 Pro", "Apple M2", "Intel Iris Plus Graphics")[abs(h) % 3]
        } else {
            gpuList[abs(h / 7) % gpuList.size]
        }
        val gpuVendor = when {
            isMac && gpu.startsWith("Apple") -> "Apple"
            gpu.startsWith("NVIDIA") -> "NVIDIA"
            gpu.startsWith("AMD") -> "AMD"
            else -> "Intel"
        }

        // ---------- CPU / 内存（跟 GPU 档次挂钩）----------
        val cores = when (gpuTier) {
            0 -> listOf(12, 16, 20, 24)[abs(h / 11) % 4]
            1 -> listOf(6, 8, 12)[abs(h / 13) % 3]
            else -> listOf(4, 6, 8)[abs(h / 17) % 3]
        }
        val mem = when (gpuTier) {
            0 -> listOf(16, 32)[abs(h / 19) % 2]
            1 -> listOf(8, 16)[abs(h / 23) % 2]
            else -> listOf(8, 16)[abs(h / 29) % 2]
        }

        // ---------- 时区 / 语言 ----------
        // 优先用出口 IP 反查结果；没有地理信息时才回落到种子随机。
        val loc: Locale
        if (geo != null && geo.ok && geo.timezone.isNotBlank()) {
            loc = Locale(
                tz = geo.timezone,
                lang = CloakGeoResolver.languageFor(geo.countryCode),
                primary = CloakGeoResolver.languageFor(geo.countryCode).substringBefore('-')
            )
        } else {
            loc = LOCALES[abs(h / 31) % LOCALES.size]
        }
        // macOS 的字符集声明与 Windows 不同，UA 必须整体替换
        val userAgent: String
        val platform: String
        val spoofPlatform: String
        if (isMac) {
            val macVer = listOf("10_15_7", "13_6_1", "14_2_1", "14_5")[abs(h / 37) % 4]
            userAgent =
                "Mozilla/5.0 (Macintosh; Intel Mac OS X $macVer) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/$CHROME_MAJOR.0.0.0 Safari/537.36"
            platform = "MacIntel"
            spoofPlatform = "macos"
        } else {
            userAgent =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/$CHROME_MAJOR.0.0.0 Safari/537.36"
            platform = "Win32"
            spoofPlatform = "windows"
        }

        // ---------- 视口 ----------
        // 视口比屏幕小，差值对应浏览器 chrome（地址栏/书签栏）。
        // 桌面窗口常见约 0.75~0.93 屏高，宽度取 0.62~0.78。
        val vw = (sw * (62 + abs(h / 41) % 17) / 100).coerceAtLeast(1024)
        val vh = (sh * (75 + abs(h / 43) % 19) / 100).coerceAtLeast(640)

        return CloakFingerprint(
            profileId = profileId,
            platform = platform,
            userAgent = userAgent,
            timezone = loc.tz,
            language = loc.lang,
            viewportWidth = vw,
            viewportHeight = vh,
            deviceScale = 1.0,
            mobile = false,
            // 关键：Canvas / WebGL 图像 / 字体列表由这个种子派生
            fingerprintSeed = abs(h % 900000) + 100000,
            proxyServer = proxyServer,
            spoofPlatform = spoofPlatform,
            gpuVendor = gpuVendor,
            hardwareConcurrency = cores,
            deviceMemory = mem,
            screenWidth = sw,
            screenHeight = sh,
            // 已经用 geo 手工对齐了时区/语言，不需要引擎再做 geoip 猜测。
            // 引擎的 geoip 依赖它自己能否访问外部服务，行为不可控；
            // 我们这边是确定性的，所以关掉它避免两套逻辑打架。
            geoip = geo == null
        )
    }

    /** 生成一个全新的随机种子（用于「每次注册换一套」）。 */
    fun randomSeed(): String {
        val r = Random(System.nanoTime() xor System.currentTimeMillis())
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString(20) { repeat(20) { append(chars[r.nextInt(chars.length)]) } }
    }

    /** 由种子派生出稳定的 profile 目录名，避免复用上一个账号的存储态。 */
    fun profileIdFor(seed: String): String = "p${abs(stableHash(seed))}"

    /** 跨平台稳定哈希（Java String.hashCode 语义，避免平台差异）。 */
    private fun stableHash(s: String): Int {
        var h = 0
        for (c in s) h = 31 * h + c.code
        return h
    }
}
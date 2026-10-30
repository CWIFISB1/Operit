package com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak

/** 浏览器引擎类型。 */
enum class BrowserEngine {
    /** Android 系统 WebView（默认，原生流畅）。 */
    WEBVIEW,
    /** proot 内 CloakBrowser Chromium（反检测 / 指纹伪装）。 */
    CLOAK,
    /**
     * proot 内 Camoufox（Firefox 158 源码级反检测）。
     *
     * 与 CLOAK 同为「外部二进制 + 远程协议」，差异：
     *  - 控制协议是 WebDriver BiDi + Marionette，不是 CDP
     *  - 引擎是 Gecko，不发送 Client Hints，少一整类检测面
     *  - 指纹经 CAMOU_CONFIG 在 C++ 层产生，无 JS 改写痕迹
     */
    CAMOUFOX;

    companion object {
        fun fromName(raw: String?): BrowserEngine =
            when (raw?.trim()?.lowercase()) {
                "cloak", "chromium", "cloakbrowser" -> CLOAK
                "camoufox", "firefox", "gecko" -> CAMOUFOX
                else -> WEBVIEW
            }
    }
}

/** 指纹配置，映射到 CloakBrowser 启动参数与 CDP Emulation。 */
data class CloakFingerprint(
    val profileId: String = "default",
    val platform: String = "Win32",
    val userAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
    val timezone: String = "Asia/Shanghai",
    val language: String = "zh-CN",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val viewportWidth: Int = 1280,
    val viewportHeight: Int = 800,
    val deviceScale: Double = 1.0,
    val mobile: Boolean = false,
    /** 传入 --fingerprint 种子，CloakBrowser 会据此派生 Canvas/WebGL/字体指纹。 */
    val fingerprintSeed: Int? = null,
    val proxyServer: String? = null,
    /** 伪装平台：windows / macos / linux。 */
    val spoofPlatform: String = "windows",
    /** GPU 厂商：NVIDIA / Intel / AMD。 */
    val gpuVendor: String? = null,
    /** navigator.hardwareConcurrency。 */
    val hardwareConcurrency: Int? = null,
    /** navigator.deviceMemory GB。 */
    val deviceMemory: Int? = null,
    /** 屏幕宽度。 */
    val screenWidth: Int? = null,
    /** 屏幕高度。 */
    val screenHeight: Int? = null,
    /** WebRTC IP 伪装，auto 跟随代理出口。 */
    val webrtcIp: String = "auto",
    /** 按代理出口 IP 匹配时区与语言。 */
    val geoip: Boolean = true,
    /** 拟人化交互。 */
    val humanize: Boolean = true,
    /** 拟人化预设 default/careful/fast。 */
    val humanPreset: String = "default"
) {
    fun toCliArgs(): List<String> = buildList {
        add("--user-agent=$userAgent")
        add("--lang=$language")
        add("--window-size=$viewportWidth,$viewportHeight")
        if (mobile) add("--fingerprint-mobile=true")
        fingerprintSeed?.let { add("--fingerprint=$it") }
        proxyServer?.let { add("--proxy-server=$it") }
        if (spoofPlatform.isNotBlank()) add("--fingerprint-platform=$spoofPlatform")
        gpuVendor?.let { if (it.isNotBlank()) add("--fingerprint-gpu-vendor=$it") }
        hardwareConcurrency?.let { if (it > 0) add("--fingerprint-hardware-concurrency=$it") }
        deviceMemory?.let { if (it > 0) add("--fingerprint-device-memory=$it") }
        screenWidth?.let { if (it > 0) add("--fingerprint-screen-width=$it") }
        screenHeight?.let { if (it > 0) add("--fingerprint-screen-height=$it") }
        if (webrtcIp.isNotBlank()) add("--fingerprint-webrtc-ip=$webrtcIp")
        if (humanize) {
            add("--humanize=true")
            if (humanPreset.isNotBlank()) add("--human-preset=$humanPreset")
        }
        add("--disable-blink-features=AutomationControlled")
    }
}

/** CloakBrowser 运行状态快照。 */
data class CloakStatus(
    val running: Boolean = false,
    val port: Int = 0,
    val pid: Int = -1,
    val profileId: String = "",
    val version: String = "",
    val lastError: String? = null
)

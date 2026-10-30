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
    /**
     * WebRTC IP 伪装值。**默认空 = 完全不传 `--fingerprint-webrtc-ip`**。
     *
     * 实测（2026-10-30，真机 Cloak 146，each 跑 2~3 次复现）：
     *   传 auto / disabled / block / false / none / deny / off → 全部 total=2 host=1 srflx=1
     *   完全省略该参数                                    → total=0 host=0 srflx=0
     *
     * 即：**这个参数一旦出现，不论取值，都会自己造出一个假 ICE 候选**，
     * 风控只要看到 `typ srflx` 就判定「WebRTC 可达」。最干净的做法是彻底不传。
     * 需要指定具体伪装 IP 时才显式赋非空值（此时接受候选存在这一代价）。
     */
    val webrtcIp: String = "",
    /**
     * WebRTC 存活策略。默认 [WEBRTC_DISABLED]。
     *
     * 实测（2026-10-30 真机）：只加 `--fingerprint-webrtc-ip=auto` 时，
     * 页面仍能拿到 `typ srflx` 候选 —— IP 值是 "auto" 占位符，但**候选对象本身存在**，
     * 风控据此判定「WebRTC 可达」，是 Cloak 免费版最明显的破绽。
     *
     * 加 `--webrtc-ip-handling-policy=disable_non_proxied_udp` 后实测：
     *   total=0 host=0 srflx=0 relay=0
     * 候选完全为空，真实 IP 彻底不可探测。
     *
     * 取值：
     *   disabled                默认。彻底关掉候选，最安全
     *   default                 不干预（旧行为，会有 srflx）
     *   disable_non_proxied_udp 只关非代理 UDP，走代理时仍可用（需要真实代理才有意义）
     */
    val webrtcMode: String = WEBRTC_DISABLED,
    /** 按代理出口 IP 匹配时区与语言。 */
    val geoip: Boolean = true,
    /** 拟人化交互。 */
    val humanize: Boolean = true,
    /** 拟人化预设 default/careful/fast。 */
    val humanPreset: String = "default"
) {
    companion object {
        /** 彻底关闭 WebRTC 候选（默认）。 */
        const val WEBRTC_DISABLED = "disabled"
    }

    fun toCliArgs(): List<String> = buildList {
        add("--user-agent=$userAgent")
        add("--lang=$language")
        add("--window-size=$viewportWidth,$viewportHeight")
        // 关于 screenX/screenY（2026-10-30 实测纠偏）：
        //   CDP 的 Emulation.setDeviceMetricsOverride 带 positionX/positionY 时
        //   调用成功但 window.screenX/Y 纹丝不动 —— 它确实改不了窗口位置。
        //   但这**不构成指纹缺陷**：实测 availLeft/availTop 恒为 0，screen 与窗口尺寸
        //   关系正常，screenX/Y 只是桌面显示器上的居中窗口坐标，真实用户窗口本就可任意摆放。
        //   若强行注入 JS 改写 screenX getter，会破坏当前 toString 8/8 NATIVE 的原生性优势，
        //   代价远大于收益，故此处不做任何覆盖。
        add("--window-position=0,0")
        if (mobile) add("--fingerprint-mobile=true")
        fingerprintSeed?.let { add("--fingerprint=$it") }
        proxyServer?.let { add("--proxy-server=$it") }
        if (spoofPlatform.isNotBlank()) add("--fingerprint-platform=$spoofPlatform")
        gpuVendor?.let { if (it.isNotBlank()) add("--fingerprint-gpu-vendor=$it") }
        hardwareConcurrency?.let { if (it > 0) add("--fingerprint-hardware-concurrency=$it") }
        deviceMemory?.let { if (it > 0) add("--fingerprint-device-memory=$it") }
        screenWidth?.let { if (it > 0) add("--fingerprint-screen-width=$it") }
        screenHeight?.let { if (it > 0) add("--fingerprint-screen-height=$it") }
        // 只有显式指定了具体 IP 才传：该参数一旦出现就会造出假候选（见 webrtcIp 注释）
        if (webrtcIp.isNotBlank()) add("--fingerprint-webrtc-ip=$webrtcIp")
        // 命令行真正生效的是 force-webrtc-ip-handling-policy；
        // 不带 force 的 --webrtc-ip-handling-policy 是偏好项名，会被静默忽略。
        when (webrtcMode.lowercase()) {
            WEBRTC_DISABLED, "disable_non_proxied_udp" ->
                add("--force-webrtc-ip-handling-policy=disable_non_proxied_udp")
            "default" -> { /* 不干预 */ }
            else -> add("--force-webrtc-ip-handling-policy=disable_non_proxied_udp")
        }
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

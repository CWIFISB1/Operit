package com.ai.assistance.operit.core.tools.defaultTool.websession.browser.camoufox

/**
 * Camoufox 引擎的类型、指纹与状态定义。
 *
 * Camoufox 是 Firefox 158 的源码级反检测构建（46 处 C++ 补丁），
 * 与 CloakBrowser 同为「外部二进制 + 远程协议」形态，但协议不同：
 *   CloakBrowser → Chromium CDP（/json/version + WebSocket）
 *   Camoufox     → WebDriver BiDi（ws://host:port/session）+ Marionette
 *
 * 指纹注入方式也不同：Camoufox 通过 CAMOU_CONFIG_<n> 环境变量分块传入
 * JSON 配置，由 C++ 层 MaskConfig 在产生指纹的位置直接返回伪装值，
 * 页面侧无法通过 toString 探测到 JS 层改写痕迹。
 */

/** Camoufox 引擎运行状态快照。 */
data class CamoufoxStatus(
    val running: Boolean = false,
    val bidiPort: Int = 0,
    val marionettePort: Int = 0,
    val pid: Int = -1,
    val profileId: String = "",
    val version: String = "",
    val os: String = "",
    val lastError: String? = null
)

/**
 * Camoufox 指纹 persona。
 *
 * 字段与官方 fingerprint-presets.json 的一级分组对应：
 *   navigator.* / screen.* / webGl:* / audio:seed / fonts / voices / timezone
 * 由 [CamoufoxConfigBuilder] 转换成 CAMOU_CONFIG 用的扁平 JSON。
 */
data class CamoufoxFingerprint(
    val profileId: String = "default",
    /** persona 操作系统：windows / macos / linux。决定 navigator.platform 与 oscpu。 */
    val os: String = "windows",
    /** 完整 UA。留空时由 [CamoufoxConfigBuilder] 按 os 生成。 */
    val userAgent: String = "",
    /** navigator.hardwareConcurrency。0 表示不设置（跟随预设）。 */
    val hardwareConcurrency: Int = 0,
    /** navigator.maxTouchPoints。 */
    val maxTouchPoints: Int = 0,
    /**
     * 屏幕尺寸。
     *
     * 2026-10-31 修复：默认 0 表示不写 CAMOU_CONFIG，Camoufox 回落宿主值。
     * 实测宿主给的是 1920x1080 但 screen.availWidth/availHeight 未设置，
     * 页面读到 availHeight == height（无任务栏），Windows 桌面几乎不可能这样。
     * 默认取官方 windows 预设的真实采样：1920x1080，可用区 1920x1032。
     */
    val screenWidth: Int = 1920,
    val screenHeight: Int = 1080,
    val screenColorDepth: Int = 24,
    /** 可用区域，通常 avWidth=width，avHeight=height-任务栏。 */
    val screenAvailWidth: Int = 1920,
    val screenAvailHeight: Int = 1032,
    /**
     * WebGL 伪装厂商。
     *
     * 2026-10-31 修复：默认空串时 CAMOU_CONFIG 不写 webGl:vendor，
     * Camoufox 回落宿主值 —— 实测拿到 "Mesa"，这是 Linux 软件渲染的
     * 独有标识，比 llvmpipe 还刺眼。默认给一段真实 Windows + Intel 设备的组合。
     */
    val webglVendor: String = "Google Inc. (Intel)",
    /**
     * WebGL 伪装渲染器。
     *
     * 取 camoufox 官方 fingerprint-presets.json windows 组的真实采样值：
     *   "ANGLE (Intel, Intel(R) HD Graphics 400 Direct3D11 vs_5_0 ps_5_0), or similar"
     * 去掉尾部 ", or similar"（那是官方用来做渲染器洗牌的占位后缀，
     * 直接暴露在 getParameter 里反而不自然）。
     */
    val webglRenderer: String =
        "ANGLE (Intel, Intel(R) HD Graphics 400 Direct3D11 vs_5_0 ps_5_0)",
    /** IANA 时区，如 America/New_York。 */
    val timezone: String = "America/New_York",
    /**
     * 语言列表，第一个为主语言。
     *
     * 2026-10-31 修复配套：CamoufoxConfigBuilder 现在会把 languages[0]
     * 拆成 locale:language / locale:region / locale:script 写进 CAMOU_CONFIG，
     * 否则 navigator.languages 一直是宿主默认 en-US,en。
     */
    val languages: List<String> = listOf("en-US", "en"),
    /** 字体列表。留空则用预设。 */
    val fonts: List<String> = emptyList(),
    /** 语音列表（JSON 对象的字符串形式，保留原始结构）。 */
    val voicesJson: String = "",
    /** Canvas/WebGL 噪声种子。null 表示每次随机。 */
    val seed: Int? = null,
    /** 代理服务器，形如 http://user:pass@host:port。 */
    val proxyServer: String? = null,
    /** 拟人化交互（滚动曲线、输入节奏）。 */
    val humanize: Boolean = true,
    /** 是否阻止 WebRTC 泄露真实 IP。 */
    val blockWebRtc: Boolean = true
) {
    /** 由 os 推导 navigator.platform。 */
    fun derivedPlatform(): String = when (os.lowercase()) {
        "macos", "mac" -> "MacIntel"
        "linux" -> "Linux x86_64"
        else -> "Win32"
    }

    /** 由 os 推导 navigator.oscpu。 */
    fun derivedOscpu(): String = when (os.lowercase()) {
        "macos", "mac" -> "Intel Mac OS X 10.15"
        "linux" -> "Linux x86_64"
        else -> "Windows NT 10.0; Win64; x64"
    }

    /** 由 os 推导 UA。Firefox 版本固定为 147（与预设一致）。 */
    fun derivedUserAgent(): String = when (os.lowercase()) {
        "macos", "mac" ->
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:147.0) Gecko/20100101 Firefox/147.0"
        "linux" ->
            "Mozilla/5.0 (X11; Linux x86_64; rv:147.0) Gecko/20100101 Firefox/147.0"
        else ->
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0"
    }
}

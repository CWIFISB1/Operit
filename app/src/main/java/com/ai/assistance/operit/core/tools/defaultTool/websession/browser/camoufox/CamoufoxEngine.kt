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
    /** 屏幕尺寸。0 表示不设置。 */
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val screenColorDepth: Int = 24,
    /** 可用区域，通常 avWidth=width，avHeight=height-任务栏。 */
    val screenAvailWidth: Int = 0,
    val screenAvailHeight: Int = 0,
    /** WebGL 伪装厂商，如 "Google Inc. (Intel)"。 */
    val webglVendor: String = "",
    /** WebGL 伪装渲染器，如 "ANGLE (Intel, Intel(R) HD Graphics 400 Direct3D11 vs_5_0 ps_5_0)"。 */
    val webglRenderer: String = "",
    /** IANA 时区，如 America/New_York。 */
    val timezone: String = "America/New_York",
    /** 语言列表，第一个为主语言。 */
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

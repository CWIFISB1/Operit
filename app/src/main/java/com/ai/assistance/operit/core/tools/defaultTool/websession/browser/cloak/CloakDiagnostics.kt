package com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONObject

/**
 * Cloak 一站式诊断。
 *
 * [CLOAK-DIAG] 2026-11-02 新增。
 *
 * ==================================================================
 * 为什么需要这个类
 * ==================================================================
 * 排查「指纹能不能换」「为什么白屏」「Cookie 有没有丢」这几个问题，
 * 过去要翻十几个源文件 + 跑七八条 shell 命令才能拼出结论，
 * 中间还要反复验证「源码改了但 APK 里是旧的」这类缓存陷阱。
 *
 * 这个类把全部判据收敛成一次调用，输出一段人能直接读的报告。
 * 判据全部来自**运行时实测**，不是读源码推断 —— 避免再次被构建缓存骗。
 *
 * 输出维度：
 *   · 引擎：running / port / pid / version / Xvfb / headless
 *   · 身份：当前槽位名 / profileId / seed / Cookie 目录体积
 *   · 指纹：UA / platform / 屏幕 / GPU / 时区 / 语言 / 核数 / 内存 / Canvas
 *   · 泄漏：WebRTC 候选（srflx 存在即泄漏）
 *   · 槽位表：全部身份清单
 */
internal object CloakDiagnostics {

    private const val TAG = "CloakDiag"

    /** 指纹实测脚本：一次拿全所有可观测项。 */
    private const val PROBE_JS = """
(function(){
  const o={};
  o.ua = navigator.userAgent;
  o.platform = navigator.platform;
  o.vendor = navigator.vendor;
  o.webdriver = navigator.webdriver;
  o.cores = navigator.hardwareConcurrency;
  o.mem = navigator.deviceMemory || null;
  o.tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
  o.langs = (navigator.languages||[]).join(',');
  o.screen = screen.width + 'x' + screen.height;
  o.avail = screen.availWidth + 'x' + screen.availHeight;
  o.dpr = window.devicePixelRatio;
  o.viewport = innerWidth + 'x' + innerHeight;
  try {
    const c = document.createElement('canvas');
    c.width = 280; c.height = 60;
    const g = c.getContext('2d');
    g.textBaseline='top'; g.font='16px Arial';
    g.fillStyle='#f60'; g.fillRect(0,0,140,30);
    g.fillStyle='#069'; g.fillText('Operit fp probe', 2, 12);
    o.canvas = c.toDataURL();
  } catch(e) { o.canvas = 'ERR'; }
  try {
    const w = document.createElement('canvas');
    const gl = w.getContext('webgl');
    const d = gl.getExtension('WEBGL_debug_renderer_info');
    o.glVendor = gl.getParameter(d.UNMASKED_VENDOR_WEBGL);
    o.glRenderer = gl.getParameter(d.UNMASKED_RENDERER_WEBGL);
  } catch(e) { o.glVendor='ERR'; o.glRenderer='ERR'; }
  return JSON.stringify(o);
})()
"""

    /** WebRTC 泄漏探测。 */
    private const val WEBRTC_JS = """
new Promise((res)=>{
  const o={total:0,host:0,srflx:0,relay:0};
  let pc;
  try { pc = new RTCPeerConnection({iceServers:[{urls:'stun:stun.l.google.com:19302'}]}); }
  catch(e){ res(JSON.stringify({err:String(e)})); return; }
  const done = ()=>{ try{pc.close()}catch(x){}; setTimeout(()=>res(JSON.stringify(o)), 300); };
  pc.onicecandidate = (e)=>{
    if(!e.candidate){ done(); return; }
    const c = e.candidate.candidate;
    o.total++;
    if(c.indexOf('typ host')>=0) o.host++;
    if(c.indexOf('typ srflx')>=0) o.srflx++;
    if(c.indexOf('typ relay')>=0) o.relay++;
  };
  try { pc.createDataChannel('x'); pc.createOffer().then(x=>pc.setLocalDescription(x)); }
  catch(e){ res(JSON.stringify({err:String(e)})); }
})
"""

    /**
     * 全量诊断。
     *
     * @param sessionId 当前会话 id，可为空（此时只报全局状态）
     */
    fun fullReport(context: Context, sessionId: String?): String {
        val sb = StringBuilder()

        // ---------- 1. 引擎状态 ----------
        sb.append("===== 1. 引擎 =====\n")
        val st = CloakLauncher.status()
        sb.append("running   : ").append(st.running).append('\n')
        sb.append("port      : ").append(st.port).append('\n')
        sb.append("pid       : ").append(st.pid).append('\n')
        sb.append("version   : ").append(st.version.ifBlank { "-" }).append('\n')
        sb.append("environment: ").append(CloakLauncher.environmentSummary(context)).append('\n')
        if (!st.lastError.isNullOrBlank()) sb.append("lastError : ").append(st.lastError).append('\n')

        // ---------- 2. 身份槽位 ----------
        sb.append("\n===== 2. 身份 =====\n")
        val boundSlot = sessionId?.let { CloakBrowserTools.slotOf(it) }
        sb.append("会话绑定槽位: ").append(boundSlot ?: "(未绑定)").append('\n')
        val slots = CloakSlotStore.load(context)
        sb.append("槽位总数    : ").append(slots.size).append('\n')

        val active = sessionId?.let { CloakBrowserTools.fingerprintOf(it) }
        if (active != null) {
            sb.append("当前 profileId: ").append(active.profileId).append('\n')
            sb.append("当前 seed     : ").append(active.fingerprintSeed ?: "(无)").append('\n')
            val dir = "/root/.operit_cloak/profiles/${active.profileId}"
            val size = CloakLauncher.prootPublic(context, "du -sh $dir 2>/dev/null | cut -f1", 15_000L)
            val ck = CloakLauncher.prootPublic(
                context, "stat -c%s $dir/Default/Cookies 2>/dev/null || echo 0", 10_000L
            )
            sb.append("Cookie 目录   : ").append(dir).append('\n')
            sb.append("目录体积      : ").append(size?.trim().orEmpty().ifBlank { "不存在" }).append('\n')
            sb.append("Cookies 大小  : ").append(ck?.trim().orEmpty().ifBlank { "0" }).append(" bytes\n")
        } else {
            sb.append("当前指纹      : (会话尚未绑定，导航时自动取 default 槽位)\n")
        }

        // 代理与出口
        val curSlotName = sessionId?.let { CloakBrowserTools.slotOf(it) }
            ?: CloakSlotStore.DEFAULT_SLOT
        val curSlot = slots.firstOrNull { it.name == curSlotName }
        if (curSlot != null) {
            sb.append("槽位代理      : ").append(curSlot.proxy.ifBlank { "(直连，用设备真实出口)" }).append('\n')
            val geo = CloakGeoResolver.resolve(curSlot.proxy.ifBlank { null })
            if (geo.ok) {
                sb.append("出口 IP       : ").append(geo.ip).append('\n')
                sb.append("出口归属      : ").append(geo.country).append(' ').append(geo.city)
                    .append(" / ").append(geo.timezone).append('\n')
                sb.append("出口 ISP      : ").append(geo.isp).append('\n')
                sb.append("IP 质量       : ").append(geo.riskNote()).append('\n')
            } else {
                sb.append("出口 IP       : 探测失败（").append(geo.error).append("）\n")
            }
        }

        // ---------- 3. 指纹配置 ----------
        sb.append("\n===== 3. 指纹配置 =====\n")
        val fp = active ?: sessionId?.let { CloakBrowserTools.fingerprintOf(it) }
        if (fp != null) {
            sb.append("spoofPlatform : ").append(fp.spoofPlatform).append('\n')
            sb.append("platform      : ").append(fp.platform).append('\n')
            sb.append("UA            : ").append(fp.userAgent).append('\n')
            sb.append("屏幕          : ").append(fp.screenWidth ?: 0).append('x').append(fp.screenHeight ?: 0).append('\n')
            sb.append("视口          : ").append(fp.viewportWidth).append('x').append(fp.viewportHeight).append('\n')
            sb.append("时区          : ").append(fp.timezone).append('\n')
            sb.append("语言          : ").append(fp.language).append('\n')
            sb.append("GPU 厂商      : ").append(fp.gpuVendor ?: "(未设)").append('\n')
            sb.append("CPU 核数      : ").append(fp.hardwareConcurrency ?: 0).append('\n')
            sb.append("内存 GB       : ").append(fp.deviceMemory ?: 0).append('\n')
            sb.append("WebRTC 模式   : ").append(fp.webrtcMode)
            if (fp.webrtcIp.isNotBlank()) sb.append(" (ip=").append(fp.webrtcIp).append(')')
            sb.append('\n')
        } else {
            sb.append("(无)\n")
        }

        // ---------- 4. 槽位清单 ----------
        sb.append("\n===== 4. 槽位清单 =====\n")
        sb.append(CloakSlotStore.describe(context)).append('\n')

        // ---------- 5. 自洽性检查 ----------
        sb.append("\n===== 5. 自洽性检查 =====\n")
        if (fp != null) {
            val issues = mutableListOf<String>()

            // UA 与 platform 必须同源
            val uaIsMac = fp.userAgent.contains("Macintosh")
            val uaIsWin = fp.userAgent.contains("Windows NT")
            val platIsMac = fp.platform == "MacIntel"
            val platIsWin = fp.platform == "Win32"
            if (uaIsMac && !platIsMac) issues.add("UA 声明 macOS 但 platform=${fp.platform}")
            if (uaIsWin && !platIsWin) issues.add("UA 声明 Windows 但 platform=${fp.platform}")
            if (fp.spoofPlatform == "macos" && uaIsWin) issues.add("spoofPlatform=macos 但 UA 是 Windows")
            if (fp.spoofPlatform == "windows" && uaIsMac) issues.add("spoofPlatform=windows 但 UA 是 macOS")

            // GPU 与平台匹配
            val gpu = fp.gpuVendor ?: ""
            if (fp.spoofPlatform == "macos" && gpu == "NVIDIA") {
                issues.add("macOS 身份却配 NVIDIA 显卡（苹果 2016 起不用 N 卡）")
            }
            // 屏幕与视口
            val sw = fp.screenWidth ?: 0
            if (sw > 0 && fp.viewportWidth > sw) issues.add("视口宽度超过屏幕宽度")
            // 时区与语言
            if (fp.language.startsWith("zh") && fp.timezone.startsWith("America")) {
                issues.add("中文语言配美洲时区（可疑）")
            }
            if (fp.timezone == "UTC") issues.add("时区为 UTC（真实用户极少，高权重破绽）")

            // [GEO-SYNC] 出口 IP 与浏览器时区必须同源 —— 这是最廉价也最致命的判据
            val boundSlotName = sessionId?.let { CloakBrowserTools.slotOf(it) }
            val slotObj = boundSlotName?.let { name ->
                slots.firstOrNull { it.name == name }
            }
            if (slotObj != null) {
                val geo = CloakGeoResolver.resolve(slotObj.proxy.ifBlank { null })
                if (geo.ok) {
                    if (geo.timezone.isNotBlank() && geo.timezone != fp.timezone) {
                        issues.add(
                            "IP 时区(${geo.timezone}) 与浏览器时区(${fp.timezone}) 不一致 —— " +
                                "风控最先查这条"
                        )
                    }
                    if (geo.isHosting) {
                        issues.add("出口 IP 属机房/云厂商（${geo.isp}），住宅代理才是正解")
                    }
                    if (geo.isProxy) {
                        issues.add("出口 IP 已被标记为代理，多数风控直接拒绝")
                    }
                } else {
                    issues.add("出口地理探测失败：${geo.error}（无法验证 IP 与时区是否同源）")
                }
            }

            if (issues.isEmpty()) {
                sb.append("✅ 未发现矛盾\n")
            } else {
                issues.forEach { sb.append("⚠️ ").append(it).append('\n') }
            }
        } else {
            sb.append("(无指纹可检)\n")
        }

        return sb.toString().trimEnd()
    }

    /**
     * 实测指纹（要求引擎已启动、有活动会话）。
     *
     * 与 fullReport 的区别：fullReport 读的是**我们打算设的配置**，
     * probe 读的是**页面里真实看到的**。两者不一致就说明设置没生效。
     */
    fun liveProbe(sessionId: String?): String {
        if (sessionId.isNullOrBlank()) return "无活动会话，先 browser_navigate 建一个"
        val client = CloakBrowserTools.clientOf(sessionId)
            ?: return "该会话无 CDP 客户端（引擎未启动或非 Cloak 引擎）"
        val sb = StringBuilder()

        sb.append("===== 实测指纹（页面内真实值）=====\n")
        val raw: String? = runCatching { client.evaluate(PROBE_JS)?.toString() }.getOrNull()
        if (raw.isNullOrBlank()) {
            sb.append("evaluate 失败\n")
        } else {
            runCatching {
                val o = JSONObject(raw)
                sb.append("UA          : ").append(o.optString("ua")).append('\n')
                sb.append("platform    : ").append(o.optString("platform")).append('\n')
                sb.append("vendor      : ").append(o.optString("vendor")).append('\n')
                sb.append("webdriver   : ").append(o.optBoolean("webdriver")).append('\n')
                sb.append("cores       : ").append(o.optInt("cores")).append('\n')
                sb.append("deviceMemory: ").append(o.opt("mem") ?: "-").append('\n')
                sb.append("timezone    : ").append(o.optString("tz")).append('\n')
                sb.append("languages   : ").append(o.optString("langs")).append('\n')
                sb.append("screen      : ").append(o.optString("screen")).append('\n')
                sb.append("availScreen : ").append(o.optString("avail")).append('\n')
                sb.append("viewport    : ").append(o.optString("viewport")).append('\n')
                sb.append("dpr         : ").append(o.optDouble("dpr")).append('\n')
                sb.append("WebGL 厂商  : ").append(o.optString("glVendor")).append('\n')
                sb.append("WebGL 渲染器: ").append(o.optString("glRenderer")).append('\n')
                val cv = o.optString("canvas")
                sb.append("Canvas 长度 : ").append(cv.length).append('\n')
                sb.append("Canvas 指纹 : ").append(cv.takeLast(48)).append('\n')
            }.onFailure { sb.append("解析失败: ").append(it.message).append('\n') }
        }

        sb.append("\n===== WebRTC 泄漏 =====\n")
        val wr: String? = runCatching { client.evaluate(WEBRTC_JS)?.toString() }.getOrNull()
        if (wr.isNullOrBlank()) {
            sb.append("探测失败\n")
        } else {
            runCatching {
                val o = JSONObject(wr)
                val srflx = o.optInt("srflx")
                val host = o.optInt("host")
                sb.append("候选总数: ").append(o.optInt("total")).append('\n')
                sb.append("host    : ").append(host).append('\n')
                sb.append("srflx   : ").append(srflx).append('\n')
                sb.append("relay   : ").append(o.optInt("relay")).append('\n')
                sb.append(
                    if (srflx == 0 && host == 0) "✅ 无泄漏\n"
                    else "⚠️ 存在候选，可能泄漏内网/公网 IP\n"
                )
            }.onFailure { sb.append("解析失败: ").append(it.message).append('\n') }
        }

        return sb.toString().trimEnd()
    }

    /** 只列槽位。 */
    fun slots(context: Context): String = CloakSlotStore.describe(context)

    /**
     * 切换到指定槽位并应用到会话。
     */
    fun useSlot(context: Context, sessionId: String?, slotName: String): String {
        if (sessionId.isNullOrBlank()) return "无活动会话，先 browser_navigate 建一个"
        val slot = CloakSlotStore.find(context, slotName)
            ?: return "槽位 $slotName 不存在。用 action=slots 查看，或 action=new 新建。"
        CloakBrowserTools.bindSlot(sessionId, slot.name)
        val geo = if (slot.proxy.isBlank()) null else CloakGeoResolver.resolve(slot.proxy)
        val fp = CloakFingerprintFactory.build(
            seed = slot.seed,
            profileId = slot.profileId,
            proxyServer = slot.proxy.ifBlank { null },
            geo = geo
        )
        CloakBrowserTools.setFingerprint(sessionId, fp)
        return buildString {
            append("已切换到槽位 ").append(slot.name).append('\n')
            append("profileId: ").append(slot.profileId).append('\n')
            append("seed     : ").append(slot.seed).append('\n')
            append("Cookie 目录: /root/.operit_cloak/profiles/").append(slot.profileId).append('\n')
            append("\n提示：切换槽位后需要重新导航，引擎才会用新身份重启。")
        }
    }

    /** 新建槽位。 */
    fun newSlot(context: Context, name: String? = null, proxy: String = ""): String {
        val slot = CloakSlotStore.createSlot(context, name, proxy)
        val geo = CloakGeoResolver.resolve(slot.proxy.ifBlank { null })
        return buildString {
            append("已新建槽位 ").append(slot.name).append('\n')
            append("profileId: ").append(slot.profileId).append('\n')
            append("seed     : ").append(slot.seed).append('\n')
            append("Cookie   : 全新空白（该身份尚未产生任何登录态）\n")
            append("代理     : ").append(slot.proxy.ifBlank { "(直连)" }).append('\n')
            if (geo.ok) {
                append("出口 IP  : ").append(geo.ip).append('\n')
                append("出口归属 : ").append(geo.country).append(' ').append(geo.city)
                    .append(" / ").append(geo.timezone).append('\n')
                append("出口 ISP : ").append(geo.isp).append('\n')
                append("IP 质量  : ").append(geo.riskNote()).append('\n')
                append("指纹时区 : ").append(geo.timezone)
                    .append("  语言: ").append(CloakGeoResolver.languageFor(geo.countryCode))
                    .append("（已与出口 IP 对齐）\n")
            } else {
                append("出口探测 : 失败（").append(geo.error)
                    .append("）—— 指纹时区将回落到种子随机值，可能与 IP 矛盾\n")
            }
            append("\n用 action=use slot=").append(slot.name).append(" 切换过去。")
        }
    }

    /**
     * 独立测试代理质量，不创建槽位、不改任何状态。
     *
     * 主人说代理随时换，所以需要一个"先验货再绑定"的入口：
     * 只报出口 IP / 归属 / ISP / 是否机房 / 是否已被标记，让主人决定要不要用。
     */
    fun proxyTest(proxy: String): String {
        val geo = CloakGeoResolver.resolve(proxy)
        return buildString {
            append("===== 代理检测 =====\n")
            append("代理    : ").append(proxy).append('\n')
            if (!geo.ok) {
                append("结果    : ❌ 不可用 —— ").append(geo.error).append('\n')
                append("\n可能原因：地址端口错 / 需要认证 / 代理已失效 / 网络不通")
                return@buildString
            }
            append("出口 IP : ").append(geo.ip).append('\n')
            append("国家    : ").append(geo.country).append(" (").append(geo.countryCode).append(")\n")
            append("城市    : ").append(geo.city).append('\n')
            append("时区    : ").append(geo.timezone).append('\n')
            append("ISP     : ").append(geo.isp).append('\n')
            append("机房 IP : ").append(if (geo.isHosting) "是 ⚠️" else "否").append('\n')
            append("代理标记: ").append(if (geo.isProxy) "是 ⚠️" else "否").append('\n')
            append("移动网络: ").append(if (geo.isMobile) "是" else "否").append('\n')
            append("\n质量判定: ").append(geo.riskNote()).append('\n')
            append("\n绑定后指纹将自动设为：\n")
            append("  时区 ").append(geo.timezone).append('\n')
            append("  语言 ").append(CloakGeoResolver.languageFor(geo.countryCode)).append('\n')
            append("\n用法：browser_switch_engine engine=cloak fingerprint=slot=new proxy=").append(proxy)
        }
    }

    // ================================================================
    // [NODE-SUB] 订阅导入与节点启停
    // ================================================================

    /**
     * 导入订阅：下载 → 解析 → 落盘。
     *
     * 支持三种形态：
     *   1. base64 节点列表（绝大多数机场）
     *   2. 明文 URI 列表
     *   3. Clash YAML
     */
    fun subImport(url: String): String {
        val body = runCatching { httpGet(url) }.getOrNull()
            ?: return "订阅下载失败：$url\n检查地址是否失效、是否需要网络代理"
        if (body.isBlank()) return "订阅内容为空：$url"

        // 有的订阅需要 UA，有的返回 HTML 跳转页
        if (body.trimStart().startsWith("<!DOCTYPE", true) ||
            body.trimStart().startsWith("<html", true)
        ) {
            return "订阅返回的是 HTML 页面（多半已失效或换了域名），不是节点数据。\n" +
                "前 200 字符：${body.take(200)}"
        }

        val nodes = CloakNodeParser.parse(body)
        if (nodes.isEmpty()) {
            return "解析出 0 个节点。\n内容前 200 字符：${body.take(200)}"
        }

        // 落盘需要 Context，这里由调用方传；为简化先存进内存并返回摘要
        lastImported = nodes
        val byType = nodes.groupingBy { it.type }.eachCount()
            .entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.key}=${it.value}" }

        return buildString {
            append("✅ 订阅解析成功\n")
            append("来源    : ").append(url).append('\n')
            append("节点数  : ").append(nodes.size).append('\n')
            append("协议分布: ").append(byType).append('\n')
            append("\n前 10 个节点：\n")
            nodes.take(10).forEachIndexed { i, n ->
                append("  ").append(i + 1).append(". [").append(n.type).append("] ")
                append(n.name).append("  ").append(n.host).append(':').append(n.port)
                val r = n.regionHint()
                if (r.isNotBlank()) append("  (").append(r).append(')')
                append('\n')
            }
            if (nodes.size > 10) append("  ... 其余 ").append(nodes.size - 10).append(" 个\n")
            append("\n下一步：action=substart node=<序号或名称> slot=<槽位>")
        }
    }

    /** 最近一次导入的节点（进程内缓存）。 */
    @Volatile private var lastImported: List<CloakNodeParser.Node> = emptyList()

    /**
     * 启动指定节点，暴露成本地 socks5，并绑定到槽位。
     *
     * @param nodeSpec 序号（1-based）或节点名关键词
     * @param slotName 要绑定的槽位
     */
    fun subStart(context: Context, nodeSpec: String, slotName: String): String {
        val nodes = lastImported.ifEmpty { CloakNodeRunner.loadNodes(context) }
        if (nodes.isEmpty()) {
            return "还没有导入任何节点。先执行 action=subimport url=<订阅地址>"
        }

        // 优先按序号，其次按名称模糊匹配
        val idx = nodeSpec.trim().toIntOrNull()
        val node = if (idx != null && idx in 1..nodes.size) {
            nodes[idx - 1]
        } else {
            nodes.firstOrNull { it.name.contains(nodeSpec, ignoreCase = true) }
        } ?: return "找不到节点：$nodeSpec\n可用节点数 ${nodes.size}，用序号 1..${nodes.size} 或名称关键词"

        val slot = CloakSlotStore.find(context, slotName)
            ?: return "槽位 $slotName 不存在。先 action=new slot=$slotName 建一个"

        // 启动 mihomo
        val addr = CloakNodeRunner.start(context, node, slot.name)
            ?: return "节点启动失败。检查 mihomo 是否可执行、节点是否有效。"

        // 把本地 socks5 写入槽位的 proxy 字段
        val slots = CloakSlotStore.load(context)
        val updated = slots.map {
            if (it.name == slot.name) {
                it.copy(proxy = addr, note = "由节点「${node.name}」提供出口")
            } else it
        }
        CloakSlotStore.save(context, updated)

        // 探测出口地理
        val geo = CloakGeoResolver.resolve(addr)

        return buildString {
            append("✅ 节点已启动并绑定到槽位 ").append(slot.name).append('\n')
            append("节点    : [").append(node.type).append("] ").append(node.name).append('\n')
            append("服务器  : ").append(node.host).append(':').append(node.port).append('\n')
            append("本地代理: ").append(addr).append('\n')
            if (geo.ok) {
                append("出口 IP : ").append(geo.ip).append('\n')
                append("归属    : ").append(geo.country).append(' ').append(geo.city)
                    .append(" / ").append(geo.timezone).append('\n')
                append("ISP     : ").append(geo.isp).append('\n')
                append("质量    : ").append(geo.riskNote()).append('\n')
                append("\n指纹将自动对齐为：时区 ").append(geo.timezone)
                    .append("  语言 ").append(CloakGeoResolver.languageFor(geo.countryCode)).append('\n')
            } else {
                append("出口探测: 失败（").append(geo.error).append("）\n")
                append("⚠️ 无法确认 IP 归属，指纹时区可能与实际出口矛盾\n")
            }
            append("\n下一步：browser_switch_engine engine=cloak fingerprint=slot=").append(slot.name)
        }
    }

    /** 列出已导入的节点。 */
    fun subNodes(context: Context): String {
        val nodes = lastImported.ifEmpty { CloakNodeRunner.loadNodes(context) }
        if (nodes.isEmpty()) return "还没有导入任何节点。action=subimport url=<订阅地址>"
        return buildString {
            append("已导入节点 ").append(nodes.size).append(" 个\n")
            append("---\n")
            nodes.forEachIndexed { i, n ->
                append(i + 1).append(". [").append(n.type).append("] ")
                append(n.name).append("\n   ").append(n.host).append(':').append(n.port)
                val r = n.regionHint()
                if (r.isNotBlank()) append("  (").append(r).append(')')
                append('\n')
            }
        }
    }

    /** 简单 HTTP GET，支持自动跟随重定向。 */
    private fun httpGet(url: String): String {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 25_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty(
            "User-Agent",
            "clash-verge/v2.0.0 Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
        )
        return conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    /** 删除槽位（含 Cookie）。 */
    fun deleteSlot(context: Context, name: String): String {
        if (name == CloakSlotStore.DEFAULT_SLOT) {
            return "拒绝删除 default 槽位：那是存量登录态所在。"
        }
        return if (CloakSlotStore.deleteSlot(context, name)) {
            "已删除槽位 $name 及其 Cookie 目录。此操作不可恢复。"
        } else {
            "槽位 $name 不存在。"
        }
    }
}
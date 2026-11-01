package com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak

import com.ai.assistance.operit.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 身份槽位仓库。
 *
 * [IDENTITY-SLOT] 2026-11-01。
 *
 * ==================================================================
 * 为什么必须引入「槽位」这个概念
 * ==================================================================
 * Chromium 里 `--user-data-dir` 一处路径决定了 Cookie / localStorage /
 * IndexedDB / Service Worker / 缓存 的全部落盘位置。
 * 而 `--fingerprint=<种子>` 又决定了 Canvas / WebGL / 字体。
 *
 * 这两者如果分开变，会出现两种致命组合：
 *
 *   ① 换指纹不换 profileId
 *      → 服务端看到「同一台设备（cookie 一致）忽然换了显卡」，直接标记异常。
 *
 *   ② 换 profileId 不换指纹
 *      → 服务端看到「一台全新设备（零 cookie），但显卡/分辨率/时区和昨天那台一模一样」，
 *        指纹聚类立刻把两个账号串成一个团伙。
 *
 * 所以正确的原子单位是「身份」：
 *
 *     身份 = profileId + fingerprintSeed + Cookie 存储
 *
 * 三者同生共死。本仓库就是这张身份表的持久化层。
 *
 * ==================================================================
 * 反面教训（2026-11-01 实际踩到的坑）
 * ==================================================================
 * 曾经把 seed 写成 `"session:$sessionId"` 并让 profileId 由它派生。
 * 而 sessionId 是 `UUID.randomUUID()`，**每次开 App 都不同**，于是：
 *
 *   · 每次打开都是新 profileId → Cookie 全丢 → 今天登录的号明天要重登
 *   · 每次打开都是新指纹 → 服务端看到「同账号天天换设备」
 *
 * 「每次打开窗口自动换指纹」听起来更隐蔽，实际更可疑。
 * 真实用户不会每天清一次浏览器数据。
 *
 * 正解：指纹**跟着账号走**，不跟着窗口走。
 *
 * ==================================================================
 * 存储位置
 * ==================================================================
 * `/root/.operit_cloak/slots.json`（proot 内，与 profile 目录同层）
 *
 * 不放在 Android 侧的 SharedPreferences，因为：
 *   · profile 目录本身在 proot 里，放一起便于整体备份/迁移
 *   · 清理 profile 时可一并清 slots，避免出现「槽位存在但目录已删」的悬空记录
 */
internal object CloakSlotStore {

    private const val TAG = "CloakSlot"

    /** 槽位清单文件。 */
    const val SLOTS_FILE = "/root/.operit_cloak/slots.json"

    /** 默认槽位名：老用户升级后沿用这个，保证不丢已有登录态。 */
    const val DEFAULT_SLOT = "default"

    /**
     * 一个身份槽位。
     *
     * @param name 用户可见的槽位名，如 `reg001`、`taobao_main`。
     * @param seed 指纹种子。同一槽位永远用同一 seed，保证指纹稳定。
     * @param profileId Chromium `--user-data-dir` 的目录名，Cookie 就存在这里。
     * @param createdAt 创建时间戳，仅用于展示与排序。
     * @param note 备注，例如「已注册 3 个号」。
     */
    data class Slot(
        val name: String,
        val seed: String,
        val profileId: String,
        val createdAt: Long = System.currentTimeMillis(),
        val note: String = ""
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("seed", seed)
            put("profileId", profileId)
            put("createdAt", createdAt)
            put("note", note)
        }

        companion object {
            fun fromJson(o: JSONObject): Slot = Slot(
                name = o.optString("name"),
                seed = o.optString("seed"),
                profileId = o.optString("profileId"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                note = o.optString("note", "")
            )
        }
    }

    // ---------------------------------------------------------------- 读写

    /**
     * 读取全部槽位。文件不存在或损坏时返回空列表（不抛异常）。
     */
    fun load(context: android.content.Context): MutableList<Slot> {
        val raw = runCatching { readTextViaProot(context) }.getOrNull()
        if (raw.isNullOrBlank()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i -> Slot.fromJson(arr.getJSONObject(i)) }
        }.getOrElse {
            AppLogger.w(TAG, "slots.json 解析失败，按空表处理: ${it.message}")
            mutableListOf()
        }
    }

    /** 覆盖写入全部槽位。 */
    fun save(context: android.content.Context, slots: List<Slot>) {
        val arr = JSONArray()
        slots.forEach { arr.put(it.toJson()) }
        // 用 base64 传递，避免引号/换行在 proot 命令行里被 shell 吃掉
        val b64 = android.util.Base64.encodeToString(
            arr.toString().toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        val cmd = "mkdir -p /root/.operit_cloak 2>/dev/null; " +
            "echo '$b64' | base64 -d > $SLOTS_FILE 2>/dev/null; " +
            "wc -c < $SLOTS_FILE 2>/dev/null"
        val out = CloakLauncher.prootPublic(context, cmd, 20_000L)
        AppLogger.i(TAG, "槽位表已写入，字节数=${out?.trim()}")
    }

    /** 通过 proot 读文件。 */
    private fun readTextViaProot(context: android.content.Context): String? =
        CloakLauncher.prootPublic(context, "cat $SLOTS_FILE 2>/dev/null", 15_000L)

    // ---------------------------------------------------------------- 槽位操作

    /**
     * 取指定槽位；不存在则返回 null。
     */
    fun find(context: android.content.Context, name: String): Slot? =
        load(context).firstOrNull { it.name == name }

    /**
     * 取默认槽位；不存在则**创建**并持久化。
     *
     * 关键：默认槽位的 seed 是固定字符串 `"default"`，
     * 不是随机值。这样老用户升级后第一次跑，生成的指纹是确定的，
     * 不会因为「升级一次换一次身份」被风控盯上。
     */
    fun defaultSlot(context: android.content.Context): Slot {
        val slots = load(context)
        slots.firstOrNull { it.name == DEFAULT_SLOT }?.let { return it }
        val seed = "operit-default-v1"
        val created = Slot(
            name = DEFAULT_SLOT,
            seed = seed,
            profileId = CloakFingerprintFactory.profileIdFor(seed),
            note = "默认身份，升级前的存量登录态沿用此槽位"
        )
        slots.add(created)
        save(context, slots)
        AppLogger.i(TAG, "已创建默认槽位 profile=${created.profileId}")
        return created
    }

    /**
     * 新建一个随机身份槽位。
     *
     * @param name 不传则自动生成 `reg001` 这种递增名。
     */
    fun createSlot(context: android.content.Context, name: String? = null): Slot {
        val slots = load(context)
        val finalName = name?.trim()?.takeIf { it.isNotBlank() } ?: autoName(slots)
        if (slots.any { it.name == finalName }) {
            // 同名已存在，直接返回旧的，不重复创建 —— 避免误覆盖已有 Cookie
            AppLogger.w(TAG, "槽位 $finalName 已存在，复用之")
            return slots.first { it.name == finalName }
        }
        val seed = CloakFingerprintFactory.randomSeed()
        val slot = Slot(
            name = finalName,
            seed = seed,
            profileId = CloakFingerprintFactory.profileIdFor(seed)
        )
        slots.add(slot)
        save(context, slots)
        AppLogger.i(TAG, "新建槽位 ${slot.name} seed=${slot.seed} profile=${slot.profileId}")
        return slot
    }

    /**
     * 删除槽位。**同时删除对应的 profile 目录**（即 Cookie）。
     *
     * 这是破坏性操作，调用方必须明确知道自己在删账号。
     */
    fun deleteSlot(context: android.content.Context, name: String): Boolean {
        val slots = load(context)
        val target = slots.firstOrNull { it.name == name } ?: return false
        slots.removeAll { it.name == name }
        save(context, slots)
        val cmd = "rm -rf /root/.operit_cloak/profiles/${target.profileId} 2>/dev/null; echo done"
        CloakLauncher.prootPublic(context, cmd, 30_000L)
        AppLogger.i(TAG, "已删除槽位 $name（含 profile ${target.profileId}）")
        return true
    }

    /** 生成不重复的递增名：reg001 / reg002 ... */
    private fun autoName(slots: List<Slot>): String {
        var n = 1
        val existing = slots.map { it.name }.toSet()
        while (true) {
            val cand = "reg%03d".format(n)
            if (cand !in existing) return cand
            n++
            if (n > 9999) return "reg${System.currentTimeMillis() % 100000}"
        }
    }

    /** 供工具层展示的格式化列表。 */
    fun describe(context: android.content.Context): String {
        val slots = load(context)
        if (slots.isEmpty()) return "（暂无槽位，调用 switch_engine 会自动创建 default）"
        val sb = StringBuilder()
        sb.append("槽位数：").append(slots.size).append('\n')
        slots.sortedBy { it.createdAt }.forEach { s ->
            sb.append("  · ").append(s.name)
            sb.append("  profile=").append(s.profileId)
            sb.append("  seed=").append(s.seed)
            if (s.note.isNotBlank()) sb.append("  (").append(s.note).append(')')
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }
}
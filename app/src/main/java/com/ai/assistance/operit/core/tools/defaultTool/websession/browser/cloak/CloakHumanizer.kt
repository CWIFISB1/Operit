package com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 拟人化输入生成器。
 *
 * 为什么需要这一层：
 *   CloakBrowser 官方 wrapper 有 humanize 选项，但那是 **wrapper 层**特性 ——
 *   它替换的是 Playwright 的 page.click() / page.fill()。我们直连 CDP，
 *   走 Input.dispatchMouseEvent 裸发事件，humanize 完全不生效。
 *
 *   而 Cloudflare / reCAPTCHA / DataDome 这类评分体系，行为权重远高于静态指纹：
 *   鼠标一步到位、点击间隔恒定、滚动跳跃，静态指纹再干净也会被判高风险。
 *
 *   静态部分 Cloak 那 58 处编译期补丁已经做了，行为部分是空的 —— 这里补。
 *
 * 本类只产出轨迹点和时间间隔，不碰 CDP，便于单测与复用。
 * 所有随机走 seeded Random：同 seed 可复现，出问题能回放。
 */
internal object CloakHumanizer {

    /** 一个轨迹点：远端 CSS 像素坐标 + 相对上一点的等待毫秒。 */
    data class Step(val x: Double, val y: Double, val delayMs: Long)

    private data class Preset(
        val maxSteps: Int,
        val overshootChance: Double,
        val pressHoldMs: LongRange,
        val typingDelayMs: LongRange,
        val thinkPauseChance: Double,
        val scrollMaxSteps: Int
    )

    private fun presetOf(name: String): Preset =
        when (name.lowercase()) {
            "careful" -> Preset(
                maxSteps = 28,
                overshootChance = 0.35,
                pressHoldMs = 80L..160L,
                typingDelayMs = 90L..220L,
                thinkPauseChance = 0.28,
                scrollMaxSteps = 14
            )
            "fast" -> Preset(
                maxSteps = 8,
                overshootChance = 0.10,
                pressHoldMs = 30L..70L,
                typingDelayMs = 25L..60L,
                thinkPauseChance = 0.05,
                scrollMaxSteps = 5
            )
            else -> Preset(
                maxSteps = 16,
                overshootChance = 0.22,
                pressHoldMs = 50L..110L,
                typingDelayMs = 55L..130L,
                thinkPauseChance = 0.15,
                scrollMaxSteps = 9
            )
        }

    /**
     * 从 (x0,y0) 到 (x1,y1) 的移动轨迹。
     *
     * 三次贝塞尔 + ease-in-out 时间重参数化：
     *  - 控制点带随机横向偏移，模拟手腕自然弧度
     *  - 末端小概率过冲再回拉（真实鼠标常见）
     *  - 每步等待按缓动分配，起步慢、中段快、收尾慢
     */
    fun movePath(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        preset: String = "default",
        seed: Int = 0
    ): List<Step> {
        val p = presetOf(preset)
        val rnd = Random(seed)
        val dx = x1 - x0
        val dy = y1 - y0
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < 1.0) return listOf(Step(x1, y1, 0L))

        // 步数随距离缩放：长距离多点，短距离不拖沓。
        val n = max(4, min(p.maxSteps, (dist / 28.0).toInt()))

        // 控制点沿主方向的法线偏移，幅度随距离抖动。
        val nx = if (dist > 0.0) -dy / dist else 0.0
        val ny = if (dist > 0.0) dx / dist else 0.0
        val bend1 = (rnd.nextDouble() - 0.5) * dist * 0.35
        val bend2 = (rnd.nextDouble() - 0.5) * dist * 0.22
        val c1x = x0 + dx * 0.30 + nx * bend1
        val c1y = y0 + dy * 0.30 + ny * bend1
        val c2x = x0 + dx * 0.68 + nx * bend2
        val c2y = y0 + dy * 0.68 + ny * bend2

        // 过冲：冲过头一点再拉回，模拟手部惯性。
        val overshoot = rnd.nextDouble() < p.overshootChance
        val endX = if (overshoot) x1 + nx * rnd.nextDouble() * 12.0 else x1
        val endY = if (overshoot) y1 + ny * rnd.nextDouble() * 12.0 else y1

        val out = ArrayList<Step>(n + 2)
        for (i in 1..n) {
            val tRaw = i.toDouble() / n
            val t = easeInOut(tRaw)
            val mt = 1 - t
            val bx = mt * mt * mt * x0 + 3 * mt * mt * t * c1x + 3 * mt * t * t * c2x + t * t * t * endX
            val by = mt * mt * mt * y0 + 3 * mt * mt * t * c1y + 3 * mt * t * t * c2y + t * t * t * endY
            // 中段快、首尾慢，叠一点抖动避免等间隔。
            val base = (18.0 * (0.45 + 0.85 * sin(PI * tRaw))).toLong()
            val jitter = rnd.nextLong(-4L, 9L)
            out.add(Step(bx, by, (base + jitter).coerceAtLeast(2L)))
        }
        if (overshoot) {
            out.add(Step(x1, y1, rnd.nextLong(60L, 131L)))
        }
        return out
    }

    /** 三次缓动：手臂加速—匀速—减速。 */
    private fun easeInOut(t: Double): Double =
        if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2

    /** 按下到抬起的保持时长。 */
    fun pressHoldMs(preset: String = "default", seed: Int = 0): Long {
        val p = presetOf(preset)
        val rnd = Random(seed)
        return rnd.nextLong(p.pressHoldMs.first, p.pressHoldMs.last + 1)
    }

    /**
     * 逐字输入的节奏。
     *
     * 长文本里偶尔插入思考停顿（180~520ms），这是真人输入最强的特征之一。
     * 返回的列表与 text 等长，每个元素是该字符前的等待毫秒。
     */
    fun typingDelays(text: String, preset: String = "default", seed: Int = 0): List<Long> {
        val p = presetOf(preset)
        val rnd = Random(seed)
        val out = ArrayList<Long>(text.length)
        for (i in text.indices) {
            var d = rnd.nextLong(p.typingDelayMs.first, p.typingDelayMs.last + 1)
            if (rnd.nextDouble() < p.thinkPauseChance && i > 0) {
                d += rnd.nextLong(180L, 521L)
            }
            out.add(d)
        }
        return out
    }

    /**
     * 滚动曲线：加速 → 巡航 → 减速。
     *
     * 返回若干段 deltaY，各段之和等于 totalDelta。
     * 一次性把整段滚完是最典型的行为特征之一，必须拆开。
     */
    fun scrollSteps(totalDelta: Double, preset: String = "default", seed: Int = 0): List<Double> {
        val p = presetOf(preset)
        val rnd = Random(seed)
        val n = max(3, min(p.scrollMaxSteps, (abs(totalDelta) / 60.0).toInt()))
        val raw = DoubleArray(n) { i ->
            val t = (i + 1).toDouble() / n
            // 单峰曲线：中段占比最大。
            val w = sin(PI * t)
            0.35 + w * 1.25 + rnd.nextDouble() * 0.12
        }
        val sum = raw.sum()
        if (sum <= 0.0) return listOf(totalDelta)
        return raw.map { totalDelta * it / sum }
    }

    /**
     * 滚动过程中每一步之间的等待毫秒数。
     *
     * 真人的滚轮/滑动不是匀速的：起步慢（蓄力）、巡航快、收尾慢（刹车）。
     * 这里按步序号做缓动，并叠加少量抖动。
     *
     * @param preset default / careful / fast
     * @param step   当前是第几步（0-based）
     * @param seed   随机种子，同种子可复现
     */
    fun scrollDelayMs(preset: String = "default", step: Int = 0, seed: Int = 0): Long {
        val rnd = Random(seed * 31 + step)
        val base = when (preset.lowercase()) {
            "careful" -> 42L
            "fast" -> 12L
            else -> 24L
        }
        // 首尾各慢 1.6 倍，中段恢复基准（模拟加速-巡航-减速）
        val n = presetOf(preset).scrollSteps.coerceAtLeast(2)
        val t = step.toDouble() / n
        val shape = if (t < 0.2 || t > 0.8) 1.6 else 1.0
        val jitter = rnd.nextLong(-3L, 6L)
        return (base * shape).toLong().plus(jitter).coerceAtLeast(2L)
    }

    /**
     * 阅读时的随机微移动：模拟手不自觉地挪一点。
     * 空列表表示本次不动。
     */
    fun idleDrift(cx: Double, cy: Double, seed: Int = 0): List<Step> {
        val rnd = Random(seed)
        if (rnd.nextDouble() > 0.35) return emptyList()
        val tx = cx + (rnd.nextDouble() - 0.5) * 16.0
        val ty = cy + (rnd.nextDouble() - 0.5) * 12.0
        return movePath(cx, cy, tx, ty, "fast", seed + 7)
    }
}

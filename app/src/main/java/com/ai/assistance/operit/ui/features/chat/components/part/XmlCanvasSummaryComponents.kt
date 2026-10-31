package com.ai.assistance.operit.ui.features.chat.components.part

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke


private const val CANVAS_SINGLE_LINE_TEXT_LIMIT = 160

@Composable
internal fun CanvasToolSummaryRow(
    toolName: String,
    summary: String,
    semanticDescription: String,
    leadingIcon: ImageVector,
    titleColor: Color,
    summaryColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    // [ANR-FIX] 原实现用 BoxWithConstraints + Canvas 手绘：
    //   · Canvas 的 draw lambda 里调 VectorPainter.draw()
    //   · VectorPainter.getDrawInvalidation() 每次读 snapshot state
    //   · SnapshotStateObserver.recordRead 把每次读都塞进 MutableObjectIntMap
    //   · 工具摘要卡片一多，这个 map 随帧增长 → 主线程 hashCode/findIndex 卡死 1000ms → ANR
    // 现改为标准 Compose 布局（Row + Icon + Text），state 读取走 Compose 自己的
    // 稳定路径，不再有每帧 recordRead 累加。
    val titleStyle = MaterialTheme.typography.labelMedium.copy(color = titleColor)
    val summaryStyle = MaterialTheme.typography.bodySmall.copy(color = summaryColor)
    val normalizedSummary = remember(summary) { summary.toCanvasSingleLineText() }

    val baseModifier = modifier
        .fillMaxWidth()
        .semantics { contentDescription = semanticDescription }

    val clickableModifier =
        if (onClick != null) {
            baseModifier
                .semantics { role = Role.Button }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    role = Role.Button,
                    onClick = onClick,
                )
        } else {
            baseModifier
        }

    Row(
        modifier = clickableModifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = titleColor.copy(alpha = 0.7f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = toolName,
            style = titleStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 80.dp, max = 120.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = normalizedSummary,
            style = summaryStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun String.toCanvasSingleLineText(): String {
    val normalized = replace('\r', ' ').replace('\n', ' ').trim()
    return if (normalized.length <= CANVAS_SINGLE_LINE_TEXT_LIMIT) {
        normalized
    } else {
        normalized.take(CANVAS_SINGLE_LINE_TEXT_LIMIT) + "..."
    }
}

@Composable
internal fun CanvasToolResultRow(
    summary: String,
    isSuccess: Boolean,
    semanticDescription: String,
    modifier: Modifier = Modifier,
    emphasizeSummary: Boolean = false,
    onClick: (() -> Unit)? = null,
    onCopyClick: (() -> Unit)? = null,
) {
    // [ANR-FIX-2] 原实现用 BoxWithConstraints + Canvas 手绘：
    //   Canvas 的 draw lambda 里逐帧调 arrowPainter.draw() / statusPainter.draw() /
    //   copyPainter.draw()，VectorPainter.getDrawInvalidation() 每帧读 snapshot state，
    //   SnapshotStateObserver.ObservedScopeMap.recordRead 把每次读演进 MutableObjectIntMap。
    //   工具结果行一多，这个 map 随帧膨胀，主线程 MutableObjectIntMap.findIndex
    //   哈希查找耗时累积到 1000ms 以上 → AnrMonitor 报 ANR → 系统杀进程。
    // 现改为标准 Compose 布局（Row + Icon + Text），
    // state 读取走 Compose 稳定路径，不再有每帧 recordRead 累加；
    // 同时删掉 textMeasurer.measure 与 widthPx 手工排版，减少一次同步文本测量。
    val summaryColor =
        if (isSuccess) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        } else {
            MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
        }
    val summaryStyle =
        MaterialTheme.typography.bodySmall.copy(
            color = summaryColor,
            fontWeight = if (emphasizeSummary) FontWeight.Medium else null,
        )
    val leadingTint =
        if (isSuccess) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        }
    val statusTint =
        if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val copyTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    val normalizedSummary = remember(summary) { summary.toCanvasSingleLineText() }

    val baseModifier =
        modifier
            .fillMaxWidth()
            .semantics { contentDescription = semanticDescription }

    val rowModifier =
        if (onClick != null) {
            baseModifier
                .semantics { role = Role.Button }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    role = Role.Button,
                    onClick = onClick,
                )
        } else {
            baseModifier
        }

    Row(
        modifier = rowModifier.padding(start = 24.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.SubdirectoryArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = leadingTint,
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = if (isSuccess) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = statusTint,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = normalizedSummary,
            style = summaryStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onCopyClick != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "$semanticDescription, copy",
                modifier =
                    Modifier
                        .size(24.dp)
                        .indication(remember { MutableInteractionSource() }, ripple(bounded = false))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onCopyClick,
                        )
                        .padding(5.dp),
                tint = copyTint,
            )
        }
    }
}

@Composable
internal fun CanvasStatusCard(
    text: String,
    textColor: Color,
    backgroundColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
) {
    // [ANR-FIX-3] 原实现用 BoxWithConstraints + Canvas drawRoundRect + drawText 手绘卡片。
    // 手绘本身不读 VectorPainter，但配 textMeasurer.measure() 会在组合期做一次
    // 同步文本测量，卡片多时同样堆主线程。改为标准布局 + Surface 描边，
    // 测量交给 Compose 自己的布局阶段（可跳过、可复用缓存）。
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        color = backgroundColor,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(color = textColor),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}

@Composable
internal fun CanvasWarningStatusRow(
    summaryText: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    // [ANR-FIX-4] 原实现用 BoxWithConstraints + Canvas 画左侧竖条 + drawText 写文案。
    // 与 CanvasStatusCard 同因：组合期同步 textMeasurer.measure()。
    // 改为 Row + Box(竖条) + Text，测量交给布局阶段。
    val barColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
    val textColor = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
    val textStyle = MaterialTheme.typography.bodySmall.copy(color = textColor)

    val baseModifier = modifier.fillMaxWidth()
    val clickableModifier =
        if (onClick != null) {
            baseModifier
                .semantics { role = Role.Button }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    role = Role.Button,
                    onClick = onClick,
                )
        } else {
            baseModifier
        }

    Row(
        modifier = clickableModifier.padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .width(2.dp)
                    .height(16.dp)
                    .background(barColor, RoundedCornerShape(percent = 50))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = remember(summaryText) { summaryText.toCanvasSingleLineText() },
            style = textStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

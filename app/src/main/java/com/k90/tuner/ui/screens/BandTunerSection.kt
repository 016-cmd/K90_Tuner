package com.k90.tuner.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k90.tuner.service.DolbyTunerManager
import com.k90.tuner.service.DolbyTunerManager.BandChannel
import com.k90.tuner.ui.components.BandCurveCanvas
import kotlinx.coroutines.launch

private val BAccent = Color(0xFF22C55E)
private val BAccentDim = Color(0xFF166534)

/**
 * 频段调节（band_optimizer）— 标准版，仅 L/R 两声道。
 *
 * 机制：以 Medium 场景为基准，滑杆直接调绝对增益，写回时三场景同比。
 * 布局：顶部曲线 tab（左/右 声道切换），下方左右声道各 20 频段折叠卡。
 */
@Composable
fun BandTunerSection() {
    val bandOffsets by DolbyTunerManager.bandOffsets.collectAsState()
    val baselines by DolbyTunerManager.bandBaselines.collectAsState()
    val hasBands by DolbyTunerManager.hasBandsParsed.collectAsState()
    val scope = rememberCoroutineScope()

    var curveTab by remember { mutableStateOf(BandChannel.LEFT) }
    var extendedTab by remember { mutableStateOf(true) }
    var expandedLeft by remember { mutableStateOf(false) }
    var expandedRight by remember { mutableStateOf(false) }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    if (!hasBands) {
        Text(
            "⚠ 未解析到三场景 band_optimizer 基值（模块版本过旧或没有频段数据），频段调节不可用。",
            color = onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp)
        )
        return
    }

    val medium = baselines.medium
    fun curvePoints(channel: BandChannel, bands: List<Int>): List<Pair<Int, Int>> =
        bands.mapNotNull { f ->
            val base = medium[f] ?: return@mapNotNull null
            val d = when (channel) {
                BandChannel.LEFT -> bandOffsets.left[f] ?: 0
                BandChannel.RIGHT -> bandOffsets.right[f] ?: 0
            }
            f to (base[channel.ordinal] + d)
        }

    val curvePointsLeft = curvePoints(BandChannel.LEFT, DolbyTunerManager.ALL_BANDS)
    val curvePointsRight = curvePoints(BandChannel.RIGHT, DolbyTunerManager.ALL_BANDS)

    Column(Modifier.fillMaxWidth()) {
        // 曲线 tab（左/右声道）
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(BAccentDim.copy(alpha = 0.25f))
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            CurveTab("左声道", curveTab == BandChannel.LEFT, { curveTab = BandChannel.LEFT }, Modifier.weight(1f))
            CurveTab("右声道", curveTab == BandChannel.RIGHT, { curveTab = BandChannel.RIGHT }, Modifier.weight(1f))
        }

        Spacer(Modifier.height(6.dp))

        val curveColor = if (curveTab == BandChannel.LEFT) Color(0xFF22C55E) else Color(0xFF3B82F6)
        val curvePts = if (curveTab == BandChannel.LEFT) curvePointsLeft else curvePointsRight
        BandCurveCanvas(points = curvePts, lineColor = curveColor, showDelta = false)

        Spacer(Modifier.height(4.dp))
        Text(
            "📈 ${if (curveTab == BandChannel.LEFT) "左声道" else "右声道"} · Medium 场景实时频响（绝对增益）",
            color = onSurfaceVariant.copy(alpha = 0.7f), fontSize = 9.sp
        )

        Spacer(Modifier.height(8.dp))

        // 左声道 20 频段
        FoldCard(
            title = "左声道（gain_left）", subtitle = "20 频段",
            icon = Icons.Default.SurroundSound, expanded = expandedLeft, onToggle = { expandedLeft = it }
        ) {
            DolbyTunerManager.ALL_BANDS.forEach { f ->
                val base = medium[f]?.get(0) ?: 0
                val cur = base + (bandOffsets.left[f] ?: 0)
                BandSlider(f, base, cur) { newAbs ->
                    DolbyTunerManager.updateBandOffset(BandChannel.LEFT, f, newAbs - base)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // 右声道 20 频段
        FoldCard(
            title = "右声道（gain_right）", subtitle = "20 频段",
            icon = Icons.Default.SurroundSound, expanded = expandedRight, onToggle = { expandedRight = it }
        ) {
            DolbyTunerManager.ALL_BANDS.forEach { f ->
                val base = medium[f]?.get(1) ?: 0
                val cur = base + (bandOffsets.right[f] ?: 0)
                BandSlider(f, base, cur) { newAbs ->
                    DolbyTunerManager.updateBandOffset(BandChannel.RIGHT, f, newAbs - base)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // 重置频段偏移（读当前模式 factory 出厂基准）
        OutlinedButton(
            onClick = {
                scope.launch {
                    val ok = DolbyTunerManager.resetBandOffsets()
                    DolbyTunerManager.setResultMsg(
                        if (ok) "✅ 已重置频段偏移为 ${DolbyTunerManager.currentModeName}出厂基准（拉杆全部归零，未写入需点应用生效）"
                        else "❌ 重置失败：模块 factory 出厂文件不存在"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF59E0B))
        ) {
            Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("重置频段偏移（读当前模式出厂基准）", fontSize = 11.sp)
        }

        Spacer(Modifier.height(6.dp))

        Text(
            "🎚 滑杆直接调节该频段的绝对增益值（初始为当前模式默认值），调整后自动记录相对默认的偏移，应用时 Large/Small 场景按同样偏移同步修改。应用后仅当前激活模式生效，与另一调音模式保持独立。",
            color = onSurfaceVariant.copy(alpha = 0.7f), fontSize = 9.sp, lineHeight = 13.sp
        )
    }
}

@Composable
private fun CurveTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) BAccent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun FoldCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .clickable { onToggle(!expanded) }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, tint = BAccent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(subtitle, color = onSurfaceVariant, fontSize = 9.sp)
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, tint = onSurfaceVariant, modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.fillMaxWidth()) {
                    content()
                }
            }
        }
    }
}

/** 单频段增益滑杆（绝对增益直调），仅 L/R */
@Composable
private fun BandSlider(freq: Int, base: Int, value: Int, onAbsChange: (Int) -> Unit) {
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = BAccent
    val delta = value - base // 相对默认的偏移
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$freq Hz", Modifier.width(58.dp),
                color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Medium
            )
            Slider(
                value = value.toFloat().coerceIn(-250f, 250f),
                onValueChange = { onAbsChange(it.toInt()) },
                valueRange = -250f..250f,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = accent.copy(alpha = 0.12f)
                )
            )
            Text(
                (if (delta > 0) "+" else "") + "$delta",
                Modifier.width(40.dp),
                color = when { delta > 0 -> BAccent; delta < 0 -> Color(0xFFEF4444); else -> onSurfaceVariant },
                fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End
            )
        }
        Row(Modifier.fillMaxWidth().padding(start = 66.dp, end = 48.dp)) {
            Text("实际: $value", color = onSurfaceVariant.copy(alpha = 0.6f), fontSize = 9.sp)
        }
    }
}
package com.k90.tuner.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k90.tuner.ui.components.GlassCard
import com.k90.tuner.ui.theme.Appearance
import com.k90.tuner.ui.theme.ThemePrefs
import com.k90.tuner.ui.theme.WallpaperState
import com.k90.tuner.ui.theme.jellyPress
import com.k90.tuner.ui.theme.subInk

/**
 * 「外观」Tab —— 平铺成一张张卡片，不折叠。
 * 参数与 DolbyTuner / K90PM / K100PM 同一套（Appearance）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(activity: Activity) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            ThemePrefs.setWallpaperUri(ctx, uri.toString())
            activity.recreate()
        }
    }
    val wallpaperKind = ThemePrefs.getWallpaperKind(ctx)
    val hasLocal = ThemePrefs.getWallpaperUri(ctx) != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "外观",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "玻璃 · 颜色 · 壁纸",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 78.dp)
        ) {
            item { Spacer(Modifier.height(6.dp)) }

            // ── 玻璃与性能 ──
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    CardTitle("玻璃与性能")
                    ToggleRow("卡片真折射", "关掉后用纯色玻璃（省 GPU，最稳）", Appearance.cardLiquid) {
                        Appearance.setCardLiquid(ctx, it)
                    }
                    SegRow("性能", listOf("自动", "高画质", "省电"), Appearance.perfMode) {
                        Appearance.setPerfMode(ctx, it)
                    }
                    SegRow(
                        "玻璃风格",
                        listOf("清透", "标准", "浓厚", "柔和", "省电"),
                        Appearance.presetIndex()
                    ) { Appearance.applyPreset(ctx, it) }
                    SegRow(
                        "折射强度",
                        listOf("低", "中", "高"),
                        nearest(Appearance.refraction, intArrayOf(40, 70, 100))
                    ) { Appearance.setRefraction(ctx, intArrayOf(40, 70, 100)[it]) }
                    SegRow("玻璃厚度", listOf("薄", "标准", "厚"), Appearance.thickness) {
                        Appearance.setThickness(ctx, it)
                    }
                }
            }

            // ── 颜色与形状 ──
            item {
                Spacer(Modifier.height(10.dp))
                GlassCard(Modifier.fillMaxWidth()) {
                    CardTitle("颜色与形状")
                    SegRow(
                        "色散",
                        listOf("关", "轻", "标准", "强"),
                        nearest(Appearance.dispersion, intArrayOf(0, 25, 50, 80))
                    ) { Appearance.setDispersion(ctx, intArrayOf(0, 25, 50, 80)[it]) }
                    SegRow(
                        "体积光",
                        listOf("关", "弱", "标准", "强"),
                        Appearance.volumeLight.coerceIn(0, 3)
                    ) { Appearance.setVolumeLight(ctx, it) }
                    SegRow("卡片底色", listOf("自动", "纯白", "纯黑"), Appearance.tintMode) {
                        Appearance.setTintMode(ctx, it)
                    }
                    SegRow(
                        "圆角",
                        listOf("小", "标准", "大"),
                        nearest(Appearance.radius.toInt(), intArrayOf(16, 26, 32))
                    ) { Appearance.setRadius(ctx, intArrayOf(16, 26, 32)[it].toFloat()) }
                }
            }

            // ── 动效 ──
            item {
                Spacer(Modifier.height(10.dp))
                GlassCard(Modifier.fillMaxWidth()) {
                    CardTitle("动效")
                    SegRow(
                        "果冻按压",
                        listOf("关", "轻", "标准", "强"),
                        nearest(Appearance.jelly, intArrayOf(0, 40, 70, 100))
                    ) { Appearance.setJelly(ctx, intArrayOf(0, 40, 70, 100)[it]) }
                    SegRow("弹簧", listOf("软", "标准", "硬"), Appearance.spring) {
                        Appearance.setSpring(ctx, it)
                    }
                    ToggleRow("光照互通", "按压时照亮邻近玻璃", Appearance.crossLight) {
                        Appearance.setCrossLight(ctx, it)
                    }
                    ToggleRow("页面切换动画", null, Appearance.animPage == 1) {
                        Appearance.setAnimPage(ctx, it)
                    }
                    ToggleRow("卡片入场动画", null, Appearance.animCard == 1) {
                        Appearance.setAnimCard(ctx, it)
                    }
                    ToggleRow("启动动画", null, Appearance.animLaunch == 1) {
                        Appearance.setAnimLaunch(ctx, it)
                    }
                }
            }

            // ── 壁纸 ──
            item {
                Spacer(Modifier.height(10.dp))
                GlassCard(Modifier.fillMaxWidth()) {
                    CardTitle("壁纸")
                    val wp = WallpaperState.original
                    if (wp != null) {
                        Image(
                            bitmap = wp.asImageBitmap(),
                            contentDescription = "当前壁纸",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .clip(RoundedCornerShape(16.dp))
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        "当前：${Appearance.builtinName(if (hasLocal) 0 else wallpaperKind)}",
                        fontSize = 9.sp,
                        color = subInk()
                    )
                    SegRow(
                        "来源",
                        listOf("本地", "绝区零", "海风", "微醺"),
                        if (hasLocal) 0 else wallpaperKind
                    ) { k ->
                        if (k == 0 && !hasLocal) {
                            picker.launch("image/*")
                        } else {
                            ThemePrefs.setWallpaperUri(ctx, null)
                            ThemePrefs.setWallpaperKind(ctx, k)
                            activity.recreate()
                        }
                    }
                    SegRow(
                        "压暗",
                        listOf("无", "轻", "中", "深"),
                        nearest(Appearance.wallpaperDim, intArrayOf(0, 20, 40, 60))
                    ) { Appearance.setWallpaperDim(ctx, intArrayOf(0, 20, 40, 60)[it]) }
                    Spacer(Modifier.height(6.dp))
                    if (hasLocal) {
                        WallButton("清除本地壁纸", Icons.Default.Delete) {
                            ThemePrefs.setWallpaperUri(ctx, null)
                            ThemePrefs.setWallpaperKind(ctx, 1)
                            activity.recreate()
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    WallButton("从本地选择图片", Icons.Default.PhotoLibrary) {
                        picker.launch("image/*")
                    }
                }
            }

            // ── 文字 ──
            item {
                Spacer(Modifier.height(10.dp))
                GlassCard(Modifier.fillMaxWidth()) {
                    CardTitle("文字")
                    ToggleRow(
                        "增强文字可读性",
                        "透明卡片上看不清时打开：小字改成纯黑/纯白",
                        Appearance.readLevel >= 1
                    ) { Appearance.setReadLevel(ctx, if (it) 1 else 0) }
                }
            }

            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = subInk(0.72f)
    )
    Spacer(Modifier.height(6.dp))
}

private fun nearest(v: Int, arr: IntArray): Int {
    var best = 0
    var bd = Int.MAX_VALUE
    arr.forEachIndexed { i, x ->
        val d = kotlin.math.abs(x - v)
        if (d < bd) { bd = d; best = i }
    }
    return best
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp)
            if (subtitle != null) Text(subtitle, fontSize = 9.sp, color = subInk())
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Appearance.accent),
            modifier = Modifier.size(width = 40.dp, height = 24.dp)
        )
    }
}

@Composable
private fun SegRow(
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Spacer(Modifier.height(8.dp))
    Text(title, fontSize = 12.sp)
    Spacer(Modifier.height(4.dp))
    Row(
        Modifier.fillMaxWidth().padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEachIndexed { i, s ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (on) Appearance.accent.copy(alpha = 0.22f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    )
                    .jellyPress(Appearance.jelly > 0)
                    .clickable { onSelect(i) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    s,
                    fontSize = 10.sp,
                    color = if (on) Appearance.accent else subInk(0.72f),
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun WallButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    val ink = Color(0xFF1A1A1E)
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.92f))
            .jellyPress(Appearance.jelly > 0)
            .clickable { onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = ink, fontWeight = FontWeight.Medium)
    }
}
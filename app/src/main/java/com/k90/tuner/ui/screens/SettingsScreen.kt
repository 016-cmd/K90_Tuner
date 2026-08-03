package com.k90.tuner.ui.screens

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k90.tuner.ui.components.GlassSettingsCard
import com.k90.tuner.ui.theme.ThemeMode
import com.k90.tuner.ui.theme.ThemePrefs
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    activity: Activity
) {
    val ctx = LocalContext.current
    var themeMode by remember { mutableStateOf(ThemePrefs.getMode(ctx)) }
    var wallpaperUri by remember { mutableStateOf(ThemePrefs.getWallpaperUri(ctx)) }

    val wallpaperPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            // 复制到内部存储 → Scene 同款 windowBg.jpg
            try {
                val bgFile = File(ctx.filesDir, "windowBg.jpg")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(bgFile).use { output ->
                        input.copyTo(output)
                    }
                }
                ThemePrefs.setWallpaperUri(ctx, bgFile.path)
            } catch (_: Exception) {
                // 回退：直接存 URI
                ThemePrefs.setWallpaperUri(ctx, uri.toString())
            }
            activity.recreate()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ── 外观 ──
            SectionHeader("外观")
            Spacer(Modifier.height(6.dp))

            GlassSettingsCard {
                ThemeOption(
                    label = "浅色",
                    icon = Icons.Rounded.LightMode,
                    selected = themeMode == ThemeMode.LIGHT,
                    onClick = {
                        themeMode = ThemeMode.LIGHT
                        ThemePrefs.setMode(ctx, ThemeMode.LIGHT)
                        activity.recreate()
                    }
                )
                ThemeOption(
                    label = "深色",
                    icon = Icons.Rounded.DarkMode,
                    selected = themeMode == ThemeMode.DARK,
                    onClick = {
                        themeMode = ThemeMode.DARK
                        ThemePrefs.setMode(ctx, ThemeMode.DARK)
                        activity.recreate()
                    }
                )
                ThemeOption(
                    label = "跟随系统",
                    icon = Icons.Rounded.SettingsBrightness,
                    selected = themeMode == ThemeMode.SYSTEM,
                    onClick = {
                        themeMode = ThemeMode.SYSTEM
                        ThemePrefs.setMode(ctx, ThemeMode.SYSTEM)
                        activity.recreate()
                    }
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── 背景 ──
            SectionHeader("背景")
            Spacer(Modifier.height(6.dp))

            GlassSettingsCard {
                SettingsRow(
                    icon = Icons.Rounded.Wallpaper,
                    label = "自定义壁纸",
                    subtitle = if (wallpaperUri != null) "已设置" else "未设置",
                    onClick = { wallpaperPicker.launch("image/*") }
                )
                if (wallpaperUri != null) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    SettingsRow(
                        icon = Icons.Rounded.Delete,
                        label = "移除壁纸",
                        subtitle = null,
                        onClick = {
                            ThemePrefs.setWallpaperUri(ctx, null)
                            // 删除 Scene 同款壁纸文件
                            File(ctx.filesDir, "windowBg.jpg").delete()
                            activity.recreate()
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 关于 ──
            SectionHeader("关于")
            Spacer(Modifier.height(6.dp))
            GlassSettingsCard {
                InfoRow("版本", "v1.0.0")
                InfoRow("开发者", "016-cmd")
                InfoRow("设备", "REDMI K90（annibale）")
                InfoRow("模块", "K90 音质优化")
            }

            Spacer(Modifier.height(16.dp))

            // ── 特别鸣谢 ──
            SectionHeader("特别鸣谢")
            Spacer(Modifier.height(6.dp))
            GlassSettingsCard {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🙏",
                        fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp)
                    )
                    Text(
                        "感谢三位朋友全程参与真机验证与建议反馈，为模块与应用的打磨付出了大量心血：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp, lineHeight = 16.sp
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        "柚柒",
                        "神鸡",
                        "二十四画生"
                    ).forEach { name ->
                        Text(
                            "@$name",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "每一处可靠的效果背后，都有你们的一份信任与陪伴。由衷感谢！",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // 底部预留空间，避免内容被底部 Dock 栏遮挡
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun ThemeOption(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(
                Icons.Rounded.CheckCircle,
                "已选",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    label: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}
package com.k90.tuner.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k90.tuner.service.DolbyTunerManager
import kotlinx.coroutines.launch

private val Accent = Color(0xFF22C55E)
private val AccentDim = Color(0xFF166534)
private val Danger = Color(0xFFEF4444)

/**
 * 频段调节独立页（Tab 2）。
 * 仅 L/R 两声道，20 频段，三场景同比写回当前激活调音模式。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BandTunerScreen(activity: Activity) {
    val scope = rememberCoroutineScope()
    val isApplying by DolbyTunerManager.isApplying.collectAsState()
    val currentMode by DolbyTunerManager.currentMode.collectAsState()
    val currentModeName = DolbyTunerManager.currentModeName

    var showApplyDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showLoadDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var bandSaveName by remember { mutableStateOf("") }
    var bandPresetNames by remember { mutableStateOf(listOf<String>()) }

    // 每次进入频段页：以当前生效文件刷新三场景基准（保留偏移），并同步模式显示
    LaunchedEffect(Unit) {
        DolbyTunerManager.refreshBandBaselines(activity)
    }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Equalizer, null, tint = Accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("频段调节", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = onSurface)
                            Text("${currentModeName}模式 · 仅左右声道", style = MaterialTheme.typography.bodySmall, color = onSurfaceVariant)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // 模式切换提示
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = AccentDim.copy(alpha = 0.2f))
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Equalizer, null, tint = Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("当前调音模式：$currentModeName", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("频段调整仅对当前激活模式生效，另一模式保持独立。切换模式请在模块 WebUI 中操作。", color = onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                }
            }

            // 频段调节主体
            BandTunerSection()

            Spacer(Modifier.height(12.dp))

            // 应用按钮
            Button(
                onClick = { showApplyDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                enabled = !isApplying
            ) {
                if (isApplying) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("应用频段调整", fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 频段预设（独立于调音台预设，仅存频段偏移） ──
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("频段预设", color = onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("仅保存/加载当前频段偏移，与该模式的杜比参数预设相互独立。", color = onSurfaceVariant, fontSize = 9.sp, lineHeight = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { showSaveDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)
                        ) {
                            Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("保存频段预设", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                bandPresetNames = DolbyTunerManager.getBandPresetNames(activity)
                                showLoadDialog = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)
                        ) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("加载频段预设", fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "⚠ 应用后仅当前激活模式（$currentModeName）的频段会写入模块，将重启音频服务即时生效。请不要频繁应用。",
                color = onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp
            )

            Spacer(Modifier.height(80.dp))
        }
    }

    // 应用确认弹窗
    if (showApplyDialog) {
        AlertDialog(
            onDismissRequest = { showApplyDialog = false },
            title = { Text("确认应用频段调整", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "将当前频段调整写入 $currentModeName 模式对应的模块文件，并重启音频服务即时生效。\n\n⚠ 请不要频繁应用，每次都会重启音频服务。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showApplyDialog = false
                    scope.launch { DolbyTunerManager.applyChanges(activity) }
                }) { Text("确定", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { showApplyDialog = false }) { Text("取消") }
            }
        )
    }

    // ── 保存频段预设弹窗 ──
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = {
                showSaveDialog = false
                bandSaveName = ""
            },
            title = { Text("保存频段预设", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("输入预设名称（最多5个）", fontSize = 13.sp, color = onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = bandSaveName,
                        onValueChange = { bandSaveName = it },
                        label = { Text("预设名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            cursorColor = Accent
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (bandSaveName.isNotBlank()) {
                            val msg = DolbyTunerManager.saveBandPreset(activity, bandSaveName.trim())
                            DolbyTunerManager.setResultMsg(msg)
                            bandSaveName = ""
                            showSaveDialog = false
                        }
                    },
                    enabled = bandSaveName.isNotBlank()
                ) { Text("保存", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    bandSaveName = ""
                }) { Text("取消") }
            }
        )
    }

    // ── 加载频段预设弹窗 ──
    if (showLoadDialog) {
        AlertDialog(
            onDismissRequest = { showLoadDialog = false },
            title = { Text("加载频段预设", fontWeight = FontWeight.Bold) },
            text = {
                if (bandPresetNames.isEmpty()) {
                    Text("暂无保存的频段预设", color = onSurfaceVariant, fontSize = 13.sp)
                } else {
                    Column {
                        bandPresetNames.forEach { name ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(name, modifier = Modifier.weight(1f), fontSize = 13.sp, color = onSurface)
                                    IconButton(
                                        onClick = {
                                            DolbyTunerManager.loadBandPreset(activity, name)
                                            DolbyTunerManager.setResultMsg("✅ 频段预设「$name」已加载")
                                            showLoadDialog = false
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            deleteTarget = name
                                            showDeleteConfirm = true
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, null, tint = Danger, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLoadDialog = false }) { Text("关闭") }
            }
        )
    }

    // ── 删除频段预设确认 ──
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除频段预设", fontWeight = FontWeight.Bold) },
            text = { Text("确定删除频段预设「$deleteTarget」？", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    DolbyTunerManager.deleteBandPreset(activity, deleteTarget)
                    DolbyTunerManager.setResultMsg("✅ 频段预设「$deleteTarget」已删除")
                    bandPresetNames = DolbyTunerManager.getBandPresetNames(activity)
                    deleteTarget = ""
                    showDeleteConfirm = false
                }) { Text("删除", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}
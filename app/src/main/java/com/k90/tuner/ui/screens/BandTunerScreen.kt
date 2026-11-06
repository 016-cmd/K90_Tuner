package com.k90.tuner.ui.screens

import androidx.compose.foundation.layout.WindowInsets

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k90.tuner.ui.components.GlassSurface
import com.k90.tuner.ui.theme.Appearance
import com.k90.tuner.service.DolbyTunerManager
import kotlinx.coroutines.launch

private val Accent get() = Appearance.accent
private val AccentDim get() = Appearance.accent.copy(alpha = 0.55f)
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
    val bandResultMsg by DolbyTunerManager.resultMsg.collectAsState()

    var showApplyDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showLoadDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var bandSaveName by remember { mutableStateOf("") }
    var bandPresetNames by remember { mutableStateOf(listOf<String>()) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportTab by remember { mutableStateOf(0) }
    var exportBandNames by remember { mutableStateOf(listOf<String>()) }
    var exportMixerNames by remember { mutableStateOf(listOf<String>()) }
    // 导入只允许 .json（选完再校验扩展名，部分文件管理器把 json 报成 text/plain）
    fun importDisplayName(u: android.net.Uri): String = runCatching {
        activity.contentResolver.query(u, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else ""
        } ?: u.lastPathSegment ?: ""
    }.getOrDefault("")
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val fileName = importDisplayName(uri)
                val text = runCatching {
                    activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                val msg = when {
                    !fileName.lowercase().endsWith(".json") ->
                        "❌ 只支持 .json 预设文件（当前：${fileName.ifBlank { "未知文件" }}）"
                    text.isNullOrBlank() -> "❌ 读取文件失败（可能不是文本文件）"
                    else -> DolbyTunerManager.importPresetText(activity, text)
                }
                DolbyTunerManager.setResultMsg(msg)
            }
        }
    }

    // 每次进入频段页：以当前生效文件刷新三场景基准（保留偏移），并同步模式显示
    LaunchedEffect(Unit) {
        DolbyTunerManager.refreshBandBaselines(activity)
    }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
            GlassSurface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Equalizer, null, tint = Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("当前调音模式：$currentModeName", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("频段调整仅对当前激活模式生效，另一模式保持独立。切换模式请在模块 WebUI 中操作。", color = onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                }
            }

            // 操作结果提示（保存/加载/删除频段预设、应用、重置等）
            if (bandResultMsg.isNotBlank()) {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text(
                        bandResultMsg,
                        color = onSurface, fontSize = 11.sp, lineHeight = 15.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // ── 杜比实时层（卡片自带展开/收起，默认收起） ──
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Dolby · Realtime Layer", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Text("实时层 · GEQ 20 段", color = onSurfaceVariant, fontSize = 9.sp)
            }
            DapRealtimeSection()
            Spacer(Modifier.height(14.dp))
            // ── 分层引导线 ──
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(Modifier.weight(1f), color = onSurfaceVariant.copy(alpha = 0.25f))
                Text(
                    "杜比文件层 · Dolby File Layer",
                    color = onSurfaceVariant, fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                HorizontalDivider(Modifier.weight(1f), color = onSurfaceVariant.copy(alpha = 0.25f))
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
            GlassSurface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
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
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                exportBandNames = DolbyTunerManager.getBandPresetNames(activity)
                                exportMixerNames = DolbyTunerManager.getPresetNames(activity)
                                exportTab = 0
                                showExportDialog = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)
                        ) {
                            Icon(Icons.Default.Share, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("导出预设", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { importPicker.launch(arrayOf("application/json", "text/plain")) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)
                        ) {
                            Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("导入预设", fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "⚠ 应用后仅当前激活模式（$currentModeName）的频段会写入模块，将重启音频服务即时生效。请不要频繁应用。",
                color = onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp
            )

            Spacer(Modifier.height(96.dp))
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
                            GlassSurface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(name, modifier = Modifier.weight(1f), fontSize = 13.sp, color = onSurface)
                                    TextButton(
                                        onClick = {
                                            DolbyTunerManager.loadBandPreset(activity, name)
                                            DolbyTunerManager.setResultMsg("✅ 频段预设「$name」已加载")
                                            showLoadDialog = false
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) { Text("应用", color = Accent, fontSize = 12.sp) }
                                    TextButton(
                                        onClick = {
                                            deleteTarget = name
                                            showDeleteConfirm = true
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) { Text("删除", color = Danger, fontSize = 12.sp) }
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

    // ── 导出预设弹窗（Tab 切换 频段 / 调音台）──
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出预设", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    TabRow(
                        selectedTabIndex = exportTab,
                        containerColor = Color.Transparent
                    ) {
                        Tab(
                            selected = exportTab == 0,
                            onClick = { exportTab = 0 },
                            text = { Text("频段预设", fontSize = 12.sp) }
                        )
                        Tab(
                            selected = exportTab == 1,
                            onClick = { exportTab = 1 },
                            text = { Text("调音台预设", fontSize = 12.sp) }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val names = if (exportTab == 0) exportBandNames else exportMixerNames
                    if (names.isEmpty()) {
                        Text("暂无保存的预设", color = onSurfaceVariant, fontSize = 13.sp)
                    } else {
                        Text("导出到：内部存储/Download（.k90eq.json）", fontSize = 11.sp, color = onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        names.forEach { name ->
                            GlassSurface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(name, modifier = Modifier.weight(1f), fontSize = 13.sp, color = onSurface)
                                    TextButton(
                                        onClick = {
                                            val kind = if (exportTab == 0) "band" else "mixer"
                                            DolbyTunerManager.setResultMsg(
                                                DolbyTunerManager.exportPresetToDownload(activity, kind, name)
                                            )
                                            showExportDialog = false
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) { Text("导出", color = Accent, fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("关闭") }
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
package com.k90.tuner.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Equalizer
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
}
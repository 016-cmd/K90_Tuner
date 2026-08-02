package com.k90.tuner.ui.screens

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * 主框架 — 内容区 + 底部液态玻璃 Dock 栏。
 * 三个 Tab：杜比调音台 / 频段调节 / 设置。
 */
@Composable
fun MainApp(activity: Activity) {
    var currentTab by remember { mutableIntStateOf(0) }

    fun switchTab(index: Int) {
        if (index != currentTab) currentTab = index
    }

    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.red * 0.299f + colors.background.green * 0.587f + colors.background.blue * 0.114f < 0.5f

    // 液态玻璃：backdrop 不画底色，让底层内容透过来
    val backdrop = rememberLayerBackdrop {
        drawContent()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = currentTab,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(animationSpec = tween(300)) { it * dir } + fadeIn(animationSpec = tween(300))) togetherWith
                (slideOutHorizontally(animationSpec = tween(300)) { -it * dir } + fadeOut(animationSpec = tween(300)))
            },
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) { tab ->
            when (tab) {
                0 -> DolbyTunerScreen(activity = activity)
                1 -> BandTunerScreen(activity = activity)
                2 -> SettingsScreen(activity = activity)
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 32.dp, vertical = 16.dp)
                .height(58.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(28.dp) },
                    effects = {
                        vibrancy()
                        blur(6.dp.toPx())
                        lens(10.dp.toPx(), 18.dp.toPx())
                    },
                    onDrawSurface = {
                        drawRect(if (isDark) Color.Black.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.12f))
                    }
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val tabs = listOf(
                    DockTab("调音台", Icons.Default.Tune),
                    DockTab("频段", Icons.Default.Equalizer),
                    DockTab("设置", Icons.Default.Settings)
                )
                tabs.forEachIndexed { index, tab ->
                    val selected = currentTab == index
                    val tabColor = if (selected) colors.primary
                                   else colors.onSurfaceVariant.copy(alpha = if (isDark) 0.5f else 0.6f)

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .then(
                                if (selected) Modifier.background(
                                    if (isDark) Color.White.copy(alpha = 0.12f)
                                    else Color.Black.copy(alpha = 0.05f),
                                    RoundedCornerShape(14.dp)
                                ) else Modifier
                            )
                            .clickable { switchTab(index) }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(tab.icon, contentDescription = tab.label, modifier = Modifier.size(22.dp), tint = tabColor)
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(tab.label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = tabColor)
                    }
                }
            }
        }
    }
}

data class DockTab(val label: String, val icon: ImageVector)
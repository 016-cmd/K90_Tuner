package com.k90.tuner.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import com.k90.tuner.ui.AppContextHolder
import com.k90.tuner.ui.screens.MainApp
import com.k90.tuner.ui.theme.K90TunerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContextHolder.ctx = applicationContext
        enableEdgeToEdge()

        // 初始化 Coil 图片加载器（壁纸图片加载）
        SingletonImageLoader.setSafe { context: PlatformContext ->
            ImageLoader.Builder(context)
                .crossfade(true)
                .build()
        }

        setContent {
            K90TunerTheme {
                MainApp(activity = this@MainActivity)
            }
        }
    }
}
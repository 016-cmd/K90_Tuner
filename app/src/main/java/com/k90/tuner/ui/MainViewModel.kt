package com.k90.tuner.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.k90.tuner.service.ModuleDetector
import com.k90.tuner.service.WsaShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    data class ModuleStatus(
        val isInstalled: Boolean = false,
        val version: String = "检测中...",
        val deviceCode: String = "检测中...",
        val isDeviceMatch: Boolean = false,
        val isChecking: Boolean = true
    )

    private val _moduleStatus = MutableStateFlow(ModuleStatus())
    val moduleStatus: StateFlow<ModuleStatus> = _moduleStatus.asStateFlow()

    private val _hasRoot = MutableStateFlow<Boolean?>(null)
    val hasRoot: StateFlow<Boolean?> = _hasRoot.asStateFlow()

    /**
     * 应用是否可编辑（机型匹配 且 模块已安装 且 已授权 root）。
     * 由 moduleStatus 与 hasRoot 通过 combine 合成的响应式状态：
     * 任一源变化都会立即发出新值，UI 的 collectAsState 必触发重组，
     * 彻底避免首帧用未就绪数据算出 false 后不再刷新导致的"请先授权"卡住。
     */
    val canEdit: StateFlow<Boolean> = combine(moduleStatus, hasRoot) { st, root ->
        st.isDeviceMatch && st.isInstalled && root == true
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    private val prefs by lazy { AppContextHolder.ctx.getSharedPreferences("k90_tuner", Context.MODE_PRIVATE) }

    init {
        if (prefs.getBoolean("root_granted", false)) {
            // 之前已授权 → 静默 su 验证；Magisk 永久授权不弹窗
            autoDetect()
        } else {
            // 首次使用 → 不调 su，等用户手动激活
            _moduleStatus.update { it.copy(isChecking = false, version = "点击下方按钮激活") }
        }
    }

    private fun autoDetect() {
        viewModelScope.launch(Dispatchers.IO) {
            val rootOk = WsaShell.hasRoot()
            _hasRoot.value = rootOk
            if (rootOk) {
                prefs.edit().putBoolean("root_granted", true).apply()
                doDetectAndLoad()
            } else {
                prefs.edit().putBoolean("root_granted", false).apply()
                _moduleStatus.update { it.copy(isChecking = false, version = "点击下方按钮激活") }
            }
        }
    }

    fun requestRootAndDetect() {
        viewModelScope.launch(Dispatchers.IO) {
            _moduleStatus.update { it.copy(isChecking = true) }
            val rootOk = WsaShell.hasRoot()
            _hasRoot.value = rootOk
            if (rootOk) {
                prefs.edit().putBoolean("root_granted", true).apply()
                // 用户主动点击（激活/刷新）→ 强制重新检测
                doDetectAndLoad(force = true)
            } else {
                _moduleStatus.update { it.copy(isChecking = false, version = "请先在 Magisk 中授权 Root") }
            }
        }
    }

    private fun doDetectAndLoad(force: Boolean = false) {
        if (force) ModuleDetector.forceDetect() else ModuleDetector.detect()
        _moduleStatus.update {
            it.copy(
                isInstalled = ModuleDetector.isInstalled,
                version = ModuleDetector.installedVersion,
                deviceCode = ModuleDetector.deviceCode,
                isDeviceMatch = ModuleDetector.isDeviceMatch,
                isChecking = false
            )
        }
    }
}
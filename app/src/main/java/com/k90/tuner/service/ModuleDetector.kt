package com.k90.tuner.service

/**
 * K90 音质模块检测器。
 *
 * 通过 Root 检测 Magisk 模块安装状态 + 机型代号。
 * 机型代号不匹配（非 annibale / REDMI K90 标准版）时，APP 不可用。
 */
object ModuleDetector {

    @Volatile var isInstalled = false; private set
    @Volatile var installedVersion = "未安装"; private set
    @Volatile var deviceCode = "未知"; private set
    /** 机型代号是否匹配 K90 标准版（annibale） */
    @Volatile var isDeviceMatch = false; private set

    private const val MODULE_NAME = "k90_audio_plus"
    private const val TARGET_DEVICE = "annibale"

    /** 是否已完成首次检测（防多余 shell 检测，仅进入 APP 时检测一次） */
    @Volatile private var detectedOnce = false

    /** 动态检测模块根路径 */
    private var _moduleBase: String? = null
    private fun moduleBase(): String {
        if (_moduleBase == null) {
            _moduleBase = findModuleBase()
        }
        return _moduleBase ?: "/data/adb/modules/$MODULE_NAME"
    }

    private fun findModuleBase(): String? {
        for (prefix in listOf("/data/adb/modules", "/data/adb/ksu/modules", "/data/adb/ap/modules")) {
            val path = "$prefix/$MODULE_NAME"
            val result = WsaShell.execSyncCmd("[ -d $path ] && echo yes || echo no")
            if (result.contains("yes")) return path
        }
        return null
    }

    /**
     * 检测模块 + 机型。为防多余 shell 检测，仅在首次真正执行；
     * 之后调用直接返回（除非设备/模块状态需手动刷新，可调用 [forceDetect]）。
     */
    fun detect() {
        if (detectedOnce) return
        doDetect()
        detectedOnce = true
    }

    /** 强制重新检测（用于用户手动"刷新"） */
    fun forceDetect() {
        doDetect()
        detectedOnce = true
    }

    private fun doDetect() {
        // 1. 机型代号（系统属性，稳定）
        deviceCode = readDeviceCode()
        isDeviceMatch = deviceCode.equals(TARGET_DEVICE, ignoreCase = true)

        // 2. 模块安装
        val modProp = WsaShell.execSyncCmd("cat ${moduleBase()}/module.prop 2>/dev/null")
        if (modProp.isNotBlank()) {
            isInstalled = true
            installedVersion = trimV(modProp)
        } else {
            isInstalled = false; installedVersion = "未安装"
        }
    }

    /**
     * 读取机型代号。
     * 逻辑与模块 customize.sh 一致：**优先匹配目标机型代号 annibale**，
     * 轮询三个 getprop，只要任一个 == annibale 就采用并判定匹配；
     * 若都不匹配，才回退取第一个非空值作为显示（此时 isDeviceMatch=false）。
     * 这样不会因为 ro.product.vendor.device 返回通用值(mivendor)而漏判正确机型。
     */
    private fun readDeviceCode(): String {
        val props = listOf(
            "ro.product.vendor.device",
            "ro.product.device",
            "ro.build.product"
        )
        var firstNonBlank: String? = null
        for (prop in props) {
            val v = WsaShell.execSyncCmd("getprop $prop").trim()
            if (v.isBlank()) continue
            if (firstNonBlank == null) firstNonBlank = v
            // 命中目标机型 → 立即采用
            if (v.equals(TARGET_DEVICE, ignoreCase = true)) return v
        }
        return firstNonBlank ?: "未知"
    }

    /** 解析 version= 行（允许前导空格），返回版本值 */
    private fun trimV(modProp: String): String {
        return modProp.lines()
            .firstOrNull { it.trim().startsWith("version=") }
            ?.trim()
            ?.substringAfter("=")
            ?.trim() ?: "未知版本"
    }
}
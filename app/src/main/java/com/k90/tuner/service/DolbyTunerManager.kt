package com.k90.tuner.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * K90（标准版）杜比调音台管理器
 *
 * 适配 REDMI K90 标准版模块（id=k90_audio_plus）。
 * 与 PM 版的核心差异：
 *  - 双调音模式（哈曼 dax-default.xml / 柏林 dax-mode-1.xml）。
 *    采用方案 A：普通参数同时写两份模板保证切模式不丢失；频段按当前激活模式写对应模板。
 *  - 仅 L/R 两声道（无低音单元 surround）。
 *  - 写盘后复用模块 switch_mode.sh 重新挂载当前模式 + 重启音频服务即时生效。
 *  - factory 为模块内新建的两份出厂文件，重置时按当前模式恢复对应出厂文件。
 *
 * 写入流程：
 *   ① cat /odm/etc/dolby/dax-default.xml 读当前系统生效值作为模板
 *   ② Kotlin 正则替换修改参数（普通参数 + 当前模式频段）
 *   ③ 写入 APP 内部目录文件（context.filesDir，无需 root）
 *   ④ su -c cp 覆盖到模块 Link 目录两份模板 + vendor 目录两份模板
 *   ⑤ sh switch_mode.sh [harman|berlin] 重新挂载当前模式 + 重启 audioserver
 */
object DolbyTunerManager {

    private const val TAG = "K90_DolbyTuner"
    private const val MODULE_NAME = "k90_audio_plus"

    // ── 模式常量 ──
    const val MODE_HARMAN = 0
    const val MODE_BERLIN = 1

    /** 动态检测模块根路径（兼容 Magisk/KSU/APatch） */
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

    // ═══════════════════════════════════════════
    //  路径常量（标准版双模式模板）
    // ═══════════════════════════════════════════

    /** 系统杜比生效路径（切换模式也挂这里，只读用于解析模板） */
    private const val DAX_SYS = "/odm/etc/dolby/dax-default.xml"

    // 哈曼模板（Link + vendor）
    private val LINK_HARMAN get() = "${moduleBase()}/Link/odm/etc/dolby/dax-default.xml"
    private val VENDOR_HARMAN get() = "${moduleBase()}/vendor/etc/dolby/dax-default.xml"
    // 柏林模板（Link + vendor）
    private val LINK_BERLIN get() = "${moduleBase()}/Link/odm/etc/dolby/dax-mode-1.xml"
    private val VENDOR_BERLIN get() = "${moduleBase()}/vendor/etc/dolby/dax-mode-1.xml"

    // factory（模块内新建，两份出厂原文件，不参与挂载，仅 APP 重置使用）
    private val FACTORY_HARMAN get() = "${moduleBase()}/factory/etc/dolby/dax-default.xml"
    private val FACTORY_BERLIN get() = "${moduleBase()}/factory/etc/dolby/dax-mode-1.xml"

    /** 模块版本快照文件（用于模块更新后频段复位） */
    private const val VERSION_SNAPSHOT = "/data/local/tmp/k90_tuner_module_version.txt"

    /** 模块自身记忆的模式文件（0=哈曼 1=柏林，由 switch_mode.sh 写入） */
    private const val MODE_FILE = "/data/local/tmp/k90_tuning_mode"

    // ═══════════════════════════════════════════
    //  数据模型
    // ═══════════════════════════════════════════

    data class DolbyParams(
        val dialogEnhancerEnable: Boolean = true,
        val dialogEnhancerAmount: Int = 5,
        val dialogEnhancerDucking: Int = 0,
        val bassEnhancerEnable: Boolean = false,
        val virtualBassProcessEnable: Boolean = false,
        val surroundDecoderEnable: Boolean = true,
        val surroundBoost: Int = 105,
        val volumeLevelerEnable: Boolean = true,
        val virtualizerEnable: Boolean = false,
        val virtualizerStartBand: Int = 0,
        val calibrationBoost: Int = 0,
        val volmaxBoost: Int = 50,
        val peakValue: Int = 1024,
        val hearingProtectionEnable: Boolean = false,

        // ── 快速开关区（低频提取） ──
        val bassExtractionEnable: Boolean = false,

        // ── 高级区（BE精细，三场景同步） ──
        val bassEnhancerBoost: Int = 200,
        val bassEnhancerCutoffFrequency: Int = 150,

        // ── 高级区（VB精细，三场景同步） ──
        val virtualBassMode: Int = 3,
        val virtualBassOverallGain: Int = 35,
        val virtualBassMixLow: Int = 30,
        val virtualBassMixHigh: Int = 150,

        // ── 高级区（低频提取精细，三场景同步） ──
        val bassExtractionCutoffFrequency: Int = 65
    )

    // ═══════════════════════════════════════════
    //  频段调节（band_optimizer）数据模型  — 仅 L/R
    //  ═══════════════════════════════════════════

    /** 三场景 speaker_landscape_X 的 tuning 名 */
    private val SCENE_NAMES = listOf(
        "speaker_landscape_large",
        "speaker_landscape_medium",
        "speaker_landscape_small"
    )

    val ALL_BANDS = listOf(
        47, 141, 234, 328, 469, 656, 844, 1031, 1313, 1688,
        2250, 3000, 3750, 4688, 5813, 7125, 9000, 11250, 13875, 19688
    )

    enum class BandChannel(val xmlKey: String) {
        LEFT("gain_left"),
        RIGHT("gain_right")
    }

    data class BandOffsets(
        val left: MutableMap<Int, Int> = ALL_BANDS.associateWith { 0 }.toMutableMap(),
        val right: MutableMap<Int, Int> = ALL_BANDS.associateWith { 0 }.toMutableMap()
    )

    data class SceneBaselines(
        val large: MutableMap<Int, IntArray> = mutableMapOf(),
        val medium: MutableMap<Int, IntArray> = mutableMapOf(),
        val small: MutableMap<Int, IntArray> = mutableMapOf()
    )

    // ═══════════════════════════════════════════
    //  状态
    // ═══════════════════════════════════════════

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isApplying = MutableStateFlow(false)
    val isApplying: StateFlow<Boolean> = _isApplying.asStateFlow()

    private val _params = MutableStateFlow(DolbyParams())
    val params: StateFlow<DolbyParams> = _params.asStateFlow()

    /** 当前激活的调音模式（0=哈曼 1=柏林；初始 -1=待检测，避免未检测时误显哈曼） */
    private val _currentMode = MutableStateFlow(-1)
    val currentMode: StateFlow<Int> = _currentMode.asStateFlow()

    private val _bandOffsets = MutableStateFlow(BandOffsets())
    val bandOffsets: StateFlow<BandOffsets> = _bandOffsets.asStateFlow()

    private val _bandBaselines = MutableStateFlow(SceneBaselines())
    val bandBaselines: StateFlow<SceneBaselines> = _bandBaselines.asStateFlow()

    private val _hasBandsParsed = MutableStateFlow(false)
    val hasBandsParsed: StateFlow<Boolean> = _hasBandsParsed.asStateFlow()

    private val _statusMsg = MutableStateFlow("")
    val statusMsg: StateFlow<String> = _statusMsg.asStateFlow()

    private val _resultMsg = MutableStateFlow("")
    val resultMsg: StateFlow<String> = _resultMsg.asStateFlow()

    /** 模块 factory 目录是否存在（无则禁用一键重置） */
    private val _hasFactoryDax = MutableStateFlow(false)
    val hasFactoryDax: StateFlow<Boolean> = _hasFactoryDax.asStateFlow()

    private var hasParsedOnce = false
    private var factoryWarnShownThisSession = false

    /** 当前模式名称（供 UI 展示；-1=未检测到） */
    val currentModeName: String
        get() = when (_currentMode.value) {
            MODE_HARMAN -> "哈曼"
            MODE_BERLIN -> "柏林"
            else -> "检测中…"
        }

    // ═══════════════════════════════════════════
    //  初始化 & 读取
    // ═══════════════════════════════════════════

    suspend fun loadParams(context: Context): Boolean = withContext(Dispatchers.IO) {
        _isLoading.value = true
        try {
            ModuleDetector.forceDetect()
            if (!ModuleDetector.isInstalled) {
                // 冷启动(重启APP)时 root/模块 mount 可能尚未就绪 → 首帧检测易误判"未安装"。
                // ModuleDetector.detectedOnce 会缓存首次结果导致切 tab 后不重新确认，
                // 故此处每次都用 forceDetect 强制重新检测，并轮询等待 root 就绪(最多约 3.5s)。
                var waited = 0
                while (!ModuleDetector.isInstalled && waited < 5) {
                    delay(700)
                    ModuleDetector.forceDetect()
                    waited++
                }
                if (!ModuleDetector.isInstalled) {
                    _statusMsg.value = "❌ 检测到未安装 K90 音质优化 by 016. 模块，请先安装模块"
                    _isLoading.value = false
                    return@withContext false
                }
            }
            if (!ModuleDetector.isDeviceMatch) {
                _statusMsg.value = "❌ 机型不匹配，本 APP 仅适配 REDMI K90 标准版（annibale）"
                _isLoading.value = false
                return@withContext false
            }

            checkModuleVersionChanged(context)
            detectAndCacheFactory()

            val xml = WsaShell.execSyncCmd("cat $DAX_SYS 2>/dev/null")
            if (xml.isBlank()) {
                _statusMsg.value = "无法读取杜比文件（模块未安装或无权限）"
                _isLoading.value = false
                return@withContext false
            }
            // 用同一份当前生效文件实时判定模式（所见即真实）
            setCurrentModeByFingerprint(xml)
            parseAndSet(xml)
            hasParsedOnce = true
            _statusMsg.value = ""
            _isLoading.value = false
            true
        } catch (e: Exception) {
            _statusMsg.value = "读取失败: ${e.message}"
            _isLoading.value = false
            false
        }
    }

    /** 检测 factory 目录（任一模式出厂文件存在即视为支持重置） */
    private fun detectAndCacheFactory() {
        val harman = WsaShell.execSyncCmd("[ -f $FACTORY_HARMAN ] && echo yes || echo no").contains("yes")
        val berlin = WsaShell.execSyncCmd("[ -f $FACTORY_BERLIN ] && echo yes || echo no").contains("yes")
        _hasFactoryDax.value = harman && berlin
    }

    fun checkFactoryDaxExists(): Boolean {
        detectAndCacheFactory()
        return _hasFactoryDax.value
    }

    fun isFactoryWarnShownThisSession(): Boolean = factoryWarnShownThisSession
    fun markFactoryWarnShown() { factoryWarnShownThisSession = true }

    // ═══════════════════════════════════════════
    //  双模式检测
    // ═══════════════════════════════════════════

    /**
     * 依据**当前生效 dax 内容**实时判定调音模式（所见即真实，指纹优先）。
     *
     * ⚠ 学习 PM 的"所见即真实"哲学：以当前生效 dax 文件的 fingerprint(threshold_high) 为准。
     * 该指纹在 APP 全部操作（调频段/普通参数/重置）下都不会被修改，稳定可靠（哈曼-32/柏林-36）。
     * 路径：双模式都通过 switch_mode.sh bind 到 /odm/etc/dolby/dax-default.xml，读此路径即当前真实生效。
     *
     * 判定逻辑：
     *   1) 指纹判定优先——实时读生效文件，绝不设默认哈曼兜底。
     *   2) 指纹无法判定(如数值异常)时，回退参考模块记忆文件 /data/local/tmp/k90_tuning_mode
     *      （0=哈曼 1=柏林，由 switch_mode.sh 写入）作为兜底；仍无法判定则不覆盖。
     */
    private fun setCurrentModeByFingerprint(xml: String) {
        val fp = fingerprintMode(xml)
        if (fp >= 0) {
            _currentMode.value = fp
            return
        }
        // 指纹兜底：参考模块写入的记忆文件
        val back = moduleMemoryMode()
        if (back >= 0) _currentMode.value = back
    }

    /** 读模块记忆文件（0=哈曼 1=柏林），读不到/异常返回 -1 */
    private fun moduleMemoryMode(): Int {
        val mem = WsaShell.execSyncCmd("cat $MODE_FILE 2>/dev/null").trim()
        return when (mem) {
            "0" -> MODE_HARMAN
            "1" -> MODE_BERLIN
            else -> -1
        }
    }

    /** 无参版本：内部重新读取当前生效文件判定（供 applyChanges/reset 使用） */
    private fun detectCurrentMode() {
        val xml = WsaShell.execSyncCmd("cat $DAX_SYS 2>/dev/null")
        if (xml.isNotBlank()) setCurrentModeByFingerprint(xml)
    }

    /**
     * 用 band_regulator threshold_high 指纹判别模式（基于传入的生效 dax 内容实时判定）。
     * 哈曼：大-54 / 中-32 / 小-19；柏林：大-58 / 中-36 / 小-23。
     * 命中任一场景指纹即判；全无命中返回 -1。
     */
    private fun fingerprintMode(xml: String): Int {
        try {
            if (xml.isBlank()) return -1
            // 对三场景分别取 47Hz threshold_high，命中任一模式指纹即判
            for (scene in SCENE_NAMES) {
                val start = xml.indexOf("<tuning name=\"$scene\"")
                if (start < 0) continue
                val end = xml.indexOf("</tuning>", start)
                val seg = if (end > start) xml.substring(start, end) else xml.substring(start)
                val m = Regex("<band_regulator frequency=\"47\"[^>]*?threshold_high=\"(-?\\d+)\"").find(seg)
                val v = m?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (v == -32) return MODE_HARMAN
                if (v == -36) return MODE_BERLIN
            }
            return -1
        } catch (_: Exception) { return -1 }
    }

    // ═══════════════════════════════════════════
    //  解析核心
    // ═══════════════════════════════════════════

    private fun parseAndSet(xml: String) {
        _params.value = parseParams(xml)
        parseBandOptimizers(xml)
    }

    private fun parseParams(xml: String): DolbyParams {
        return DolbyParams(
            dialogEnhancerEnable = extractBool(xml, "dialog-enhancer-enable"),
            dialogEnhancerAmount = extractInt(xml, "dialog-enhancer-amount", 5),
            dialogEnhancerDucking = extractInt(xml, "dialog-enhancer-ducking", 0),
            bassEnhancerEnable = extractBool(xml, "bass-enhancer-enable"),
            virtualBassProcessEnable = extractBool(xml, "virtual-bass-process-enable"),
            surroundDecoderEnable = extractBool(xml, "surround-decoder-enable"),
            surroundBoost = extractInt(xml, "surround-boost", 105),
            volumeLevelerEnable = extractInt(xml, "volume-leveler-amount", 0) == 1,
            virtualizerEnable = extractBool(xml, "virtualizer-enable"),
            virtualizerStartBand = extractInt(xml, "virtualizer-start-band", 0),
            calibrationBoost = extractInt(xml, "calibration-boost", 0),
            volmaxBoost = extractInt(xml, "volmax-boost", 50),
            peakValue = extractInt(xml, "peak-value", 1024),
            hearingProtectionEnable = extractBool(xml, "hearing-protection-enable"),
            bassExtractionEnable = extractBool(xml, "bass-extraction-enable"),
            bassEnhancerBoost = extractTuningInt(xml, "bass-enhancer-boost", 200),
            bassEnhancerCutoffFrequency = extractTuningInt(xml, "bass-enhancer-cutoff-frequency", 150),
            virtualBassMode = extractTuningInt(xml, "virtual-bass-mode", 3),
            virtualBassOverallGain = extractTuningInt(xml, "virtual-bass-overall-gain", 35),
            virtualBassMixLow = extractTuningIntRangeLow(xml, "virtual-bass-mix-freqs", 30),
            virtualBassMixHigh = extractTuningIntRangeHigh(xml, "virtual-bass-mix-freqs", 150),
            bassExtractionCutoffFrequency = extractTuningInt(xml, "bass-extraction-cutoff-frequency", 65)
        )
    }

    fun updateParams(new: DolbyParams) { _params.value = new }
    fun setStatusMsg(msg: String) { _statusMsg.value = msg }
    fun setResultMsg(msg: String) { _resultMsg.value = msg }
    fun clearResultMsg() { _resultMsg.value = "" }

    // ═══════════════════════════════════════════
    //  应用修改（双模式写入）
    // ═══════════════════════════════════════════

    /**
     * 将当前 params + 频段偏移写入模块模板，复用 switch_mode.sh 重新挂载 + 重启音频服务。
     *
     * 双模式写入策略（方案A）：
     *   - 普通杜比参数：同时写入哈曼/柏林两份模板（Link + vendor），切模式不丢。
     *   - 频段 band_optimizer：仅写入当前激活模式对应模板（保持两模式频段差异）。
     *   - 完成后调用 switch_mode.sh 当前模式，重新 mount + 重启 audioserver。
     */
    suspend fun applyChanges(context: Context): Boolean = withContext(Dispatchers.IO) {
        _isApplying.value = true
        _statusMsg.value = ""
        _resultMsg.value = ""
        try {
            val rootCheck = WsaShell.execSyncCmd("echo OK")
            if (!rootCheck.contains("OK")) {
                _resultMsg.value = "❌ 无 Root 权限，请在 Magisk 中授予本应用 Root 权限"
                _isApplying.value = false
                return@withContext false
            }
            ModuleDetector.detect()
            if (!ModuleDetector.isInstalled) {
                _resultMsg.value = "❌ 检测到未安装 K90 音质优化 by 016. 模块，请先安装模块"
                _isApplying.value = false
                return@withContext false
            }
            if (!ModuleDetector.isDeviceMatch) {
                _resultMsg.value = "❌ 机型不匹配，本 APP 仅适配 REDMI K90 标准版（annibale）"
                _isApplying.value = false
                return@withContext false
            }

            // 1. 读取当前系统生效文件作为基础模板
            val original = WsaShell.execSyncCmd("cat $DAX_SYS 2>/dev/null")
            if (original.isBlank()) {
                _resultMsg.value = "❌ 无法读取杜比文件，请确认模块已正确安装"
                _isApplying.value = false
                return@withContext false
            }

            // 1.5 依据当前生效文件实时判定模式（写入必须以真实生效模式为准）
            detectCurrentMode()
            if (_currentMode.value < 0) {
                _resultMsg.value = "❌ 无法识别当前调音模式（既非哈曼也非柏林），已中止，请检查模块状态"
                _isApplying.value = false
                return@withContext false
            }

            // 2. 应用普通参数
            var paramsXml = applyParamsToXml(original, _params.value)
            // 3. 应用当前模式频段偏移
            paramsXml = applyBandOffsets(paramsXml)

            // 4. 写入当前模式模板（Link + vendor）
            val mode = _currentMode.value
            val curLink = if (mode == MODE_HARMAN) LINK_HARMAN else LINK_BERLIN
            val curVendor = if (mode == MODE_HARMAN) VENDOR_HARMAN else VENDOR_BERLIN
            writeInternalAndCp(context, paramsXml, listOf(curLink, curVendor))

            // 5. 普通参数同步写入另一模式模板（不含当前模式频段）
            val otherMode = if (mode == MODE_HARMAN) MODE_BERLIN else MODE_HARMAN
            val otherXml = readOtherModeTemplate(otherMode)
            if (otherXml.isNotBlank()) {
                val otherParamsXml = applyParamsToXml(otherXml, _params.value)
                val otherLink = if (otherMode == MODE_HARMAN) LINK_HARMAN else LINK_BERLIN
                val otherVendor = if (otherMode == MODE_HARMAN) VENDOR_HARMAN else VENDOR_BERLIN
                writeInternalAndCp(context, otherParamsXml, listOf(otherLink, otherVendor))
            }

            // 6. 复用模块 switch_mode.sh 重新挂载当前模式 + 重启音频服务
            val modeArg = if (mode == MODE_HARMAN) "harman" else "berlin"
            val switchOk = WsaShell.execSyncCmd(
                "sh ${moduleBase()}/switch_mode.sh $modeArg && echo OK || echo FAIL"
            ).contains("OK")

            loadParams(context)

            if (switchOk) {
                _resultMsg.value = "✅ 参数已保存并生效（${currentModeName}模式）！音频服务已重启，效果即时生效。请不要频繁应用参数。"
            } else {
                _resultMsg.value = "✅ 参数已保存（${currentModeName}模式）！但音频服务重启失败，请重启手机后生效。"
            }
            _isApplying.value = false
            true
        } catch (e: Exception) {
            _resultMsg.value = "❌ 应用失败: ${e.message}"
            _isApplying.value = false
            false
        }
    }

    private fun readOtherModeTemplate(mode: Int): String {
        val p = if (mode == MODE_HARMAN) LINK_HARMAN else LINK_BERLIN
        return WsaShell.execSyncCmd("cat $p 2>/dev/null")
    }

    private fun writeInternalAndCp(context: Context, xml: String, targets: List<String>) {
        val internalFile = File(context.filesDir, "k90_tuner_dax.xml")
        internalFile.writeText(xml, Charsets.UTF_8)
        val internalPath = internalFile.absolutePath
        for (t in targets) {
            WsaShell.execSyncCmd("cp -f '$internalPath' '$t' && chmod 644 '$t'")
        }
    }

    /** 仅应用普通参数（speaker + tuning），不改频段 */
    private fun applyParamsToXml(xml: String, p: DolbyParams): String {
        var out = xml
        out = replaceInSpeaker(out, "dialog-enhancer-enable", boolToXml(p.dialogEnhancerEnable))
        out = replaceInSpeaker(out, "dialog-enhancer-amount", p.dialogEnhancerAmount.toString())
        out = replaceInSpeaker(out, "dialog-enhancer-ducking", p.dialogEnhancerDucking.toString())
        out = replaceInSpeaker(out, "bass-enhancer-enable", boolToXml(p.bassEnhancerEnable))
        out = replaceInSpeaker(out, "virtual-bass-process-enable", boolToXml(p.virtualBassProcessEnable))
        out = replaceInSpeaker(out, "surround-decoder-enable", boolToXml(p.surroundDecoderEnable))
        out = replaceInSpeaker(out, "surround-boost", p.surroundBoost.toString())
        out = replaceInSpeaker(out, "volume-leveler-amount", if (p.volumeLevelerEnable) "1" else "0")
        out = replaceInSpeaker(out, "virtualizer-enable", boolToXml(p.virtualizerEnable))
        out = replaceInSpeaker(out, "virtualizer-start-band", p.virtualizerStartBand.toString())
        out = replaceInSpeaker(out, "calibration-boost", p.calibrationBoost.toString())
        out = replaceInSpeaker(out, "volmax-boost", p.volmaxBoost.toString())
        out = replaceInSpeaker(out, "peak-value", p.peakValue.toString())
        out = replaceInSpeaker(out, "hearing-protection-enable", boolToXml(p.hearingProtectionEnable))
        out = replaceInAllTunings(out, "bass-enhancer-boost", p.bassEnhancerBoost.toString())
        out = replaceInAllTunings(out, "bass-enhancer-cutoff-frequency", p.bassEnhancerCutoffFrequency.toString())
        out = replaceInAllTunings(out, "virtual-bass-mode", p.virtualBassMode.toString())
        out = replaceInAllTunings(out, "virtual-bass-overall-gain", p.virtualBassOverallGain.toString())
        out = replaceMixFreqs(out, p.virtualBassMixLow, p.virtualBassMixHigh)
        out = replaceInAllTunings(out, "bass-extraction-enable", boolToXml(p.bassExtractionEnable))
        out = replaceInAllTunings(out, "bass-extraction-cutoff-frequency", p.bassExtractionCutoffFrequency.toString())
        return out
    }

    // ═══════════════════════════════════════════
    //  重置（按当前模式恢复出厂）
    // ═══════════════════════════════════════════

    /**
     * 重置为模块默认。先判当前模式，读对应 factory 出厂文件写回当前模式模板；
     * 另一模式也恢复其自身出厂文件，然后 switch_mode 重挂载重启。
     */
    suspend fun resetToModuleDefault(context: Context): Boolean = withContext(Dispatchers.IO) {
        _isApplying.value = true
        _statusMsg.value = ""
        _resultMsg.value = ""
        try {
            val rootCheck = WsaShell.execSyncCmd("echo OK")
            if (!rootCheck.contains("OK")) {
                _resultMsg.value = "❌ 无 Root 权限，请在 Magisk 中授予本应用 Root 权限"
                _isApplying.value = false
                return@withContext false
            }
            ModuleDetector.detect()
            if (!ModuleDetector.isInstalled) {
                _resultMsg.value = "❌ 检测到未安装 K90 音质优化 by 016. 模块，请先安装模块"
                _isApplying.value = false
                return@withContext false
            }
            if (!ModuleDetector.isDeviceMatch) {
                _resultMsg.value = "❌ 机型不匹配，本 APP 仅适配 REDMI K90 标准版（annibale）"
                _isApplying.value = false
                return@withContext false
            }

            // 0.8 依据当前生效文件实时判定模式（重置必须以真实生效模式为准）
            detectCurrentMode()
            if (_currentMode.value < 0) {
                _resultMsg.value = "❌ 无法识别当前调音模式（既非哈曼也非柏林），已中止，请检查模块状态"
                _isApplying.value = false
                return@withContext false
            }

            val mode = _currentMode.value
            val factory = if (mode == MODE_HARMAN) FACTORY_HARMAN else FACTORY_BERLIN
            val factoryXml = WsaShell.execSyncCmd("cat $factory 2>/dev/null")
            if (factoryXml.isBlank()) {
                _resultMsg.value = "❌ 未找到模块出厂原始文件（factory/etc/dolby/），请确认模块版本支持"
                _isApplying.value = false
                return@withContext false
            }

            val curLink = if (mode == MODE_HARMAN) LINK_HARMAN else LINK_BERLIN
            val curVendor = if (mode == MODE_HARMAN) VENDOR_HARMAN else VENDOR_BERLIN
            writeInternalAndCp(context, factoryXml, listOf(curLink, curVendor))

            val otherMode = if (mode == MODE_HARMAN) MODE_BERLIN else MODE_HARMAN
            val otherFactory = if (otherMode == MODE_HARMAN) FACTORY_HARMAN else FACTORY_BERLIN
            val otherFactoryXml = WsaShell.execSyncCmd("cat $otherFactory 2>/dev/null")
            if (otherFactoryXml.isNotBlank()) {
                val otherLink = if (otherMode == MODE_HARMAN) LINK_HARMAN else LINK_BERLIN
                val otherVendor = if (otherMode == MODE_HARMAN) VENDOR_HARMAN else VENDOR_BERLIN
                writeInternalAndCp(context, otherFactoryXml, listOf(otherLink, otherVendor))
            }

            val modeArg = if (mode == MODE_HARMAN) "harman" else "berlin"
            val switchOk = WsaShell.execSyncCmd(
                "sh ${moduleBase()}/switch_mode.sh $modeArg && echo OK || echo FAIL"
            ).contains("OK")

            loadParams(context)

            if (switchOk) {
                _resultMsg.value = "✅ 已重置为模块默认参数（${currentModeName}模式）并生效！音频服务已重启。"
            } else {
                _resultMsg.value = "✅ 已重置为模块默认参数（${currentModeName}模式）！但音频服务重启失败，请重启手机后生效。"
            }
            _isApplying.value = false
            true
        } catch (e: Exception) {
            _resultMsg.value = "❌ 重置失败: ${e.message}"
            _isApplying.value = false
            false
        }
    }

    // ═══════════════════════════════════════════
    //  模块版本检测
    // ═══════════════════════════════════════════

    private fun checkModuleVersionChanged(context: Context) {
        try {
            val modProp = WsaShell.execSyncCmd("cat ${moduleBase()}/module.prop 2>/dev/null")
            val daxModTime = WsaShell.execSyncCmd("stat -c %Y $LINK_HARMAN 2>/dev/null")
            val currentVersion = (modProp + "|" + daxModTime).trim()

            val savedVersion = WsaShell.execSyncCmd("cat $VERSION_SNAPSHOT 2>/dev/null").trim()

            if (currentVersion.isNotBlank() && currentVersion != savedVersion) {
                WsaShell.execSyncCmd("rm -f $VERSION_SNAPSHOT 2>/dev/null")
                WsaShell.execSyncCmd("echo '${currentVersion.replace("'", "'\\''")}' > $VERSION_SNAPSHOT 2>/dev/null")
            } else if (savedVersion.isBlank() && currentVersion.isNotBlank()) {
                WsaShell.execSyncCmd("echo '${currentVersion.replace("'", "'\\''")}' > $VERSION_SNAPSHOT 2>/dev/null")
            }
        } catch (_: Exception) {
        }
    }

    // ═══════════════════════════════════════════
    //  XML 替换辅助
    // ═══════════════════════════════════════════

    private fun replaceInSpeaker(xml: String, paramName: String, newValue: String): String {
        val regex = Regex("(<${Regex.escape(paramName)}\\s+value\\s*=\\s*\")([^\"]*)(\"\\s*/>)")
        return regex.replaceFirst(xml, "$1$newValue$3")
    }

    private fun replaceInAllTunings(xml: String, paramName: String, newValue: String): String {
        val regex = Regex("(<${Regex.escape(paramName)}\\s+value\\s*=\\s*\")([^\"]*)(\"\\s*/>)")
        return regex.replace(xml, "$1$newValue$3")
    }

    private fun replaceMixFreqs(xml: String, low: Int, high: Int): String {
        val regex = Regex("(<virtual-bass-mix-freqs\\s+)frequency_low\\s*=\\s*\"\\d+\"\\s+frequency_high\\s*=\\s*\"\\d+\"\\s*/>")
        val replacement = "<virtual-bass-mix-freqs frequency_low=\"$low\" frequency_high=\"$high\"/>"
        return regex.replace(xml, replacement)
    }

    // ═══════════════════════════════════════════
    //  频段调节（band_optimizer）核心 — 仅 L/R
    //  ═══════════════════════════════════════════

    /** 解析三场景 band_optimizer L/R 基值 */
    private fun parseBandOptimizers(xml: String, preserveOffsets: Boolean = false) {
        try {
            val scenes = SceneBaselines()
            var parsedCount = 0
            for ((idx, scene) in SCENE_NAMES.withIndex()) {
                val tuningStart = xml.indexOf("<tuning name=\"$scene\"")
                if (tuningStart < 0) continue
                val segEnd = xml.indexOf("</tuning>", tuningStart)
                val seg = if (segEnd > tuningStart) xml.substring(tuningStart, segEnd) else xml.substring(tuningStart)

                val bandMap = mutableMapOf<Int, IntArray>()
                val bandRegex = Regex(
                    "frequency=\"(\\d+)\"\\s+gain_left=\"([-0-9]+)\"\\s+gain_right=\"([-0-9]+)\""
                )
                for (m in bandRegex.findAll(seg)) {
                    val f = m.groupValues[1].toIntOrNull() ?: continue
                    bandMap[f] = intArrayOf(
                        m.groupValues[2].toInt(),
                        m.groupValues[3].toInt()
                    )
                    parsedCount++
                }
                when (idx) {
                    0 -> scenes.large.putAll(bandMap)
                    1 -> scenes.medium.putAll(bandMap)
                    2 -> scenes.small.putAll(bandMap)
                }
            }
            _bandBaselines.value = scenes
            // preserveOffsets=true 时保留当前用户偏移（仅刷新基准），否则重置为全 0（初始/一键重置）
            if (!preserveOffsets) _bandOffsets.value = BandOffsets()
            _hasBandsParsed.value = parsedCount >= 20 * 3
        } catch (_: Exception) {
            _hasBandsParsed.value = false
        }
    }

    fun updateBandOffset(channel: BandChannel, freq: Int, delta: Int) {
        val clamped = delta.coerceIn(-250, 250)
        val cur = _bandOffsets.value
        val next = when (channel) {
            BandChannel.LEFT -> cur.copy(left = cur.left.toMutableMap().apply { this[freq] = clamped })
            BandChannel.RIGHT -> cur.copy(right = cur.right.toMutableMap().apply { this[freq] = clamped })
        }
        _bandOffsets.value = next
    }

    /** 重置所有频段偏移量为 0，并恢复为当前模式 factory 出厂文件的频段基准 */
    suspend fun resetBandOffsets(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 以当前真实生效模式为准
            detectCurrentMode()
            if (_currentMode.value < 0) return@withContext false
            val factory = if (_currentMode.value == MODE_HARMAN) FACTORY_HARMAN else FACTORY_BERLIN
            val factoryXml = WsaShell.execSyncCmd("cat $factory 2>/dev/null")
            if (factoryXml.isBlank()) return@withContext false
            parseBandOptimizers(factoryXml)
            _bandOffsets.value = BandOffsets()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 将当前频段偏移量 Δ 应用到完整 dax xml，三场景同比写回（仅 L/R） */
    private fun applyBandOffsets(modified: String): String {
        if (!_hasBandsParsed.value) return modified
        val deltas = _bandOffsets.value
        val bases = _bandBaselines.value
        var out = modified
        for ((idx, scene) in SCENE_NAMES.withIndex()) {
            val baseMap = when (idx) {
                0 -> bases.large
                1 -> bases.medium
                else -> bases.small
            }
            if (baseMap.isEmpty()) continue
            for ((freq, arr) in baseMap) {
                val dL = deltas.left[freq] ?: 0
                out = replaceBandGain(out, scene, freq, "gain_left", arr[0] + dL)
                val dR = deltas.right[freq] ?: 0
                out = replaceBandGain(out, scene, freq, "gain_right", arr[1] + dR)
            }
        }
        return out
    }

    /** 在指定 tuning 场景段内，替换某 frequency 的某声道 gain 为 newVal */
    private fun replaceBandGain(xml: String, scene: String, frequency: Int, channelKey: String, newVal: Int): String {
        val sceneStart = xml.indexOf("<tuning name=\"$scene\"")
        if (sceneStart < 0) return xml
        val tuningEnd = xml.indexOf("</tuning>", sceneStart)
        val searchEnd = if (tuningEnd > sceneStart) tuningEnd else xml.length

        val numReg = Regex("(<band_optimizer frequency=\"${frequency}\"[^>]*?$channelKey=\")(-?\\d+)(\")")
        val match = numReg.find(xml, sceneStart)?.takeIf { it.range.last < searchEnd } ?: return xml
        val prefix = match.groupValues[1]
        val numStart = match.range.first + prefix.length
        val numEnd = numStart + match.groupValues[2].length
        return StringBuilder(xml).replace(numStart, numEnd, newVal.toString()).toString()
    }

    // ═══════════════════════════════════════════
    //  XML 提取辅助
    // ═══════════════════════════════════════════

    private fun extractBool(xml: String, paramName: String): Boolean {
        val regex = Regex("<${Regex.escape(paramName)}\\s+value\\s*=\\s*\"(true|false)\"")
        val match = regex.find(xml)
        return match?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: false
    }

    private fun extractInt(xml: String, paramName: String, default: Int): Int {
        val regex = Regex("<${Regex.escape(paramName)}\\s+value\\s*=\\s*\"(-?\\d+)\"")
        val match = regex.find(xml)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: default
    }

    private fun extractTuningInt(xml: String, paramName: String, default: Int): Int {
        val tuningStart = xml.indexOf("<tuning name=\"speaker_landscape")
        if (tuningStart < 0) return default
        val tuningXml = xml.substring(tuningStart)
        val regex = Regex("<${Regex.escape(paramName)}\\s+value\\s*=\\s*\"(-?\\d+)\"")
        val match = regex.find(tuningXml)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: default
    }

    private fun extractTuningIntRangeLow(xml: String, paramName: String, default: Int): Int {
        val tuningStart = xml.indexOf("<tuning name=\"speaker_landscape")
        if (tuningStart < 0) return default
        val tuningXml = xml.substring(tuningStart)
        val regex = Regex("<${Regex.escape(paramName)}\\s+frequency_low\\s*=\\s*\"(-?\\d+)\"")
        val match = regex.find(tuningXml)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: default
    }

    private fun extractTuningIntRangeHigh(xml: String, paramName: String, default: Int): Int {
        val tuningStart = xml.indexOf("<tuning name=\"speaker_landscape")
        if (tuningStart < 0) return default
        val tuningXml = xml.substring(tuningStart)
        val regex = Regex("<${Regex.escape(paramName)}\\s+frequency_low\\s*=\\s*\"(-?\\d+)\"\\s+frequency_high\\s*=\\s*\"(-?\\d+)\"")
        val match = regex.find(tuningXml)
        return match?.groupValues?.get(2)?.toIntOrNull() ?: default
    }

    private fun boolToXml(b: Boolean) = if (b) "true" else "false"

    // ═══════════════════════════════════════════
    //  预设管理（多预设，命名保存，JSON持久化）
    // ═══════════════════════════════════════════

    private const val MAX_PRESETS = 5
    private const val PREFS_NAME = "dolby_tuner_presets"
    private const val KEY_LIST = "preset_list"

    data class PresetEntry(val name: String, val params: DolbyParams)

    fun getPresetNames(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LIST, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
    }

    fun savePreset(context: Context, name: String): String {
        val p = _params.value
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }

        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getString("name") == name) {
                arr.put(i, buildPresetJson(name, p))
                prefs.edit().putString(KEY_LIST, arr.toString()).apply()
                return "同名预设已更新"
            }
        }
        if (arr.length() >= MAX_PRESETS) return "预设已满（最多${MAX_PRESETS}个），请先删除旧预设"
        arr.put(buildPresetJson(name, p))
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
        return "✅ 预设「$name」已保存"
    }

    fun loadPreset(context: Context, name: String): Boolean {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LIST, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("name") == name) {
                // 只恢复杜比参数；频段偏移由频段页独立的频段预设管理（两页已隔离）
                _params.value = parseParamsFromJson(obj)
                return true
            }
        }
        return false
    }

    private fun bandOffsetsToJson(band: BandOffsets): JSONObject {
        val left = JSONObject()
        band.left.forEach { (k, v) -> left.put(k.toString(), v) }
        val right = JSONObject()
        band.right.forEach { (k, v) -> right.put(k.toString(), v) }
        return JSONObject().apply {
            put("left", left)
            put("right", right)
        }
    }

    private fun bandOffsetsFromJson(obj: JSONObject): BandOffsets {
        val band = BandOffsets()
        val data = if (obj.has("bandOffsets")) obj.getJSONObject("bandOffsets") else JSONObject()
        fun readMap(parent: JSONObject, key: String): MutableMap<Int, Int> {
            val m = mutableMapOf<Int, Int>()
            try {
                if (parent.has(key)) {
                    val o = parent.getJSONObject(key)
                    val it = o.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        m[k.toIntOrNull() ?: 0] = o.optInt(k, 0)
                    }
                }
            } catch (_: Exception) {}
            return m
        }
        band.left.putAll(readMap(data, "left"))
        band.right.putAll(readMap(data, "right"))
        return band
    }

    fun deletePreset(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getString("name") != name) {
                out.put(arr.getJSONObject(i))
            }
        }
        prefs.edit().putString(KEY_LIST, out.toString()).apply()
    }

    private fun buildPresetJson(name: String, p: DolbyParams): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("dialogEnhancerEnable", p.dialogEnhancerEnable)
            put("dialogEnhancerAmount", p.dialogEnhancerAmount)
            put("dialogEnhancerDucking", p.dialogEnhancerDucking)
            put("bassEnhancerEnable", p.bassEnhancerEnable)
            put("virtualBassProcessEnable", p.virtualBassProcessEnable)
            put("surroundDecoderEnable", p.surroundDecoderEnable)
            put("surroundBoost", p.surroundBoost)
            put("volumeLevelerEnable", p.volumeLevelerEnable)
            put("virtualizerEnable", p.virtualizerEnable)
            put("virtualizerStartBand", p.virtualizerStartBand)
            put("calibrationBoost", p.calibrationBoost)
            put("volmaxBoost", p.volmaxBoost)
            put("peakValue", p.peakValue)
            put("hearingProtectionEnable", p.hearingProtectionEnable)
            put("bassEnhancerBoost", p.bassEnhancerBoost)
            put("bassEnhancerCutoffFrequency", p.bassEnhancerCutoffFrequency)
            put("virtualBassMode", p.virtualBassMode)
            put("virtualBassOverallGain", p.virtualBassOverallGain)
            put("virtualBassMixLow", p.virtualBassMixLow)
            put("virtualBassMixHigh", p.virtualBassMixHigh)
            put("bassExtractionEnable", p.bassExtractionEnable)
            put("bassExtractionCutoffFrequency", p.bassExtractionCutoffFrequency)
            // 注：频段偏移不再存入调音台预设（两页已隔离），由频段页独立的频段预设管理
        }
    }

    private fun parseParamsFromJson(obj: JSONObject): DolbyParams {
        return DolbyParams(
            dialogEnhancerEnable = obj.optBoolean("dialogEnhancerEnable", true),
            dialogEnhancerAmount = obj.optInt("dialogEnhancerAmount", 5),
            dialogEnhancerDucking = obj.optInt("dialogEnhancerDucking", 0),
            bassEnhancerEnable = obj.optBoolean("bassEnhancerEnable", false),
            virtualBassProcessEnable = obj.optBoolean("virtualBassProcessEnable", false),
            surroundDecoderEnable = obj.optBoolean("surroundDecoderEnable", true),
            surroundBoost = obj.optInt("surroundBoost", 105),
            volumeLevelerEnable = obj.optBoolean("volumeLevelerEnable", true),
            virtualizerEnable = obj.optBoolean("virtualizerEnable", false),
            virtualizerStartBand = obj.optInt("virtualizerStartBand", 0),
            calibrationBoost = obj.optInt("calibrationBoost", 0),
            volmaxBoost = obj.optInt("volmaxBoost", 50),
            peakValue = obj.optInt("peakValue", 1024),
            hearingProtectionEnable = obj.optBoolean("hearingProtectionEnable", false),
            bassEnhancerBoost = obj.optInt("bassEnhancerBoost", 200),
            bassEnhancerCutoffFrequency = obj.optInt("bassEnhancerCutoffFrequency", 150),
            virtualBassMode = obj.optInt("virtualBassMode", 3),
            virtualBassOverallGain = obj.optInt("virtualBassOverallGain", 35),
            virtualBassMixLow = obj.optInt("virtualBassMixLow", 30),
            virtualBassMixHigh = obj.optInt("virtualBassMixHigh", 150),
            bassExtractionEnable = obj.optBoolean("bassExtractionEnable", false),
            bassExtractionCutoffFrequency = obj.optInt("bassExtractionCutoffFrequency", 65)
        )
    }

    // ═══════════════════════════════════════════
    //  频段预设管理（独立于调音台预设，仅存 bandOffsets）
    //  ═══════════════════════════════════════════

    private const val BAND_PREFS_NAME = "dolby_tuner_band_presets"
    private const val BAND_KEY_LIST = "band_preset_list"
    private const val MAX_BAND_PRESETS = 5

    /**
     * 进入频段页时调用：以当前生效文件重新解析三场景基准，
     * preserveOffsets=true 保留当前偏移（避免把用户/预设偏移清零）。
     */
    suspend fun refreshBandBaselines(context: Context) = withContext(Dispatchers.IO) {
        try {
            val xml = WsaShell.execSyncCmd("cat $DAX_SYS 2>/dev/null")
            if (xml.isNotBlank()) {
                parseBandOptimizers(xml, preserveOffsets = true)
                // 同步重判当前模式，保证频段页顶部模式显示准确
                setCurrentModeByFingerprint(xml)
            }
        } catch (_: Exception) {}
    }

    fun getBandPresetNames(context: Context): List<String> {
        val raw = context.getSharedPreferences(BAND_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(BAND_KEY_LIST, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
    }

    fun saveBandPreset(context: Context, name: String): String {
        val prefs = context.getSharedPreferences(BAND_PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(BAND_KEY_LIST, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }

        val obj = JSONObject().apply {
            put("name", name)
            put("bandOffsets", bandOffsetsToJson(_bandOffsets.value))
        }
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getString("name") == name) {
                arr.put(i, obj)
                prefs.edit().putString(BAND_KEY_LIST, arr.toString()).apply()
                return "同名频段预设已更新"
            }
        }
        if (arr.length() >= MAX_BAND_PRESETS) return "预设已满（最多${MAX_BAND_PRESETS}个），请先删除旧预设"
        arr.put(obj)
        prefs.edit().putString(BAND_KEY_LIST, arr.toString()).apply()
        return "✅ 频段预设「$name」已保存"
    }

    fun loadBandPreset(context: Context, name: String): Boolean {
        val raw = context.getSharedPreferences(BAND_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(BAND_KEY_LIST, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("name") == name) {
                _bandOffsets.value = bandOffsetsFromJson(obj)
                return true
            }
        }
        return false
    }

    fun deleteBandPreset(context: Context, name: String) {
        val prefs = context.getSharedPreferences(BAND_PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(BAND_KEY_LIST, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getString("name") != name) {
                out.put(arr.getJSONObject(i))
            }
        }
        prefs.edit().putString(BAND_KEY_LIST, out.toString()).apply()
    }
}
package com.k90.tuner.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
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
 *   ④ su -c cp 覆盖到模块模板目录（Aoi，旧版 Link）两份模板 + vendor 目录两份模板
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

    // 哈曼模板（既有 Aoi，也兼容旧版 Link；优先 Aoi）
    private val LINK_HARMAN: String get() = moduleTemplate("dax-default.xml")
    private val VENDOR_HARMAN get() = "${moduleBase()}/vendor/etc/dolby/dax-default.xml"
    // 柏林模板（既有 Aoi，也兼容旧版 Link；优先 Aoi）
    private val LINK_BERLIN: String get() = moduleTemplate("dax-mode-1.xml")
    private val VENDOR_BERLIN get() = "${moduleBase()}/vendor/etc/dolby/dax-mode-1.xml"

    /** 模块内模板路径：优先 Aoi/odm，旧版模块回退 Link/odm */
    private fun moduleTemplate(file: String): String {
        val aoi = "${moduleBase()}/Aoi/odm/etc/dolby/$file"
        val link = "${moduleBase()}/Link/odm/etc/dolby/$file"
        val r = WsaShell.execSyncCmd("[ -f '$aoi' ] && echo A || ([ -f '$link' ] && echo L || echo A)")
        return if (r.trim() == "L") link else aoi
    }

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
            ModuleDetector.detect()
            if (!ModuleDetector.isInstalled) {
                _statusMsg.value = "❌ 检测到未安装 K90 音质优化 by 016. 模块，请先安装模块"
                _isLoading.value = false
                return@withContext false
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
     * 该指纹在 APP 全部操作（调频段/普通参数/重置）下都不会被修改，稳定可靠
     * （哈曼 -32/-25，柏林 -36/-58，见 fingerprintMode 内注释）。
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
     * 用 band_regulator 指纹判别模式（基于传入的生效 dax 内容实时判定）。
     *
     * 2026-10 修正：模块 v1.7 起哈曼 47Hz 的 threshold_high 已由 -54/-32/-19 调整为 -35/-25/0，
     * 旧实现只认「-32」，会导致哈曼模式判定失败（一直显示"检测中…"）。现改为：
     *   ① 先按 47Hz threshold_high 集合匹配（新旧阈值都认）——对 v1.6 及更早版本同样准确；
     *   ② 若阈值全无命中（将来又被大改），再用 threshold_low + isolated_band 结构特征兜底。
     *
     * 数值指纹：
     *   哈曼：-32（v1.7 前）/ -25（v1.7+）；柏林：-58（large）/ -36（medium）
     * 结构指纹（仅兜底）：
     *   哈曼：large/medium 为 threshold_low=-128 且 isolated_band=false
     *   柏林：threshold_low=-192 且 isolated_band=true
     *   ⚠ v1.6 哈曼的结构与柏林同构（-192/true），故结构只能兜底、不能优先。
     *
     * 三场景累计投票，票多者胜；两轮都无法判定则返回 -1。
     */
    private fun fingerprintMode(xml: String): Int {
        try {
            if (xml.isBlank()) return -1
            var highHarman = 0
            var highBerlin = 0
            var structHarman = 0
            var structBerlin = 0
            for (scene in SCENE_NAMES) {
                val start = xml.indexOf("<tuning name=\"$scene\"")
                if (start < 0) continue
                val end = xml.indexOf("</tuning>", start)
                val seg = if (end > start) xml.substring(start, end) else xml.substring(start)
                val attrs = Regex("<band_regulator frequency=\"47\"([^>]*)>").find(seg)
                    ?.groupValues?.get(1) ?: continue
                val low = Regex("threshold_low=\"(-?\\d+)\"").find(attrs)
                    ?.groupValues?.get(1)?.toIntOrNull()
                val high = Regex("threshold_high=\"(-?\\d+)\"").find(attrs)
                    ?.groupValues?.get(1)?.toIntOrNull()
                val iso = Regex("isolated_band=\"(true|false)\"").find(attrs)
                    ?.groupValues?.get(1) == "true"

                // ① 数值指纹（主判据）——覆盖 v1.7 前后全部三场景取值
                // 哈曼：large -54(旧)/-35(新)、medium -32(旧)/-25(新)、small -19(旧)
                if (high == -54 || high == -32 || high == -19 || high == -35 || high == -25) highHarman++
                // 柏林：large -58、medium -36、small -23
                if (high == -58 || high == -36 || high == -23) highBerlin++
                // ② 结构指纹（阈值被大改时的兜底）
                // 哈曼：v1.7 的 large/medium 为 -128 且非隔离，small 为 -64（v1.6 及更早整份为 -192/true，靠数值指纹识别）
                if (low == -64 || (low == -128 && !iso)) structHarman++
                if (low == -192 && iso) structBerlin++
            }
            if (highHarman != highBerlin) {
                return if (highHarman > highBerlin) MODE_HARMAN else MODE_BERLIN
            }
            if (structHarman != structBerlin) {
                return if (structHarman > structBerlin) MODE_HARMAN else MODE_BERLIN
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
     *   - 普通杜比参数：同时写入哈曼/柏林两份模板（Aoi/Link + vendor），切模式不丢。
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

            // 4. 写回当前模式模板（Aoi / Link / 模块 odm / vendor 全覆盖）
            val mode = _currentMode.value
            val curFile = if (mode == MODE_HARMAN) "dax-default.xml" else "dax-mode-1.xml"
            val writeErr = writeToTargets(context, paramsXml, listOf(curFile))
            if (writeErr != null) {
                _resultMsg.value = writeErr
                _isApplying.value = false
                return@withContext false
            }

            // 5. 普通参数同步写入另一模式模板（不含当前模式频段）
            val otherMode = if (mode == MODE_HARMAN) MODE_BERLIN else MODE_HARMAN
            val otherXml = readOtherModeTemplate(otherMode)
            if (otherXml.isNotBlank()) {
                val otherParamsXml = applyParamsToXml(otherXml, _params.value)
                val otherFile = if (otherMode == MODE_HARMAN) "dax-default.xml" else "dax-mode-1.xml"
                writeToTargets(context, otherParamsXml, listOf(otherFile))
            }

            // 6. 重挂当前模式 + 杀 DMS 与 audioserver（等库重建在同一条脚本里）
            val mountErr = ensureMounted(mode)
            if (mountErr != null) {
                _resultMsg.value = mountErr
                _isApplying.value = false
                return@withContext false
            }
            WsaShell.execSyncCmd("echo '${if (mode == MODE_HARMAN) 0 else 1}' > $MODE_FILE 2>/dev/null")
            val (restartOk, dbOk) = restartDolbyStack()

            loadParams(context)
            // 6.5 音频服务重启会清空 DSP 里的实时层 GEQ → 写回去
            val rtOk = if (restartOk) reapplyRealtimeAfterRestart(context) else false
            if (restartOk) {
                _resultMsg.value = "✅ 参数已保存并生效（${currentModeName}模式）！杜比服务与音频服务都已重载" +
                    (if (dbOk) "，参数库已重建。" else "，参数库重建较慢，听感可能延迟几秒。") +
                    (if (rtOk) "实时层 GEQ 已自动写回。" else "实时层 GEQ 暂未写回（音频服务可能仍在启动），到实时层拖一下即可恢复。")
            } else {
                _resultMsg.value = "✅ 参数已保存（${currentModeName}模式）！但服务重载失败，请重启手机后生效。"
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
        out = replaceInSpeakerEndpoints(out, "dialog-enhancer-enable", boolToXml(p.dialogEnhancerEnable), emptySet())
        out = replaceInSpeakerEndpoints(out, "dialog-enhancer-amount", p.dialogEnhancerAmount.toString(), emptySet())
        out = replaceInSpeakerEndpoints(out, "dialog-enhancer-ducking", p.dialogEnhancerDucking.toString(), emptySet())
        out = replaceInSpeakerEndpoints(out, "bass-enhancer-enable", boolToXml(p.bassEnhancerEnable), emptySet())
        out = replaceInSpeakerEndpoints(out, "virtual-bass-process-enable", boolToXml(p.virtualBassProcessEnable), emptySet())
        out = replaceInSpeakerEndpoints(out, "surround-decoder-enable", boolToXml(p.surroundDecoderEnable), emptySet())
        out = replaceInSpeakerEndpoints(out, "surround-boost", p.surroundBoost.toString(), emptySet())
        out = replaceInSpeakerEndpoints(out, "volume-leveler-amount", if (p.volumeLevelerEnable) "1" else "0", emptySet())
        out = replaceInSpeakerEndpoints(out, "virtualizer-enable", boolToXml(p.virtualizerEnable), emptySet())
        out = replaceInSpeakerEndpoints(out, "virtualizer-start-band", p.virtualizerStartBand.toString(), emptySet())
        out = replaceInSpeakerEndpoints(out, "calibration-boost", p.calibrationBoost.toString(), emptySet())
        // volmax-boost 每个 profile 各一份：必须改所有 speaker 端点，否则只改到 Default
        out = replaceInSpeakerEndpoints(out, "volmax-boost", p.volmaxBoost.toString(), emptySet())
        out = replaceInAllProfiles(out, "peak-value", p.peakValue.toString())
        out = replaceInSpeakerEndpoints(out, "hearing-protection-enable", boolToXml(p.hearingProtectionEnable), emptySet())
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

            val curFile = if (mode == MODE_HARMAN) "dax-default.xml" else "dax-mode-1.xml"
            val writeErr = writeToTargets(context, factoryXml, listOf(curFile))
            if (writeErr != null) {
                _resultMsg.value = writeErr
                _isApplying.value = false
                return@withContext false
            }

            val otherMode = if (mode == MODE_HARMAN) MODE_BERLIN else MODE_HARMAN
            val otherFactory = if (otherMode == MODE_HARMAN) FACTORY_HARMAN else FACTORY_BERLIN
            val otherFactoryXml = WsaShell.execSyncCmd("cat $otherFactory 2>/dev/null")
            if (otherFactoryXml.isNotBlank()) {
                val otherFile = if (otherMode == MODE_HARMAN) "dax-default.xml" else "dax-mode-1.xml"
                writeToTargets(context, otherFactoryXml, listOf(otherFile))
            }

            val mountErr = ensureMounted(mode)
            if (mountErr != null) {
                _resultMsg.value = mountErr
                _isApplying.value = false
                return@withContext false
            }
            WsaShell.execSyncCmd("echo '${if (mode == MODE_HARMAN) 0 else 1}' > $MODE_FILE 2>/dev/null")
            val (restartOk, dbOk) = restartDolbyStack()

            loadParams(context)
            // 7.5 音频服务重启会清空实时层 GEQ → 写回去
            val rtOk = if (restartOk) reapplyRealtimeAfterRestart(context) else false
            if (restartOk) {
                _resultMsg.value = "✅ 已重置为模块默认参数（${currentModeName}模式）并生效！杜比服务与音频服务都已重载" +
                    (if (dbOk) "，参数库已重建。" else "，参数库重建较慢，听感可能延迟几秒。") +
                    (if (rtOk) "实时层 GEQ 已自动写回。" else "实时层 GEQ 暂未写回（音频服务可能仍在启动），到实时层拖一下即可恢复。")
            } else {
                _resultMsg.value = "✅ 已重置为模块默认参数（${currentModeName}模式）！但服务重载失败，请重启手机后生效。"
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

    // ═══════════════════════════════════════════
    //  模块文件写入 / 挂载校验 / 链路重载
    // ═══════════════════════════════════════════

    /** 某份模板在所有可能出现的位置：Aoi / Link / 模块 odm / vendor */
    private fun daxTargetsFor(file: String): List<String> = listOf(
        "${moduleBase()}/Aoi/odm/etc/dolby/$file",
        "${moduleBase()}/Link/odm/etc/dolby/$file",
        "${moduleBase()}/odm/etc/dolby/$file",
        "${moduleBase()}/vendor/etc/dolby/$file"
    )

    /** 一次 su 把 xml 写回所有存在的目标；返回错误文案或 null */
    private fun writeToTargets(context: Context, xml: String, files: List<String>): String? {
        val f = File(context.filesDir, "k90_tuner_dax.xml")
        f.writeText(xml, Charsets.UTF_8)
        val path = f.absolutePath
        val sb = StringBuilder("set +e; n=0;")
        for (name in files) for (t in daxTargetsFor(name)) {
            sb.append(" [ -f '$t' ] && cp -f '$path' '$t' && chmod 644 '$t' && n=\$((n+1));")
        }
        sb.append(" echo WROTE=\$n")
        val r = WsaShell.execSyncCmd(sb.toString())
        val n = r.substringAfter("WROTE=", "").trim().substringBefore(" ").toIntOrNull() ?: 0
        return if (n > 0) null else "❌ 模块里没有可写的 dax 模板（Aoi/Link/vendor 都不存在）"
    }

    /** 把当前模式的模板挂到 /odm 与 /vendor；不一致就补挂 */
    private fun ensureMounted(mode: Int): String? {
        val file = if (mode == MODE_HARMAN) "dax-default.xml" else "dax-mode-1.xml"
        val src = moduleTemplate(file)
        val vsrc = "${moduleBase()}/vendor/etc/dolby/$file"
        val out = WsaShell.execSyncCmd("md5sum '$src' '$DAX_SYS' 2>/dev/null").trim()
        val map = out.lines().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"), 2)
            if (p.size == 2) p[1].trim().removePrefix("*") to p[0].trim() else null
        }.toMap()
        val want = map[src] ?: return "❌ 读不到模块里的 $file，请确认模块已安装"
        if (map[DAX_SYS] != want) {
            WsaShell.execSyncCmd("umount '$DAX_SYS' 2>/dev/null; mount --bind '$src' '$DAX_SYS' 2>/dev/null")
            val now = WsaShell.execSyncCmd("md5sum '$DAX_SYS' 2>/dev/null | cut -d' ' -f1").trim().removePrefix("*")
            if (now != want) return "❌ 挂载 $file 失败，请检查模块状态"
        }
        WsaShell.execSyncCmd(
            "[ -f '$vsrc' ] && umount '/vendor/etc/dolby/dax-default.xml' 2>/dev/null;" +
            "[ -f '$vsrc' ] && mount --bind '$vsrc' '/vendor/etc/dolby/dax-default.xml' 2>/dev/null"
        )
        return null
    }

    /** 杀 DMS + audioserver，并在同一条脚本里等参数库重建；返回 (服务OK, 库OK) */
    private fun restartDolbyStack(): Pair<Boolean, Boolean> {
        val script = """
            set +e
            db=/data/vendor/dolby/dax_sqlite3.db
            before=${'$'}(stat -c %Y ${'$'}db 2>/dev/null)
            for n in vendor.dolby.dms.service audioserver; do
              P=${'$'}(pidof ${'$'}n); [ -n "${'$'}P" ] && kill ${'$'}P 2>/dev/null
            done
            sleep 0.3
            for n in vendor.dolby.dms.service audioserver; do
              P=${'$'}(pidof ${'$'}n); [ -n "${'$'}P" ] && kill -9 ${'$'}P 2>/dev/null
            done
            i=0
            while [ ${'$'}i -lt 60 ]; do
              a=${'$'}(pidof audioserver); d=${'$'}(pidof vendor.dolby.dms.service)
              [ -n "${'$'}a" ] && [ -n "${'$'}d" ] && break
              i=${'$'}((i+1)); sleep 0.2
            done
            pidof vendor.dolby.dms.service >/dev/null 2>&1 || start vendor.dolby.dms.service 2>/dev/null
            pidof audioserver >/dev/null 2>&1 || start audioserver 2>/dev/null
            ok=0
            pidof audioserver >/dev/null 2>&1 && pidof vendor.dolby.dms.service >/dev/null 2>&1 && ok=1
            dbok=0
            i=0
            while [ ${'$'}i -lt 48 ]; do
              now=${'$'}(stat -c %Y ${'$'}db 2>/dev/null)
              if [ -n "${'$'}now" ] && [ "${'$'}now" != "${'$'}before" ]; then dbok=1; break; fi
              i=${'$'}((i+1)); sleep 0.25
            done
            echo "STACK=${'$'}ok DB=${'$'}dbok"
        """.trimIndent()
        val r = WsaShell.execSyncCmd(script)
        return r.contains("STACK=1") to r.contains("DB=1")
    }

    private fun replaceInSpeaker(xml: String, paramName: String, newValue: String): String {
        val regex = Regex("(<${Regex.escape(paramName)}\\s+value\\s*=\\s*\")([^\"]*)(\"\\s*/>)")
        return regex.replaceFirst(xml, "$1$newValue$3")
    }

    /**
     * 在所有 profile 的 speaker endpoint 内替换参数（每块仅一处）。
     * 用于 volmax-boost 这类「每个 profile 各存一份」的参数：只改 replaceFirst 的话
     * 只会命中 Default，听歌/看视频切到别的 profile 就完全听不出变化。
     */
        private fun replaceInSpeakerEndpoints(xml: String, paramName: String, newValue: String,
                                          skipProfiles: Set<String> = setOf("Bass", "Loudness")): String {
        val innerRegex = Regex("(<${Regex.escape(paramName)}\\s+value\\s*=\\s*\")([^\"]*)(\"\\s*/>)")
        val endpointRegex = Regex("<endpoint_type id=\"speaker\">.*?</endpoint_type>", RegexOption.DOT_MATCHES_ALL)
        val profileRegex = Regex("<profile\\s+[^>]*name=\"([^\"]*)\"[^>]*>.*?</profile>", RegexOption.DOT_MATCHES_ALL)
        return profileRegex.replace(xml) { m ->
            val name = m.groupValues[1]
            if (name in skipProfiles) {
                m.value
            } else {
                val block = m.value
                val sb = StringBuilder()
                var cursor = 0
                endpointRegex.findAll(block).forEach { em ->
                    sb.append(block, cursor, em.range.first)
                    sb.append(em.value.replaceFirst(innerRegex, "$1$newValue$3"))
                    cursor = em.range.last + 1
                }
                sb.append(block, cursor, block.length)
                sb.toString()
            }
        }
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


    // ═══════════════════════════════════════════
    //  预设导出 / 导入（调音台 + 频段，导入自动归类）
    // ═══════════════════════════════════════════
    /** 当前模式标签（导出文件名与导入预设名后缀用） */
    private fun modeTag(): String = when (_currentMode.value) {
        MODE_HARMAN -> "哈曼"
        MODE_BERLIN -> "柏林"
        else -> "未知"
    }

    /** 取调音台预设原始 JSON */
    fun getPresetJson(context: Context, name: String): JSONObject? =
        findPresetJson(context, PREFS_NAME, KEY_LIST, name)

    /** 取频段预设原始 JSON */
    fun getBandPresetJson(context: Context, name: String): JSONObject? =
        findPresetJson(context, BAND_PREFS_NAME, BAND_KEY_LIST, name)

    private fun findPresetJson(context: Context, prefsName: String, key: String, name: String): JSONObject? {
        val raw = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(key, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name") == name) return o
        }
        return null
    }

    /** 导出文本：kind = "band" / "mixer" */
    fun buildExportText(context: Context, kind: String, name: String): String? {
        val obj = if (kind == "band") getBandPresetJson(context, name)
        else getPresetJson(context, name) ?: return null
        return JSONObject().apply {
            put("format", "k90eq-std")
            put("version", 1)
            put("kind", kind)
            put("mode", if (_currentMode.value == MODE_BERLIN) "berlin" else "harman")
            put("modeLabel", modeTag())
            put("exportedAt", System.currentTimeMillis())
            put("preset", obj)
        }.toString(2)
    }

    /** 导出到 Download：频段_xxx_哈曼.k90eq.json / 调音台_xxx_哈曼.k90eq.json */
    fun exportPresetToDownload(context: Context, kind: String, name: String): String {
        val text = buildExportText(context, kind, name) ?: return "❌ 预设「$name」不存在"
        val prefix = if (kind == "band") "频段" else "调音台"
        val safe = (prefix + "_" + name + "_" + modeTag())
            .replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")
        val dest = "/storage/emulated/0/Download/${safe}.k90eq.json"
        return try {
            val tmp = java.io.File(context.cacheDir, "export_std.json")
            tmp.writeText(text)
            WsaShell.execSyncCmd("cp -f '${tmp.absolutePath}' '$dest' && chmod 664 '$dest'")
            if (java.io.File(dest).length() > 0) "✅ 已导出：Download/${safe}.k90eq.json"
            else "❌ 导出失败：写不进 Download（确认已授予 root）"
        } catch (t: Throwable) {
            "❌ 导出失败：${t.message}"
        }
    }

    /**
     * 导入预设：自动判定「调音台 / 频段」，写入对应库（不自动应用）。
     * 名字统一加模式后缀「· 哈曼 / · 柏林」，已带则不重复。
     */
    fun importPresetText(context: Context, text: String): String {
        return try {
            val root = JSONObject(text.trim())
            val preset = when {
                root.has("preset") -> root.getJSONObject("preset")
                root.has("name") -> root
                else -> return "❌ 不是本 APP 的预设文件"
            }
            val kind = when {
                root.optString("kind") == "band" || root.optString("kind") == "mixer" -> root.optString("kind")
                preset.has("bandOffsets") || preset.has("geq") -> "band"
                preset.has("dialogEnhancerEnable") || preset.has("surroundBoost") || preset.has("volmaxBoost") -> "mixer"
                else -> return "❌ 认不出预设类型（既不像频段也不像调音台）"
            }
            val label = when {
                root.optString("modeLabel").isNotBlank() -> root.optString("modeLabel")
                root.optString("mode") == "berlin" -> "柏林"
                root.optString("mode") == "harman" -> "哈曼"
                else -> modeTag()
            }
            var baseName = preset.optString("name").ifBlank { "导入预设" }
            val suffix = " · $label"
            if (!baseName.endsWith(suffix)) baseName += suffix
            val prefsName = if (kind == "band") BAND_PREFS_NAME else PREFS_NAME
            val key = if (kind == "band") BAND_KEY_LIST else KEY_LIST
            val limit = if (kind == "band") MAX_BAND_PRESETS else MAX_PRESETS
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val arr = try { JSONArray(prefs.getString(key, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
            if (arr.length() >= limit) return "预设已满（该类最多${limit}个），请先删除旧预设"
            val existing = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
            var finalName = baseName
            if (finalName in existing) {
                var i = 2
                while ("$baseName ($i)" in existing) i++
                finalName = "$baseName ($i)"
            }
            preset.put("name", finalName)
            arr.put(preset)
            prefs.edit().putString(key, arr.toString()).apply()
            val where = if (kind == "band") "频段预设" else "调音台预设"
            "✅ 已导入到「$where」：$finalName（去对应页面加载才生效）"
        } catch (t: Throwable) {
            "❌ 导入失败：${t.message}"
        }
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

    /**
     * 将「用户 UI 当前看到的 Medium 场景最终绝对增益」序列化为 JSON。
     * 预设只存 Medium（因为三场景是同一偏移 Δ 等比写回，存 Medium 即可表达用户意图，
     * 无论保存前是否已应用，加载时都能用当前实时 Medium 基线倒推出偏移）。
     * 结构：{medium:{left:{idx:val}, right:{idx:val}}}（下标对齐 ALL_BANDS）
     */
    private fun bandSnapshotToJson(scenes: SceneBaselines, band: BandOffsets): JSONObject {
        val medium = scenes.medium
        val left = JSONObject()
        val right = JSONObject()
        ALL_BANDS.forEachIndexed { i, f ->
            val arr = medium[f] ?: return@forEachIndexed
            left.put(i.toString(), arr[0] + (band.left[f] ?: 0))
            right.put(i.toString(), arr[1] + (band.right[f] ?: 0))
        }
        return JSONObject().apply {
            put("left", left)
            put("right", right)
        }
    }
    /**
     * 从 JSON 恢复频段状态（只恢复 Medium 偏移，不改任何基线）。
     * 新格式（保存的 Medium 绝对显示值）：用「当前实时生效的 Medium 基线」倒推出偏移量 Δ，
     * 使 UI 显示==保存时值，且偏移是真实相对当前基线的偏移 → 点"应用"三场景等比同步，无 bug。
     * 旧格式（纯偏移量 {left/right:{freq:Δ}}）回退：直接作为偏移。
     */
    private fun bandSnapshotFromJson(obj: JSONObject): BandOffsets {
        val band = BandOffsets()
        val data = if (obj.has("bandOffsets")) obj.getJSONObject("bandOffsets") else JSONObject()
        if (data.has("left") || data.has("right")) {
            val leftSaved = data.optJSONObject("left")
            val rightSaved = data.optJSONObject("right")
            val curMedium = _bandBaselines.value.medium
            ALL_BANDS.forEachIndexed { i, f ->
                val savedL = leftSaved?.optInt(i.toString())
                if (savedL != null) {
                    val baseL = curMedium[f]?.get(0) ?: 0
                    band.left[f] = savedL - baseL
                }
                val savedR = rightSaved?.optInt(i.toString())
                if (savedR != null) {
                    val baseR = curMedium[f]?.get(1) ?: 0
                    band.right[f] = savedR - baseR
                }
            }
        } else {
            // 旧格式：纯偏移量
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
        }
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

/**
     * 音频服务重启之后，把实时层保存过的 GEQ 写回 DSP。
     * 先走常驻会话（writeLive 会自愈重开），失败才退回一次性写入。
     */
    private suspend fun reapplyRealtimeAfterRestart(context: Context): Boolean {
        val curve = runCatching { DapCurveStore.loadDraft(context) }.getOrNull()
            ?: runCatching { DapCurveStore.load(context) }.getOrNull()
            ?: return false
        repeat(8) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(700)
            val e1 = runCatching { DapRealtime.writeLive(curve) }
                .getOrElse { it.message ?: "写入异常" }
            if (e1 == null) {
                runCatching { DapRealtime.save() }
                DapRealtime.notifyChanged()
                return true
            }
            val e2 = runCatching { DapRealtime.writeCurve(curve, null) }
                .getOrElse { it.message ?: "写入异常" }
            if (e2 == null) {
                runCatching { DapRealtime.save() }
                DapRealtime.notifyChanged()
                return true
            }
        }
        return false
    }

    /** 频段预设里的 GEQ：后台线程写进 DSP，成功后落库 + 清草稿 + 通知实时层刷新 */
    private fun applyPresetGeqAsync(context: Context, geq: IntArray) {
        Thread {
            val err = runCatching { DapRealtime.writeLive(geq) }
                .getOrElse { it.message ?: "写入异常" }
            val ok = if (err == null) true
                     else runCatching { DapRealtime.writeCurve(geq, null) }.getOrNull() == null
            if (ok) {
                runCatching { DapRealtime.save() }
                DapCurveStore.save(context, geq, bootApply = true)
                DapCurveStore.clearDraft(context)
                DapRealtime.notifyChanged()
            }
        }.start()
    }

    //  ═══════════════════════════════════════════
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
            // 方案A：保存三场景绝对频响快照（保存时无需应用也能完整还原所看到的频响）
            put("bandOffsets", bandSnapshotToJson(_bandBaselines.value, _bandOffsets.value))
            // 实时层 GEQ：跟频段预设一起存（优先未保存草稿，再退已保存曲线）
            val geq = runCatching { DapCurveStore.loadDraft(context) }.getOrNull()
                ?: runCatching { DapCurveStore.load(context) }.getOrNull()
            val geqArr = JSONArray()
            geq?.forEach { geqArr.put(it) }
            put("geq", geqArr)
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
                // 只恢复 Medium 偏移（不改基线）：
                // 无论保存前应用与否，都用当前实时 Medium 基线倒推出偏移 → UI 还原保存时值，
                // 点"应用"时三场景按同一偏移等比写回，不会出现三场景 bug。
                _bandOffsets.value = bandSnapshotFromJson(obj)
                // 预设里带实时层 GEQ → 写回 DSP（后台线程，优先会话）
                if (obj.has("geq")) {
                    val ga = obj.getJSONArray("geq")
                    if (ga.length() == DapRealtime.BANDS) {
                        applyPresetGeqAsync(context, IntArray(DapRealtime.BANDS) { ga.getInt(it) })
                    }
                }
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
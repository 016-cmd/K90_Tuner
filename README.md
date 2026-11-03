# K90 Tuner — REDMI K90 标准版 音质优化伴生 APP

面向 **REDMI K90 标准版**（机型代号 `annibale`）的音质优化伴生 Tuner 应用，与 K90 标准版音质优化模块（`k90_audio_plus`）
协作，提供**杜比调音台、20 段频段调节、模块状态检测**等一套完整、可控的音频调校工具。

> 本工程为**完全原创**开发，**整体采用 GNU Affero General Public License v3.0（AGPL-3.0）** 授权。

---

## ✨ 主要功能

### 🎚️ 杜比调音台（Dolby Tuner）
- 适配 K90 标准版模块（`k90_audio_plus`），支持 **哈曼（Harmon）/ 柏林（Berlin）双调音模式**
- 解析并写回系统 Dolby 参数与频段基线（`DolbyParams`、DAX 参数、`band_optimizer` 偏移）
- 普通参数同时写两份模式模板，**切换模式不丢失**；频段按当前激活模式写对应模板
- 通过 Root 覆盖模块 `Link` / `vendor` 模板，并调用 `switch_mode.sh` 重挂载 + 重启 audioserver 生效

### 🎛️ 频段调节（Band Tuner）
- **20 段频段**（`band_optimizer`）左右声道独立调节，标准版仅 L/R 两声道
- 顶部曲线 Tab 在左/右声道间切换，下方各声道 20 频段折叠卡片
- **偏移相对实时基线**：加载时用当前激活模式的基线倒推偏移还原 UI，三场景同比写回无偏移冲突
- 曲线可视化（`BandCurveCanvas`）

### 🖥️ 系统界面与设置
- **液态玻璃（Liquid Glass）3-Tab 主框架**：调音台 / 频段 / 设置（Material 3 + backdrop 玻璃效果）
- 主题 / 壁纸外观设置

### 🔧 底层与稳定性
- **Root/驱动适配**：自实现 Root 命令执行（`WsaShell`，`Runtime.exec("su -c ...")`，兼容 Magisk / KernelSU / APatch 模块路径）
- **模块检测**（`ModuleDetector`）：Root 检测模块安装状态 + 机型代号校验，非 `annibale` / REDMI K90 标准版不可用
- 杜比参数 / 频段实时状态管理与落盘

---

## 许可证

本工程（含代码、资源、文档）为**原创作品**，基于 **GNU Affero General Public License v3.0（AGPL-3.0）** 发布，
完整条款见 [`LICENSE`](./LICENSE)。

**Copyright (c) 2026 016.**

**为什么选择 AGPL-3.0？** 全部代码由本项目自主开发，不依赖任何 GPL/AGPL 传染性上游代码。
选用 **AGPL-3.0**（copyleft 约束最强的开源许可证），既保证代码开源免费，也要求任何**修改、分发、
或通过网络（服务器/在线服务）提供本工程（或基于它的改编）** 的人都**必须同样以 AGPL-3.0 开源并公布对应源码**，
以此保护本项目的原创成果不被闭源私有化或擅自商用。

---

## 版权声明

本工程 **全部功能代码均为原创**，版权归作者 **016.** 所有，包括但不限于：
- **杜比调音引擎**：`com.k90.tuner.service.DolbyTunerManager`（双模式参数解析 / 写回 / 切换重挂载）
- **频段调节**：`com.k90.tuner.ui.screens.BandTunerScreen` / `BandTunerSection` / `ui.components.BandCurveCanvas`
- **主框架与界面**：`com.k90.tuner.ui.screens.MainApp`（液态玻璃 3-Tab）、`SettingsScreen`、主题 / 壁纸
- **Root/设备适配**：`com.k90.tuner.service.WsaShell`（root 命令执行）、`ModuleDetector`（模块与机型检测）
- **应用与数据层**：`com.k90.tuner.app.MainActivity`、`ui.AppContextHolder`、`ui.MainViewModel`

> 本工程不包含 ViPER4Android 等第三方音效引擎的衍生代码；所依赖的均为标准 Android / Jetpack 开源库（见下）。

---

## 三方依赖致谢

| 组件 | 所属 | 许可证 |
|---|---|---|
| AndroidX（Compose / Material3 / Core / Lifecycle / Activity / Navigation） | JetBrains / Google | ✅ Apache-2.0 |
| Kotlin & KotlinX Coroutines | JetBrains | ✅ Apache-2.0 |
| Coil（coil-compose） | Coil | ✅ Apache-2.0 |
| DataStore / 序列化 | JetBrains / Android | ✅ Apache-2.0 |
| Backdrop（Kyant0 / AndroidLiquidGlass，液态玻璃视觉效果） | 液态玻璃背景 | ✅ Apache-2.0 |

> **关于 Backdrop**：该组件（`io.github.kyant0:backdrop`）对应的开发仓库为
> [`Kyant0/AndroidLiquidGlass`](https://github.com/Kyant0/AndroidLiquidGlass)，依据其 POM 声明采用 **Apache-2.0** 授权。

---

## 说明与免责

- 本工程为个人/社区音质优化用途，与系统音频架构深度耦合，Root 操作会修改系统音频配置，请仅在具备必要知识的前提下使用。
- 你使用、修改、分发本工程时，须遵循 **AGPL-3.0** 的条款，并保留本文件中的版权声明。
- 若您对版权归属或本声明有任何疑问，欢迎通过仓库 Issues 联系我们。
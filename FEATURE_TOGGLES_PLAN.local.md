# 新增特性开关管理方案（与 Opus 三轮共识；root 音量已确认跳过）

基线：主线 librepods-org/librepods b5a3eaee。新增文件 41、大改共享文件 41。
归属已核实：vendor_id_hook/vendor_att_socket/空间音频设置/自动连接等在主线已存在，不算我们新增。

## 开关矩阵（8 项，6 项已实现 + 2 项不做）

| # | Key | 默认 | 状态 | UI 位置 | 关闭行为 | 风险 |
|---|-----|------|------|---------|----------|------|
| 1 | heart_rate_monitoring_enabled | false | ✅ 已实现 | 心率监测 | 完全禁用 | 无 |
| 2 | heart_rate_auto_retry_enabled | true | ✅ 已实现 | 心率 > 自动重试 | 只保留手动重试 | 低 |
| 3 | heart_rate_respect_remote_audio_guard | true | ✅ 已实现 | 心率 > 高级 | 远端占用也自动重试 | 可能干扰他人音频 |
| 4 | health_connect_export_enabled | false | ✅ 已实现 | 心率 > Health Connect | 不导出 | 无 |
| 5 | health_connect_detailed_samples | false | ✅ 已实现 | Health Connect > 详细样本 | 只写分钟平均 | 无 |
| 6 | remote_device_audio_guard_enabled | true | ✅ 已实现 | 连接保护 | 关闭全部防抢占机制 | 高（需二次确认） |
| 7 | root_volume_control_enabled | - | ❌ 不做 | - | 用户确认跳过；RootAvrcpVolumeController 维持无条件使用 | - |
| 8 | hyperos_fixes_enabled | - | ❌ 不做 | - | - | Xposed/native hook 跨进程读 pref 不可靠且需重启，违背"安装即生效"定位；改为状态卡片 |

## 依赖关系

- 2/3/4/5 依赖 1（主开关关闭时子项置灰/隐藏）。
- 5 依赖 4。
- 3 依赖 6 的语义（但可独立关闭，作为高级逃生舱）。
- 6 关闭时核心机制（扬声器屏障、A2DP START lease、repair fence、重连预算）全部随之关闭，不拆分。

## 内部不可关清单

扬声器屏障、原生 A2DP START lease、repair fence、lease 换代、重连预算、PendingSocketWatchdog、心率隐私保护（原始帧不落日志、Health Connect 未授权不写）、Smart Routing 被动解析（周期上报永久关闭）。

## 明确不做的项

- heart_rate_continuous_mode_enabled：实现即连续监测，无单次模式。
- guard_health_metrics_enabled / local_audio_detection_sensitivity：需新实现，不是现有特性开关。
- root_spatial_preferred_over_native：无原生降级双路径。
- root_volume_control_enabled：用户确认跳过，无原生降级双路径，维持现状。
- auto_update_check_enabled：代码无自动检查逻辑。
- xiaomifix 运行时开关：Xposed/native hook 进程启动加载，跨进程 pref 不可靠。
- 第二轮误列项（auto_connect/通知转发/无障碍/电池优化等）：主线已有或不存在该特性。

## UI 组织

```
高级设置
├─ 心率监测分组
│  ├─ 心率监测（主）
│  ├─ 自动重试（依赖主）
│  ├─ 尊重远端音频保护（依赖主）
│  ├─ Health Connect 导出（依赖主）
│  └─ 详细样本（依赖导出）
├─ 系统增强分组
│  └─ 远端音频保护（独立，⚠️ 二次确认）
└─ HyperOS 状态卡片（可选）：显示修复模块已激活/未检测到
```

## 实施批次

- 批次 1/2：已取消（root 音量不做，开关矩阵全部为已完成项）。
- 批次 3（可选 ~0.5h）：HyperOS 修复模块状态卡片。

## 共识记录

- 第 1 轮：完整矩阵 + UI 分区 + 阶段划分（含部分不适用项）。
- 第 2 轮：剔除连续模式/健康度/灵敏度/root 优先/自动更新；纠正归属（vendor/spatial 主线已有）。
- 第 3 轮：确认 1-6，否决 hyperos_fixes_enabled；随后用户确认跳过 root 音量，收敛为 6 项已完成矩阵 + 明确不做清单。

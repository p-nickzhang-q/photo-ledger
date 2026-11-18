# 票 45：云端识别 token 用量记录与展示

- Owner: agent（QoderCN）
- Created: 2026-11-18
- Status: Resolved

## Resolved

- 结论（2026-11-18）：云端接口返回的 usage 已解析并随账目入库（Entry v5 三列，旧数据留空）；导入队列与账目详情显示「输入 / 输出 / 总计 tokens」。
- 证据：`:app:testDebugUnitTest` 全绿。新增覆盖：客户端 usage 解析（含缺失为 null）、队列项 usage 透传、确认入账写入每条账目（一图多单同记一份）、备份 round-trip 与 CSV 三列、repo.confirm 用量写入。
- 真机（OpenCode Go / deepseek-v4.1-flash）：v4→v5 迁移在真实 36 笔账本上通过；云端识别后队列显示「云端用量：输入 1,259 / 输出 335 / 总计 1,594 tokens」，入账后详情页显示同一行；测试账目已删除，账本恢复 36 笔。
- 未验证：端侧识别/旧账目在真机上不显示用量行（逻辑为 null 不渲染，单测/代码路径明确）。

## 背景

云端识别按 token 计费；用户需要知道每笔识别实际用了多少，以便感知成本。旧账目没有该数据，不做回填。

## 方案

- `data/TokenUsage`：prompt/completion/total，`display()` 输出「输入 1,234 / 输出 150 / 总计 1,384 tokens」。
- `CloudVisionClient.parseUsage`：读 `usage.prompt_tokens / completion_tokens / total_tokens`，兼容 `input/output_tokens`；total 缺失时相加补。
- `ExtractionOutcome.Success.usage` → `ImportItem.usage` → 队列卡片展示；确认时传给 `repo.confirm` 写入 Entry。
- Room v5：`prompt_tokens / completion_tokens / total_tokens` 可空列；旧行 NULL，不显示。
- 备份 JSON 保真；CSV 追加「输入Token / 输出Token / 总Token」三列。
- 一图多单：每单记录同一份用量（API 按图片计费，不做拆分）。

## 验收

- 云端识别后队列卡片显示用量；确认入账后详情页显示同一用量。
- 端侧识别/旧账目：无用量行，不占位。
- 备份恢复后用量字段无损；CSV 三列可读。

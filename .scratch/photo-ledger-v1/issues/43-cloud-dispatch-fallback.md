# 票 43：提取器分派与云端失败回退

- Owner: agent（QoderCN）
- Created: 2026-11-18
- Status: Resolved

## Resolved

- 结论（2026-11-18）：`makeImportExtractor` 逐张读取引擎设置；云端失败且端侧模型齐备时自动回退，队列项显示回退原因。
- 证据：ImportQueueTest 新增「云端失败回退提示透传到队列项」断言并通过；全量 `:app:testDebugUnitTest` 绿。
- 真机：云端成功路径验证（合成订单图 → 队列显示 1 单 + 商家/金额/日期，提取 7.7s，无回退提示）。
- 未验证：真机断网/错误 Key 的回退耗时与提示展示（回退逻辑由单测覆盖，未在真机上制造失败）。

## 方案

- `extractOnDevice(bytes)`：原 OCR + 0.6B 管线抽出复用（含商户记忆与规则快路径）。
- `extractWithCloud(bytes, config)`：配置不完整直接返回可读失败；否则压缩 → base64 → 云端；异常按需回退。
- 回退提示：`ExtractionOutcome.Success(note)` → `ImportState.Done(note)` → 队列卡片小字（锈红）。
- 两引擎都不可用：失败原因合并上屏（云端原因 + 端侧回退原因）。

## 验收

- 云端成功：Draft 含商家/内容；队列正常逐单入账。
- 云端失败 + 端侧可用：出端侧结果，并显示「云端失败（原因），已用端侧识别」。
- 云端失败 + 端侧不可用：该项 Failed，原因可读。

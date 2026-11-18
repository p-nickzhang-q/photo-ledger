# 票 46：云端识别提速（快速模式）

- Owner: agent（QoderCN）
- Created: 2026-11-18
- Status: Resolved

## Resolved

- 结论（2026-11-18）：新增「快速模式」默认开启。DeepSeek V4 推理模型请求附 `reasoning_effort: "none"` + `max_tokens: 2048`，直接输出 JSON、不再先思考；复杂截图识别不全可在设置页关闭。
- 实测（同一张合成订单图，长边 1568 / JPEG 85，OpenCode Go）：
  - 默认思考：7.2–10.5s，completion 356–544 tokens，reasoning 570–853 字符
  - 快速模式：2.7–5.1s，completion 51 tokens，reasoning 0
  - 两次结果字段一致（商家/内容/金额/日期/类别）
- 顺带按网关文档补齐请求身份：`User-Agent: photo-ledger/1.0`；`x-opencode-session` 改为 CloudPrefs 持久化的安装级稳定 ID（原为进程内随机）。
- 证据：`:app:testDebugUnitTest` 全绿；新增断言覆盖 deepseek 快速模式参数、关闭开关不发参数、UA 头。
- 真机端到端（快速模式包，14:41 安装）：合成订单图提取 **5.7s**，日志 `fast=true`，usage 输入 1,233 / 输出 **55** / 总计 1,288（旧版同图输出 335）。
- 用户真实截图旧版日志：**55s，输出 3,859 tokens**（思考占绝大部分），与快速模式预期的 ~50-200 输出量级差距明显。
- 排查记录：13:39 构建的快速模式包首次安装未落地——手机息屏期间 `adb install` 静默未执行，设备仍为 11:49 旧版（`lastUpdateTime` 可查）；14:41 重装后 `lastUpdateTime` 更新并验证 `fast=true`。此后装机一律核对 `lastUpdateTime`，不只信 install 返回。

## 背景

用户导入真实截图云端识别耗时 58s。根因是 DeepSeek V4.1 Flash 默认开启思考（high），复杂页面会生成大量 reasoning token；`reasoning_effort` 默认 high。

## 方案

- `CloudConfig.fastMode`（默认 true）+ 设置页开关。
- 仅当 `fastMode && model 含 deepseek` 时发送 `reasoning_effort: "none"` 与 `max_tokens: 2048`，避免其它 OpenAI 兼容服务拒绝未知参数。
- 关闭快速模式时不发这两个参数，保持原有行为。
- 日志 `CLOUD_EXTRACT_OK ms=… fast=…` 便于用户回报耗时。

## 验收

- 设置页开关可见且持久化。
- 真机云端识别耗时显著下降（合成图目标 <6s）；识别字段不回归。
- 关闭开关后请求体不包含 reasoning_effort / max_tokens。

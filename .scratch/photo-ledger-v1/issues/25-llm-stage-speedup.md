# 票 25：LLM 段提速——会话池预建 + Benchmark 数据打开

- Owner: agent（QoderCN）
- Created: 2026-09-22
- Status: In Progress

## 背景 / 动机

票 23 收尾时已把单张提取分解清楚：OCR 1.4-2.1s（正常）+ LLM 段 ~8.9s。
LLM 段再拆：`createConversation` ~2s（每张图固定开销）+ `sendMessage` ~6.9s
（输出仅 77 字符，非思考链失控）。本轮两件事：

1. **打开 Benchmark 数据**：`ExperimentalFlags.enableBenchmark = true`（0.17.0 jar
   已核实该开关存在）。打开后 LITERT_BENCH 日志才有真实数据（TTFT / prefill
   token 数与吞吐 / decode token 数与吞吐），6.9s 里 prefill 与 decode 各占多少
   一目了然，定下一步优化方向（prefill 大头 → prompt 瘦身/缓存；decode 大头 →
   只能等 INT4 上游）。
2. **会话池预建（流水线）**：Conversation 是有状态多轮会话、无 reset 接口，
   跨图直接复用会污染上下文（不可行）。改为「用后预建」：decode 结束、
   当前会话 close 之后，后台立刻建下一个会话；下张图 OCR（1.4-2.1s）与
   解析/入账期间正好覆盖这 ~2s 创建时间。队列稳态下每张省 ~2s。
   单张导入仍付一次 2s（无后续图可藏）——再叠一层：ensureLoaded 后也预建，
   把第一张的 2s 藏进 App 启动预热期。

## 约束

- 不动 prompt / 契约 / 采样参数 → 不触发闸门重跑（纯传输层改动）
- 任意时刻至多一个会话在 decode（预建完成后才被 acquire，无并发推理）
- 诊断绝不影响业务（票 23 教训）：池取会话失败一律回退同步创建

## 方案

`LitertLlmTransport` 内部：

- `ensureLoaded`：Engine 创建前 `ExperimentalFlags.enableBenchmark = true`；
  加载完成后 fire-and-forget 预建一个会话
- `acquireConversation()`：取池中预建会话（无则同步创建）；
  `completeText` 的 `finally` 中 close 当前会话后触发预建
- `close()`：取消预建 scope（进程级单例 transport，泄漏一个会话随进程回收）

## 验收

- Windows 侧 assembleRelease 编译通过
- 真机队列导入：LITERT_CONV_PREWARM 命中（第二次提取起 CON_CREATE 消失）、
  LITERT_BENCH 有数据（TTFT/prefill/decode 吞吐）
- 队列稳态单张耗时 ~10s → ~8s（conversation 开销被覆盖）
- 下一步依据 LITERT_BENCH 数据定 prefill/decode 优化方向（另立后续）

## Resolved

- 结论（2026-09-22）：两件事均落地。① `ExperimentalFlags.enableBenchmark = true`
  （ensureLoaded 中设置），LITERT_BENCH 拿到 runtime 自报数据；
  ② 会话池「用后预建」：completeText finally 中 close 后立即后台预建下一个会话，
  ensureLoaded 完成后也预建（第一张的 2s 藏进启动预热期）。
  预建失败静默回退同步创建（诊断绝不影响业务，票 23 教训）。
- 真机验证（vivo V2183A，队列 4 张）：关键路径零 CON_CREATE（全部 source=prewarm），
  预建 1.75-2.5s 被下一张 OCR（0.7-1.2s）+ 解析窗口覆盖；E2E_LLM ~8.9s → 5.9-7.7s，
  队列稳态单张省 ~2s。
- Benchmark 分解（sendMessage 6.3-6.9s = TTFT + decode，对账吻合）：
  - prefill（TTFT）：1.5-2.8s，prompt 236-309 token，100-173 tok/s
  - decode：3.4-4.6s，输出 28-38 token，**7.7-8.7 tok/s**
  - 结论：decode 是大头。GPU 裸跑基准 21 tok/s，约束解码（LLGuidance 逐步掩码）
    下只剩 ~8 tok/s（~2.5x 开销）。后续方向：砍输出 token 数（schema key 缩短等，
    契约改动需过闸门）/ INT4（上游仍阻塞）。
- 疑点存档：BenchmarkInfo.initTimeInSecond=5.7-6.4s 语义未明（不落在 sendMessage
  计时窗内，与 CON_CREATE 1.75-2.5s 也不吻合），暂不解读。

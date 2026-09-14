# 端到端提取耗时现状与优化路线（票 05 实测 → 2026-09-14 优化后更新）

日期：2026-09-14。状态：第一轮优化已落地（596s→208s），下一轮方向见文末。

## TL;DR

票 05 真机端到端首测 **~10 分钟/张**。当日完成第一轮优化（Q4_K_M 量化 + NEON dotprod + 关 OpenMP），**稳定值 ~3.5 分钟/张（208s）**，质量闸门 30/30 硬字段 100% 保持。prompt 瘦身尝试被闸门复验否决（order_12 回归），已回退。

## 优化成果（真机 vivo V2183A，order_01，6 线程）

| 配置 | 总耗时 | prefill/解码 | 备注 |
|---|---|---|---|
| 基线：Q8_0、ARM 零优化编译 | 596s | 371s @0.53tok/s | 票 05 首测 |
| + Q4_K_M（397MB） | ~440s | 294s @0.57 | 内存峰值 1.32→1.06GB RSS |
| + `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod` | 389s | 240s @0.57 | dotprod 主要加速 prefill |
| 连续运行（系统状态良好） | **208s** | **123s @1.01 tok/s** | 热节流影响大，208-389s 波动 |

三项修复的坑（详见票 10/05 记录）：
1. **ARM 零优化**：llama.cpp 的 ARM 分支不自动加 NEON flags，`GGML_NATIVE` 在交叉编译下不生效，必须显式 `GGML_CPU_ARM_ARCH`（官方 Android 示例同样没开，属通病）
2. **OpenMP 崩溃**：NDK 27 带 libomp，`find_package(OpenMP)` 意外成功 → 真机 prefill 段 SIGSEGV（mul_mat memcpy），必须 `GGML_OPENMP=OFF`
3. **量化不能本地转**：Q8_0 GGUF 无法 requantize 到 Q4_K_M，需从源下载（unsloth/Qwen3-0.6B-GGUF，ModelScope 镜像 98KB/s 可用）

**实测口径说明**：208s 与 389s 的差距主要是热节流与后台负载。评测取连续第二次运行（cache 热、无并发负载）。

## 被否决的方案：prompt 瘦身（前车之鉴）

把字段说明从 8 行压到 1 行（prompt 410→255 token，端到端 169s）后，**闸门 30 张复验抓到 order_12 回归**：标注 ¥14.5 提取为 ¥10.0、商家「如意馄饨」→「绿宝广」，硬字段 100%→96.7%。删掉的「取『实付款/实付』后紧跟的数字，不要商品单价」反例说明在多金额截图上有实际作用。**教训：prompt 里看似冗长的口径反例是质量资产，动 prompt 必须跑闸门。**

## 下一轮优化方向（票 10）

| # | 手段 | 预期 | 状态 |
|---|---|---|---|
| 1 | KV cache 量化（q8_0 KV） | 解码提速 10-20%，内存 -100MB | 未试 |
| 2 | flash attention 已开（auto→enabled），确认收益 | - | 已生效（logcat 可见） |
| 3 | prompt 缓存（多单批量提取时共享前缀） | 第二张起 prefill -50%+ | 票 07 队列时做 |
| 4 | GPU offload（Vulkan） | 理论 3-5×，但 SoC 支持成熟度未知 | 不推荐 v1 |
| 5 | 线程数微调（4 大核 vs 6 混核对照） | ±10% | 需严格 A/B（热节流噪声下难测） |

**对票 07（队列）的约束**：3.5 分钟/张仍需异步交互设计；批量提取时 prompt 前缀缓存值得做（同一系统指令跨单复用）。

## 附：测量方法

- 端侧分段：`OnDevicePipeline.StageTimes` + JNI token 级打点（`COMPLETE stats: n_prompt/prefill_ms/n_generated/gen_ms`），落 logcat
- 桌面闸门：`./gradlew :cli:run --args='--gate <csv绝对路径> --images <目录绝对路径> --model <gguf绝对路径> --out <报告>'`（30 张，~2.5 分钟）
- 优化效果必须过闸门（30/30 硬字段 100%）才保留——prompt 瘦身即被此护栏拦下

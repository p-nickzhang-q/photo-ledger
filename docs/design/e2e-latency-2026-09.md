# 端到端提取耗时现状与优化路线（票 05 实测 → 2026-09-16 三轮优化后更新）

日期：2026-09-16。状态：三轮优化已落地（596s→208s→41.9s→17.4s），当前配置 LiteRT INT8 + GPU。

## TL;DR

票 05 真机端到端首测 **~10 分钟/张**。三轮优化后 **17-19s/张**：

| 轮次 | 手段 | 结果 |
|---|---|---|
| 一（票 05，09-14） | llama.cpp：Q4_K_M + NEON dotprod + 关 OpenMP | 596s → 208s |
| 二（票 13，09-11） | 后端换 LiteRT-LM CPU（dynamic INT8） | 208s → 41.9s |
| 三（票 14，09-16） | LiteRT GPU（OpenCL）+ 采样器修复 | 41.9s → **17.4s** |

质量闸门：硬字段 96.7%/100%、软字段 100%（LiteRT 后端，票 13 数据）；GPU 采样器与 CPU 输出等价（真机 2/2 张对照一致）。

当前瓶颈在 decode ~6 tok/s（Mali-G710 落后官方基准一代 + 热节流）。INT4 GPU 优化档有 5.5s LLM 的潜力但被上游 bug 挡住（见下），留票 10 后评估。17-19s 配合票 07 队列异步化可接受。

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

## 第三轮：LiteRT GPU（票 14，09-16）

llama.cpp CPU 208s → LiteRT CPU 41.9s（票 13）→ **LiteRT GPU 17.4s**。两条根因修复（均曾是票 13 的误判/盲区）：

1. **OpenCL 解锁**：manifest `<uses-native-library android:name="libOpenCL.so"/>`。API 24+ linker namespace 拦截非公开库，与 vivo 无关。
2. **GPU 采样器打包**：AAR 不带 `libLiteRtTopKOpenClSampler.so`，缺失时回退 CPU 采样器——**回退实现与 LLGuidance 约束解码不兼容，输出稳定截断在 145 字符**。从 LiteRT-LM prebuilt（v0.17.0 tag）取 so 放 jniLibs。

实测（真机 GPU，热态）：加载 3.8-4.1s + OCR 1.3s + LLM 15.7-16.8s = **17.3-18.1s**，Draft 与桌面 CPU 完全一致（2/2 张对照）。

**INT4 GPU 优化档（wi4b32，328MB）评估：弃用留观。** 速度惊人（LLM 5.5s，INT8 的 1/3）但所有后端输出固定截断——上游 bug（LiteRT-LM #3577 同版本同现象、#2703 约束解码 premature kDone）。上游修复或修复版 quantizer 自转换后可再提速，留票 10 后。

**10s 未达的原因**：decode ~6 tok/s。官方基准 21 tok/s 是 vivo X300 Pro（天玑 9400 新一代），本机天玑 9000+ 落后一代且实测时热节流（GPU 67°C）。剩余空间：上游 runtime 升级、INT4 修复后启用；prompt 缩短已被闸门否决。

## 下一轮优化方向（票 10 后评估）

| # | 手段 | 预期 | 状态 |
|---|---|---|---|
| 1 | INT4 GPU 档启用（上游 #3577 修复或自转换） | LLM 16.8s→5.5s，端到端 ~7s | 留观 |
| 2 | LiteRT-LM runtime 升级（>0.17.0） | 未知，看 release notes | 留观 |
| 3 | prompt 缓存（多单批量提取时共享前缀） | 第二张起 prefill 部分（GPU prefill 已很快） | 票 07 队列时做 |
| 4 | NPU（mediatek.mt6993 专版） | 仅天玑 9400+，本机不支持 | 不可用 |
| 5 | prompt 缩短 | 有 order_12 回归前科 | 已否决 |

**对票 07（队列）的约束**：17-19s/张，队列异步化后批量 10 张约 3 分钟，交互设计仍需「提取中」态但不能阻塞浏览；GPU 路径加载热态 4s，队列内引擎常驻可摊薄。

## 附：测量方法

- 端侧分段：`OnDevicePipeline.StageTimes` + JNI token 级打点（`COMPLETE stats: n_prompt/prefill_ms/n_generated/gen_ms`），落 logcat
- 桌面闸门：`./gradlew :cli:run --args='--gate <csv绝对路径> --images <目录绝对路径> --model <gguf绝对路径> --out <报告>'`（30 张，~2.5 分钟）
- 优化效果必须过闸门（30/30 硬字段 100%）才保留——prompt 瘦身即被此护栏拦下

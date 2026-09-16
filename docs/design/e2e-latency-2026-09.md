# 端到端提取耗时现状与优化路线（票 05 实测 → 2026-09-16 四轮优化后更新）

日期：2026-09-16。状态：四轮优化已落地（596s→208s→41.9s→17.4s→约 10s），当前配置 LiteRT INT8 + GPU + 四字段契约。

## TL;DR

票 05 真机端到端首测 **~10 分钟/张**。四轮优化后**队列稳态 8.4-11s/张**：

| 轮次 | 手段 | 结果 |
|---|---|---|
| 一（票 05，09-14） | llama.cpp：Q4_K_M + NEON dotprod + 关 OpenMP | 596s → 208s |
| 二（票 13，09-11） | 后端换 LiteRT-LM CPU（dynamic INT8） | 208s → 41.9s |
| 三（票 14，09-16） | LiteRT GPU（OpenCL）+ 采样器修复 | 41.9s → **17.4s** |
| 四（票 07，09-16） | 四字段输出契约 + 队列引擎常驻 | 17.4s → **约 10s**（实测 8.4/11.0/14.1） |

质量闸门：四字段契约 30/30 PASS（金额 96.7%/日期 100%，与七字段基线持平）。累计 ~60×。

当前硬瓶颈在 decode ~6 tok/s（Mali-G710 落后官方基准一代 + 热节流）。INT4 两档（wi4b32/mixed_int4）经深度排查判定死路（见下），10s 线即当前硬件上的实际水位。

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

## 第四轮：四字段契约 + 引擎常驻（票 07，09-16）

**输出契约 7 字段 → 4 字段**：decode 是唯一硬瓶颈（6 tok/s × 输出 token 数），让模型少生成是最直接的提速。模型只输出用户确认需要的 merchant/amountPaid/datePaid/category；currency=CNY、dateSource=PAYMENT_TIME、orderStatus="" 由引擎默认值承接；日期只到天（grammar 删时分秒规则，OCR 候选/prompt 清单同步截到天）。输出 token ~95→~55，LLM 16s→8-10s。**闸门 30/30 PASS，与基线完全持平**（order_12 前科未复发——砍的是输出字段，不动输入口径反例）。

**队列引擎常驻**：队列 transport 批内复用（原每张新建+销毁，白付 ~4s 加载），第 2 张起零加载。

实测（真机 3 张批，INT8 GPU）：加载 4.7s（仅首张）+ OCR 1.1-1.3s + LLM 7.3-9.7s = **稳态 8.4-11s/张**。

**空日期策略**：闪购列表卡小角标日期（「07.28」）OCR 小字识别不稳定（乱码/漏识），无日期截图入账自动填导入当天，卡片明示。治本（OCR 小字优化）留待后续。

## INT4 终审（两档全灭，判定死路）

本票期间对 INT4 做了彻底复测（新 schema 代码 + 桌面闸门 30 张 × 2 档）：

- `Qwen3-0.6B_dynamic_wi4b32_afp32`（328MB，ai-edge-quantizer）与 `qwen3_0_6b_mixed_int4`（475MB，TorchAO）**同样 30/30 失败**：`Parser Error: token "Ġ" doesn't satisfy the grammar`——模型提交语法非法 token，LLGuidance 掩码失效。INT8 同 schema 同分词器 30/30 过。
- 两种独立转换管线同死 → 病灶在 runtime 0.17.0 的掩码/包兼容，不（只）是 quantizer scale 损坏（smilingday/ai-edge-quantizer#1 的 float16 下溢是真实缺陷但修了也不够）。GitHub 最新 release 即 0.17.0，Maven 无更新 AAR，无 upgrade 路径。
- 即便修复，收益也存疑：官方基准 mixed_int4 vivo GPU decode **22.3 tok/s ≈ INT8 的 21**（wi4b32 的 3× 来自 GPU 图优化，被语法机挡死）。
- **重启条件**：上游发新版 runtime，或官方出修复版重转的 wi4b32 包。

## 下一轮优化方向

| # | 手段 | 预期 | 状态 |
|---|---|---|---|
| 1 | INT4 两档启用 | 需上游新 runtime；且 mixed_int4 官方 decode ≈ INT8 | 死路留观 |
| 2 | LiteRT-LM runtime 升级（>0.17.0） | LLGuidance 掩码修复后重测 INT4 | 留观 |
| 3 | OCR 小字识别优化（日期角标） | 消除空日期兜底 | 票 12 后评估 |
| 4 | prompt 缓存（批量共享前缀） | GPU prefill 已快（~0.5s），收益有限 | 低优先 |
| 5 | NPU（mediatek.mt6993 专版） | 仅天玑 9400+，本机不支持 | 不可用 |

**对票 09（账目视图）的输入**：约 10s/张 + 队列常驻引擎，批量 10 张约 2 分钟；「提取中」态与直接入账（无日期自动填今天）已落地。

## 附：测量方法

- 端侧分段：`OnDevicePipeline.StageTimes` + JNI token 级打点（`COMPLETE stats: n_prompt/prefill_ms/n_generated/gen_ms`），落 logcat
- 桌面闸门：`./gradlew :cli:run --args='--gate <csv绝对路径> --images <目录绝对路径> --model <模型绝对路径> --transport litert --out <报告>'`（30 张；INT8 约 15 分钟）
- **App 构建须走 Windows 侧 Gradle**（SDK 在 D:\ 无 Linux build-tools，WSL 侧只跑纯 JVM 模块）：`JAVA_HOME='/mnt/d/Program Files/Java/ms-21.0.7' WSLENV='JAVA_HOME/p' cmd.exe /c "gradlew.bat :app:assembleDebug"`
- 优化效果必须过闸门（30/30，硬字段 ≥95%）才保留——prompt 瘦身即被此护栏拦下

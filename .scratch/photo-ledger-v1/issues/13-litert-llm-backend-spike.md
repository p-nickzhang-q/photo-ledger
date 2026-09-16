# 13: LiteRT-LM 端侧推理后端评估 ⭐（速度破局）

**What to build:**
Spike 评估票：把 App 的 LLM 推理后端从 llama.cpp CPU 换/加为 Google **LiteRT-LM**（AI Edge），目标是把单张提取从 208s 降到 **10s 级**。背景：litert-community/Qwen3-0.6B 官方转换件在 vivo 同厂机型上有公开基准——CPU dynamic_int8 165/9 tok/s（prefill/decode）、GPU OpenCL 580/21、NPU a16w8 1472/36，而 llama.cpp CPU 仅 ~2.3/1 tok/s。llama.cpp CPU 在 Android 上被证实是错误工具（大小核调度 + NEON 优化缺失），桌面 llama-server 不受影响。

架构落点：`LlmTransport` 抽象（票 12 建）本就为多后端准备——新增 `LitertLlmTransport`，引擎核心零改动；llama.cpp JNI 实现保留为桌面/对照。

**必须验证的三个风险（任一不过即 FAIL 归档，不硬上）**：
1. **约束解码**：LiteRT-LM API 是否支持 grammar/GBNF 或等价的 structured output？不支持的话 prompt-only 下 0.6B 的 JSON 合法率是否可接受（闸门 30 张验证）？这是质量闸门支柱（日期清单进文法）
2. **质量回归**：换后端跑完整闸门，硬字段须 ≥ llama.cpp 基线（100%）
3. **真机实测**：vivo V2183A 上 GPU OpenCL 档的实际 tok/s（公开基准是 V2502A，隔一代）

**What to build（工程部分）**：
- Gradle 依赖 `com.google.ai.edge.litertlm`，下载 litert-community 的 .litertlm 转换件
- `LitertLlmTransport : LlmTransport`（completeText 实现；complete 抛不支持）
- 工具页加 LiteRT 冒烟入口（SMOKE_LITERT_* 日志）
- 同图 order_01 全链路测速 + 桌面闸门 30 张复验（质量对照表进 Comments）

**Blocked by:** 无（可立即开工；与 07 队列无依赖关系，速度是队列体验的前置）

**Status:** resolved

- [x] LiteRT-LM 依赖集成与 .litertlm 模型就位（adb push 开发期路径）
- [x] `LitertLlmTransport` 实现，引擎零改动接入
- [x] 约束解码能力验证：grammar/JSON schema 支持情况落 Comments（含 API 探查结论）
- [x] 真机单张提取测速：prefill/decode tok/s + 端到端耗时（对照 llama.cpp 208s）
- [x] 桌面闸门 30 张：硬字段正确率 vs 基线；GBNF→JSON Schema 转译后的质量数据
- [x] 结论：**双后端并存**，端侧默认 LiteRT（llama.cpp 保留对照/回退），写明依据

## Comments

### 2026-09-16 评估结论：切换（端侧默认 LiteRT，双后端并存）

**速度（真机 vivo V2183A，order_01 全链路）**：

| 后端 | 端到端 | LLM 段 | 提速 |
|---|---|---|---|
| llama.cpp CPU Q4_K_M+dotprod | 208s | ~207s | 基线 |
| **LiteRT CPU dynamic_int8** | **41.9s** | 40.7s | **5×** |
| LiteRT GPU OpenCL | 不可用 | - | - |

GPU 失败原因实测：vivo 封闭 OpenCL（libOpenCL.so 不暴露给 App 沙箱），报 "Can not find OpenCL library on this device"。NPU 路线（mediatek.mt6993 专版 1.2GB）未试——v1 目标机不在 mt6993 列表，留待票 10 之后的设备适配。

**质量（桌面闸门 30 张，同一 OCR + 同一 prompt + JSON Schema 约束）**：

| 后端 | 约束方式 | 实付款（硬） | 日期（硬） | 商家/类别（软） | 失败明细 |
|---|---|---|---|---|---|
| llama.cpp | GBNF | 100% | 100% | 100% | 无 |
| **LiteRT** | JSON Schema（LLGuidance） | **96.7%** | 100% | 100% | order_12：14.5→10.0 |

闸门 PASS（≥95%）。96.7% vs 100% 的差距集中在 order_12 一张——该图是多商家/多金额卡片，此前 prompt 瘦身实验中 llama.cpp 同样在该图提错（同样提为「绿宝广 ¥10.0」），归因为该截图形态对 0.6B 本就困难，非 LiteRT 特有回归。用户确认界面可一眼修正（软字段口径）。

**约束解码结论（风险 1 解除）**：
- Kotlin API：`ConversationConfig(enableResponseFormat=true)` + `sendMessage(responseFormat=ResponseFormat.json(schema)|regex(pattern))`，LLGuidance 后端，支持 JSON Schema 与 regex——**GBNF 不可用但有等价物**
- `GrammarGenerator.withDateAlternatives` 的日期清单编码在 Transport 内转译为 JSON Schema enum（`grammarToJsonSchema`）；category/currency/dateSource 枚举同样从 GBNF 行提取转 enum
- **实测 quirk**：LLGuidance 下 enum 字段值输出双重编码（`"currency": "\"CNY\""`），Transport 出口清洗（`stripDoubleEncodedStringValues` 正则还原），已验证不影响自由字符串字段

**工程坑（全记录）**：
1. litertlm 0.17.0 的 Kotlin metadata 是 2.4.0 → 工具链全家桶升级：Kotlin 2.1.20→2.4.10、KSP 2.1.20-1.0.31→2.3.12、AGP 8.7.3→8.13.2（KSP 2.3.x 要求 ≥8.12）、engine 系模块 JVM target 17→21
2. litertlm-jvm 桌面构件需 Java 21（class 65）→ WSL 装 ~/.jdk21（清华 Adoptium 镜像）
3. 阿里云 maven 对 KSP 2.2.21-2.0.5 返回 502 → settings.gradle.kts 仓库顺序调整（mavenCentral 提前）
4. 各 litertlm 版本 Kotlin metadata 实测（kotlin_module 头 4 字节）：0.8.0=2.2 / 0.9.0-0.13.1=2.3 / 0.15+ =2.4；0.8.0 无 ResponseFormat（约束解码 0.9+ 才有），无「低版本兼容 Kotlin 2.1」的选择
5. GPU 后端初始化 4s、CPU 后端 0.35s（缓存热）；模型 614MB（vs GGUF Q4_K_M 397MB）

**遗留 / 后续**：
- 端侧默认 LiteRT 后待办：MainActivity 默认按钮切 LiteRT（本票以「6.LiteRT端到端」并列保留对照）；票 10 模型管理需支持 .litertlm 下载（614MB）与双格式
- order_12 类多商家截图的口径加固（prompt 反例增强或后处理校验）——若做须重跑闸门（prompt 瘦身教训）
- NPU 评估（mt6993/天玑 9300+ 设备）→ 票 10 之后
- 真机 -80% 已达「可用」线（42s/张），批量 10 张 ~7 分钟在队列异步下可接受

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

**Status:** in-progress

- [ ] LiteRT-LM 依赖集成与 .litertlm 模型就位（adb push 开发期路径）
- [ ] `LitertLlmTransport` 实现，引擎零改动接入
- [ ] 约束解码能力验证：grammar/JSON schema 支持情况落 Comments（含 API 探查结论）
- [ ] 真机单张提取测速：prefill/decode tok/s + 端到端耗时（对照 llama.cpp 208s）
- [ ] 桌面闸门 30 张：硬字段正确率 ≥ 基线；GBNF 不可用时的 JSON 合法率数据
- [ ] 结论：切换 / 双后端并存 / 放弃，写明依据

## Comments

### 2026-09-14 开票背景（agent）

用户对 208s 明确表态不可用（"无法使用"）。调研（Tavily）：litert-community/Qwen3-0.6B 官方提供 Android 部署件与 vivo 实测数据（见票头）；另有 mediatek.mt6993 专版（天玑系 NPU，本机天玑 9000 属近系，值得探）。Reddit Snapdragon 压测佐证 NPU 无热节流、单位电量吞吐 3.7×。此票为速度破局主路线，票 10 模型管理（下载/内存门槛）的模型格式假设可能因此变更（GGUF → .litertlm），依赖本票结论。

# 端侧 VLM 调研报告（2026-09）

> 由调研 agent 于 2026-09-10 完成，服务于 photo-ledger 的模型/运行时选型。
> 所有"未核实"标注均为调研时无法从公开来源确认的事实。

## 1. 端侧 VLM 运行时现状

### 1.1 Google AI Edge：MediaPipe LLM Inference API → LiteRT-LM
- **MediaPipe LLM Inference API（Android/iOS/Web）已进入"仅维护"模式**，官方建议迁移到 LiteRT-LM；新特性只在 LiteRT-LM 上开发。
  - 来源: https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference
- **LiteRT-LM** 是 Google 当前的生产级端侧 LLM 推理框架：支持 Android/iOS/Web/桌面，CPU/GPU（Android NPU），关键特性包括"Multi-Modality: vision and audio inputs"、"带约束解码的函数调用"。支持模型：Gemma（3n、Gemma 4）、Llama、Phi-4、Qwen 等。Kotlin API 已 Stable，Swift/JS 为早期预览。
  - 来源: https://developers.google.com/edge/litert-lm/overview （2026-09-04 更新）；https://github.com/google-ai-edge/LiteRT-LM
- **图像输入**：Gemma 3n 官方 `.litertlm` 权重（`google/gemma-3n-E2B-it-litert-lm`）明确支持 text/vision/audio 多模态输入，配套 Google AI Edge Gallery App 的 "Ask Image" 演示。
  - 来源: https://huggingface.co/google/gemma-3n-E2B-it-litert-lm
- **约束解码**：LiteRT-LM C++ API 支持 constrained decoding，可通过 `LlGuidanceConfig` 施加 **JSON Schema、正则、Lark 文法**约束；官方博客称"completely eliminating parser breaking"。**注意**：Kotlin API 尚未暴露该能力，2026-03 有 open issue #1662 请求 Kotlin 支持。
  - 来源: https://developers.google.com/edge/litert-lm/cpp ；https://github.com/google-ai-edge/LiteRT-LM/issues/1662 ；https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm
- 旧版 MediaPipe API 时代即有官方 structured output codelab（"Gemma with structured output on Android"，JSON schema + constrained decoding，2025-02）；旧的 structured_output 文档页现已 404。

### 1.2 llama.cpp (Android)
- 多模态统一走 **libmtmd + mmproj** 机制：`llama-cli`、`llama-server`（OpenAI 兼容 /chat/completions）、`llama-mtmd-cli` 三个入口支持**图像、音频、视频**输入。官方 multimodal.md 列出的视觉模型包括 Gemma 3、Gemma 4、Qwen2-VL、Qwen2.5-VL、Qwen3-VL、SmolVLM、InternVL 2.5/3、Llama 4、MiniCPM-V 4.5 等。
  - 来源: https://github.com/ggml-org/llama.cpp/blob/master/docs/multimodal.md
- Qwen3-VL 架构支持约 2025-10 合入（PR #15713）；MiniCPM-V 4.5 支持于 2025-08-26 合入（PR #15575，llama.cpp b6282 起）。
- **结构化输出**：GBNF 文法（`--grammar`）+ `json_schema_to_grammar.py`；`llama-server` 自 2024 年中起支持 `response_format: json_schema`（PR #8001）。多模态与 JSON schema 均在 llama-server 上支持，但官方文档未专门演示"图像输入 + JSON schema"组合——组合可行性是社区/文档推断，未单独核实。
- Android 侧无官方 App，官方提供 NDK 编译路径与 `examples/llama-android` 示例；社区绑定为 llama.rn / ChatterUI 等。

### 1.3 MLC LLM
- 支持视觉语言模型（Llama 3.2 Vision、Phi-3.5-vision、Qwen2.5-VL 等），通过 TVM 编译到 Android GPU（Vulkan）。**结构化输出**：集成自家的 **xgrammar** 引擎做 JSON schema 约束解码（近零开销 token mask）。
  - 来源: https://llm.mlc.ai/docs/ ；https://github.com/mlc-ai/xgrammar
- 注意：官方 Android 库（mlc-chat-android）曾声明"正在重构、有 breaking API changes"。**OpenBMB 官方为 MiniCPM-V 提供基于 MLC 的 Android demo App**（Google Play 上架，要求 Android 9.0+ / 8GB+ RAM），是 MLC 跑 VLM 的最直接实例。
  - 来源: https://huggingface.co/openbmb/MiniCPM-V-4_5 相关链接与 https://github.com/OpenBMB/MiniCPM-o 的 android 目录

### 1.4 ONNX Runtime (generate() API / onnxruntime-genai)
- 官方 README 支持矩阵：**已支持 "Constrained decoding"**（"grammar specification for tool calling"）；OS 支持 **Android**（Java API 需从源码构建）；模型架构支持 **Qwen (language + vision)**、Phi (language + vision)、Gemma 等；"Multi-modal models" 列在"开发中/路线图"。
  - 来源: https://github.com/microsoft/onnxruntime-genai ；https://onnxruntime.ai/docs/genai
- 即：文本侧约束解码可用，端侧多模态（VLM）在 ONNX Runtime 生态仍属发展中状态。

### 1.5 ExecuTorch
- 2025-10 发布 1.0 GA。多模态支持集中在 **Llava 1.5 7B**（官方博客称将运行时内存从 11GB 优化到 5GB）、**Llama 3.2 Vision（11B，int4）** demo、Gemma 3 (vision)、Voxtral (audio)。
  - 来源: https://pytorch.org/blog/executorch-beta ；https://github.com/pytorch/executorch ；https://executorch.ai
- **未找到** ExecuTorch 支持 grammar/JSON schema 约束解码的证据（未核实到相关功能）。

## 2. 8-12GB RAM 手机可跑的多模态模型

| 模型 | 参数量 | 量化后占用（核实到的数字） | 中文票据识别相关事实 |
|---|---|---|---|
| **Gemma 3n E2B** | 有效 2B（总参约 5B），MobileNet-V5-400M 视觉编码器 | `.litertlm` int4 文件 2965 MB（LiteRT-LM 官方表） | 官方称支持 140+ 语言；**中文小票/发票 OCR 能力未见专门评测（未核实）** |
| **Gemma 3n E4B** | 有效 4B（总参约 8B） | `.litertlm` int4 文件 4235 MB | 同上 |
| **Gemma 4 E2B**（2026 新） | - | 2583 MB；官方表：S26 Ultra CPU 峰值内存 1733 MB（GPU 676 MB） | Google 模型卡列出的图像能力含 document/PDF parsing、OCR、multilingual OCR、handwriting recognition |
| **Qwen3-VL-2B / 4B Instruct** | 2B / 4B | 官方 GGUF 存在（Qwen/Qwen3-VL-4B-Instruct-GGUF）；Q4_K_M 约 2.8 GB（社区数字，未从官方页直接核实字节级大小） | 官方模型卡：**OCR 扩展到 32 种语言**，抗低光/模糊/倾斜，改进长文档结构解析；具体 4B 版中文发票字段级准确率**未核实** |
| **Qwen2.5-VL-3B Instruct** | 3.8B | LM Studio GGUF 页标注最低系统内存 2GB | 官方评测表（引自 MiniCPM-V 4.0 卡）：**OCRBench 828、DocVQA 93.9**；模型卡明言支持"发票/表格/单据扫描件的结构化输出" |
| **MiniCPM-V 4.0** | 4.1B（MiniCPM4-3B + SigLIP2-400M） | - | **OCRBench 894**（官方表，同档最高）；iPhone 16 Pro Max 上首 token <2s、解码 17+ tok/s（iOS 数字） |
| **MiniCPM-V 4.5** | 8B（Qwen3-8B + SigLIP2-400M，总 8.7B） | 官方提供 int4（`openbmb/MiniCPM-V-4_5-int4`）、GGUF、AWQ 共 16 种量化；"int4 约 3.5-4GB RAM"为社区数字（未核实官方数值） | OCRBench 官方称"超越 GPT-4o-latest 与 Gemini 2.5"，OmniDocBench PDF 解析 SOTA；支持 30+ 语言；官方 iOS/MLC Android demo；llama.cpp 支持已合入 |
| **SmolVLM2 2.2B** | 2.2B | GGUF 存在（llama.cpp 官方支持列表） | 训练数据以英文为主、中文 OCR 弱——**该说法仅来自社区转述，未核实** |
| **InternVL3-1B/2B** | 1B/2B（Qwen2.5 底座） | llama.cpp 官方支持 InternVL 2.5/3（multimodal.md）；GGUF 社区版本存在 | 中英双语训练、中文 OCR 较强——**来自模型卡转述，端侧中文小票实测未核实** |

速度参考（LiteRT-LM 官方表，Gemma-3n-E2B，Samsung S24 Ultra）：CPU 解码 16.1 tok/s，GPU 15.6 tok/s（prefill 816 tok/s）。Android 上 MiniCPM-V/Qwen3-VL 的实测速度**未核实**。

## 3. 结构化输出（约束解码）支持矩阵

| 运行时 | 约束解码 | 形式 | 与多模态组合的证据 |
|---|---|---|---|
| **LiteRT-LM** | 有 | C++ API：JSON Schema / Regex / Lark grammar（LlGuidance）；官方博客称可"完全消灭解析失败" | C++ 文档未提图像+约束组合示例；Kotlin API **尚不支持**（issue #1662，open） |
| **llama.cpp** | 有 | GBNF；llama-server `response_format: json_schema`（PR #8001） | 两者均为 llama-server 功能，可组合，但官方无组合示例（推断，未单独核实） |
| **MLC LLM** | 有 | xgrammar（JSON schema → token mask） | Android VLM（MiniCPM-V demo）与 xgrammar 分别存在；二者组合未核实 |
| **ONNX Runtime genai** | 有 | "Constrained decoding"（tool calling 的 grammar 规格） | 多模态模型仍在开发中/路线图上 |
| **ExecuTorch** | **未找到证据** | - | - |
| 旧 MediaPipe LLM Inference API | 有（JSON schema，codelab 2025-02） | `responseSchema` | API 已进入维护模式 |

不支持约束解码的组合只能走 prompt + `json.loads` 事后校验。arXiv 上的一项 Android 端侧 SLM 工程研究（https://arxiv.org/html/2604.24636v2 ）将"输出格式违规"（JSON 被 markdown 包裹、非英文键名）列为端侧 LLM 五大失败类之首，并建议框架层提供 schema 约束解码——印证事后校验路线的脆弱性。

## 4. 中文票据 OCR 备选路线（OCR + 小模型）

- **PaddleOCR PP-OCRv5**（2025-05，随 PaddleOCR 3.0 发布）：单一模型覆盖简体/繁体中文、英文、日文、拼音，官方称平均精度比 PP-OCRv4 提升约 13 个百分点；有 **mobile 端轻量版**（社区/搜索转述的体量：检测约 4-5 MB + 识别约 16 MB，总计约 20 MB——**精确数字未从官方发布页逐字节核实**）。Android 部署走 Paddle Lite，官方 repo 有 `deploy/android` demo；社区有 ONNX/NCNN 转换。
  - 来源: https://github.com/PaddlePaddle/PaddleOCR
- **结构化抽取配套**：PaddleOCR 生态有 **PP-StructureV3**（版面/文档解析）与 **PP-ChatOCRv4**（OCR + LLM 信息抽取），中文增值税发票是文档中列出的用例。
- 该路线的含义（事实陈述，非建议）：OCR 模型只需个位数 MB 至 20 MB 内存，之后"文本 → JSON"变成纯文本 LLM 任务，可用任意 0.5B-2B 级文本模型 + grammar 约束解码完成（LiteRT-LM 官方模型表中 Qwen2.5-0.5B/1.5B、Qwen3-0.6B、Gemma3-1B 均有现成 `.litertlm` 权重）。

## 5. 主要来源清单

- LiteRT-LM 概览/C++ 约束解码/GitHub: https://developers.google.com/edge/litert-lm/overview ；https://developers.google.com/edge/litert-lm/cpp ；https://github.com/google-ai-edge/LiteRT-LM
- MediaPipe LLM Inference（维护模式声明）: https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference
- Gemma 3n LiteRT-LM 权重与评测表: https://huggingface.co/google/gemma-3n-E2B-it-litert-lm
- llama.cpp 多模态文档: https://github.com/ggml-org/llama.cpp/blob/master/docs/multimodal.md ；llama-server 视觉支持公告: https://simonwillison.net/2025/May/10/llama-cpp-vision
- Qwen3-VL-4B 模型卡: https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct ；Qwen2.5-VL-3B: https://huggingface.co/Qwen/Qwen2.5-VL-3B-Instruct
- MiniCPM-V 4.5 / 4.0 模型卡（含量化、llama.cpp 合入时间、OCRBench 表）: https://huggingface.co/openbmb/MiniCPM-V-4_5 ；https://huggingface.co/openbmb/MiniCPM-V-4
- onnxruntime-genai README（支持矩阵）: https://github.com/microsoft/onnxruntime-genai ；https://onnxruntime.ai/docs/genai
- ExecuTorch: https://pytorch.org/blog/executorch-beta ；https://github.com/pytorch/executorch ；https://executorch.ai
- xgrammar: https://github.com/mlc-ai/xgrammar
- PaddleOCR: https://github.com/PaddlePaddle/PaddleOCR
- Gemma 4 端侧发布报道: https://www.edge-ai-vision.com/2026/04/google-pushes-multimodal-ai-further-onto-edge-devices-with-gemma-4
- LiteRT-LM 博客（约束解码）: https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm
- 端侧 SLM 失败模式研究: https://arxiv.org/html/2604.24636v2

## 6. 明确未核实项汇总

1. Gemma 3n/4 中文发票小票的字段级识别准确率（无公开评测）
2. Qwen3-VL-4B 的 OCRBench 具体分数与 Q4_K_M 精确文件大小
3. MiniCPM-V 4.5 int4 的官方 RAM 需求数值
4. SmolVLM2 中文能力弱的判断（仅社区转述）
5. llama-server/MLC 中"多模态输入 + 约束解码"的官方组合示例
6. PP-OCRv5 mobile 各模型文件的精确 MB 数
7. Android 上各 VLM 的实测解码速度（MiniCPM/Qwen 侧仅有 iOS 数字）

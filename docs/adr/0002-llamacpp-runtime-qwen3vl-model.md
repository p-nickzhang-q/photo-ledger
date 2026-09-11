# 推理运行时选 llama.cpp（自建 JNI 绑定），文本模型定稿 Qwen3-0.6B

本产品的成败押在中文订单截图的提取质量上，选型跟着模型走而不是跟着集成舒适度走：调研（2026-09，见 docs/research/on-device-vlm-2026-09.md）显示中文 OCR 能力最强的端侧候选是 Qwen 系和 MiniCPM 系，而它们没有 LiteRT-LM 官方权重；LiteRT-LM 上唯一现成的视觉模型 Gemma 3n/4 的中文票据识别能力无任何公开评测。llama.cpp 的 GBNF 约束解码成熟，且 GGUF 模型菜单在候选模型之间切换成本近零。代价是自建 NDK 编译 + JNI 绑定。

模型档位结论（2026-09-11 定稿，取代原 Qwen3-VL-4B 选型）：

- **提取路线已变更为「OCR + 文本小模型」**（ADR-0004），结构化模型不再需要视觉能力。
- **文本模型定稿：Qwen3-0.6B Q8_0**（约 640MB）。依据：同一数据集（30 张真实订单截图）、同一 harness（票 02）下，0.6B 硬字段 100% / 5m0s 全量，对照 Qwen3-1.7B Q8_0 硬字段 96.7%（金额错 1 例）/ 17m0s（慢 3.4 倍）——更大模型无质量收益，端侧耗时成本显著。
- **OCR 档位定稿：PP-OCRv5 系 mobile 档**（桌面经 RapidOCR/ONNX Runtime 验证）。闸门数据中 OCR 无一失败（30/30 硬字段全对），mobile 档够用，无需评估 server 版。
- 质量闸门 PASS（票 02），0.6B+OCR 组合达标；MiniCPM-V 4.5 对比项随 VLM 主路线一并搁置。

历史决策记录（2026-09-10）：原选 Qwen3-VL-4B-Instruct Q4_K_M 为主选、MiniCPM-V 4.5 双候选对比——该选型基于 VLM 端到端路线，因端侧 vision prefill 耗时不可行（ADR-0004）而失效；llama.cpp 运行时选择不受影响。

Considered Options：
- LiteRT-LM：Kotlin API 稳定、集成最省事，但视觉模型锁死 Gemma（中文能力未验证），且约束解码仅 C++ API（Kotlin 未暴露，open issue #1662），只能 prompt + 事后校验。
- MLC LLM：有 xgrammar 与 MiniCPM 官方 Android demo，但 Android API 正在大改（官方声明 breaking changes）。
- ONNX Runtime genai：多模态仍在路线图上。
- ExecuTorch：未找到约束解码证据。
- Qwen3-1.7B Q8_0：对照候选（本 ADR 数据），质量/速度双输于 0.6B，记录备查。

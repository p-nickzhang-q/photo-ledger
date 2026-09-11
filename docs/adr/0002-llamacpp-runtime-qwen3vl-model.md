# 推理运行时选 llama.cpp（自建 JNI 绑定），模型主选 Qwen3-VL-4B

本产品的成败押在中文订单截图的提取质量上，选型跟着模型走而不是跟着集成舒适度走：调研（2026-09，见 docs/research/on-device-vlm-2026-09.md）显示中文 OCR 能力最强的端侧候选是 Qwen 系和 MiniCPM 系，而它们没有 LiteRT-LM 官方权重；LiteRT-LM 上唯一现成的视觉模型 Gemma 3n/4 的中文票据识别能力无任何公开评测。llama.cpp 官方支持 Qwen3-VL / MiniCPM-V（libmtmd + mmproj），GBNF 约束解码成熟，且 GGUF 模型菜单在候选模型之间切换成本近零。代价是自建 NDK 编译 + JNI 绑定。

模型档位结论（2026-09-10，目标机 vivo V2183A / Android 16 / 11.7GB RAM）：Qwen3-VL-4B-Instruct Q4_K_M（约 2.8GB 文件）为主选。原型阶段与 MiniCPM-V 4.5（int4/GGUF）做双候选对比，胜者上机。GGUF 生态下换模型不换运行时，故本 ADR 的不可逆部分是运行时选择，不是具体模型。

Considered Options：
- LiteRT-LM：Kotlin API 稳定、集成最省事，但视觉模型锁死 Gemma（中文能力未验证），且约束解码仅 C++ API（Kotlin 未暴露，open issue #1662），只能 prompt + 事后校验。
- MLC LLM：有 xgrammar 与 MiniCPM 官方 Android demo，但 Android API 正在大改（官方声明 breaking changes）。
- ONNX Runtime genai：多模态仍在路线图上。
- ExecuTorch：未找到约束解码证据。

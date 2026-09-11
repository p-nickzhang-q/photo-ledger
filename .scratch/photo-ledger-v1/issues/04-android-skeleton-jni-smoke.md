# 04: Android 骨架与端侧推理冒烟

**What to build:**
可安装到目标机（vivo V2183A）的 APK 骨架：项目脚手架（Kotlin + Compose + Room，包名 io.github.pnickzhangq.photoledger，显示名"照片记账"）+ 端侧推理冒烟。

路线随 ADR-0004 变更后，端侧推理包含两个组件，本票都要冒烟通过：

1. **OCR 移动端部署**：PP-OCRv5 mobile 系（Paddle Lite 或 ONNX Runtime Mobile，选型在本票定）在真机对一张截图产出文本行（含坐标），记录耗时与内存。桌面用 RapidOCR/ONNX 已验证（01），移动端优先 ONNX Runtime 以与桌面同构。
2. **llama.cpp 文本推理**：NDK 交叉编译 + JNI 绑定，加载 Qwen3-0.6B Q8_0（03 定稿前的占位），输入一句 prompt 推理上屏。相比原票大幅简化：纯文本模型，无 mmproj/图像嵌入，JNI 边界只剩标准 decode loop + grammar（若 JNI 侧集成 GBNF 则复用 engine 的 GrammarGenerator 输出）。

集成方式参照票 01 Comments：桌面走 llama-server HTTP 是 POC 便利；Android 端走进程内 JNI（llama-server 子进程方案不适用于单 App 场景，除非 05 实测证明 JNI 不可行再回退）。

开发期模型/OCR 模型文件经 adb 推送到设备（面向用户的模型下载/导入属 10）。最低支持线 Android 10 (API 29) + 8GB RAM 的构建配置在本票定型。

**Blocked by:** 01-resolved (桌面提取管线打通——复用其集成方式结论), 12 (OCR 提取管线正式化——OCR 预处理逻辑与移动端保持同构)

**Status:** ready-for-agent

- [ ] APK 可安装到目标机并启动，无崩溃
- [ ] 真机 OCR：一张真实截图 → 文本行（含坐标）上屏，耗时与内存记录进 Comments
- [ ] 真机 llama.cpp：加载 0.6B 文本模型，一句 prompt 推理上屏；NDK 编译进 Gradle 构建，步骤记录进 Comments
- [ ] adb 推送模型/OCR 模型的开发期约定记录进 Comments（路径、加载代码入口），供 05/10 复用

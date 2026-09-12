# 04: Android 骨架与端侧推理冒烟

**What to build:**
可安装到目标机（vivo V2183A）的 APK 骨架：项目脚手架（Kotlin + Compose + Room，包名 io.github.pnickzhangq.photoledger，显示名"照片记账"）+ 端侧推理冒烟。

路线随 ADR-0004 变更后，端侧推理包含两个组件，本票都要冒烟通过：

1. **OCR 移动端部署**：PP-OCRv5 mobile 系（Paddle Lite 或 ONNX Runtime Mobile，选型在本票定）在真机对一张截图产出文本行（含坐标），记录耗时与内存。桌面用 RapidOCR/ONNX 已验证（01），移动端优先 ONNX Runtime 以与桌面同构。
2. **llama.cpp 文本推理**：NDK 交叉编译 + JNI 绑定，加载 Qwen3-0.6B Q8_0（03 定稿前的占位），输入一句 prompt 推理上屏。相比原票大幅简化：纯文本模型，无 mmproj/图像嵌入，JNI 边界只剩标准 decode loop + grammar（若 JNI 侧集成 GBNF 则复用 engine 的 GrammarGenerator 输出）。

集成方式参照票 01 Comments：桌面走 llama-server HTTP 是 POC 便利；Android 端走进程内 JNI（llama-server 子进程方案不适用于单 App 场景，除非 05 实测证明 JNI 不可行再回退）。

开发期模型/OCR 模型文件经 adb 推送到设备（面向用户的模型下载/导入属 10）。最低支持线 Android 10 (API 29) + 8GB RAM 的构建配置在本票定型。

**Blocked by:** 01-resolved (桌面提取管线打通——复用其集成方式结论), 12 (OCR 提取管线正式化——OCR 预处理逻辑与移动端保持同构)

**Status:** in-progress

- [ ] APK 可安装到目标机并启动，无崩溃
- [ ] 真机 OCR：一张真实截图 → 文本行（含坐标）上屏，耗时与内存记录进 Comments
- [ ] 真机 llama.cpp：加载 0.6B 文本模型，一句 prompt 推理上屏；NDK 编译进 Gradle 构建，步骤记录进 Comments
- [ ] adb 推送模型/OCR 模型的开发期约定记录进 Comments（路径、加载代码入口），供 05/10 复用

---

## Comments

### 2026-09-11 实施记录（进行中）

**选型定案（本票两项）**：

1. **OCR = ONNX Runtime Android 1.20.0 + 桌面同款 PP-OCRv4 mobile ONNX 三件套原样复用**。桌面闸门（票 02）用的就是 RapidOCR 内嵌的 v4 三件套，移动端直接推同文件 → 模型层面零偏移。检测后处理（DB 后处理）Kotlin 重写：连通域 + AABB（截图文本全水平，无需旋转矩形/透视）；识别后处理 CTC decode Kotlin 重写，**字典内嵌在 rec ONNX metadata 的 `character` 键（6623 行），运行时读 metadata，无需额外 dict 文件**——输出维度 6625 = blank(0) + dict(6623) + space(6624)，与 RapidOCR CTCLabelDecode 同序。
2. **llama.cpp = NDK 交叉编译（AGP externalNativeBuild，CMake add_subdirectory third_party/llama.cpp）+ 进程内 JNI**。JNI 边界一次式 `complete(ctx, prompt, grammar, nLen)`：grammar 非空即挂 GBNF sampler（文法由 engine GrammarGenerator 生成后传入），贪心解码，KV 用后即清（context 可复用）。无流式回调——GBNF 约束下输出是短 JSON，无需流式。

**构建链定型（WSL2 开发环境）**：

- SDK/NDK 在 Windows 侧（D:\Android\Sdk），**构建走 Windows Gradle**：`cmd.exe /c "set JAVA_HOME=D:\Program Files\Java\ms-21.0.7&& gradlew.bat :app:assembleDebug"`（Windows PATH 默认 JDK 8 不可用，必须显式 ms-21.0.7）
- NDK 27.0.12077973 / CMake 3.22.1 / AGP 8.7.3 / compileSdk 36（警告 AGP 测试到 35，冒烟可接受）/ minSdk 29 / arm64-v8a only
- WSL 侧跑 `ANDROID_HOME=/mnt/d/Android/Sdk ./gradlew :engine:test`（纯 Kotlin 模块无 aapt2 依赖）
- engine 模块补 `jvmTarget=17` 显式声明（Windows JDK 21 下 Kotlin/Java target 不一致会失败）
- CMake 相对路径：`app/src/main/cpp` → repo root 是 **4 层** `../../../../`（官方示例嵌在 llama.cpp 仓库内多一层，照抄会错）
- `common.h` 的 include 路径不随 common 库 target 传导，需 `target_include_directories(... third_party/llama.cpp/common)`

**adb 推送约定**（详见 `docs/design/android-dev-model-paths.md`）：App 专属外部目录 `/sdcard/Android/data/io.github.pnickzhangq.photoledger/files/`（scoped storage 免权限直读，adb 可直接 push；adb.exe 源路径须写 Windows 风格 `D:\\...`）。加载入口 `MainActivity.scanModelDir()`：文件名前缀匹配 det/rec/cls，`.gguf` 取最大。

（真机冒烟数据待补）

# 04: Android 骨架与端侧推理冒烟

**What to build:**
可安装到目标机（vivo V2183A）的 APK 骨架：项目脚手架（Kotlin + Compose + Room，包名 io.github.pnickzhangq.photoledger，显示名"照片记账"）+ 端侧推理冒烟。

路线随 ADR-0004 变更后，端侧推理包含两个组件，本票都要冒烟通过：

1. **OCR 移动端部署**：PP-OCRv5 mobile 系（Paddle Lite 或 ONNX Runtime Mobile，选型在本票定）在真机对一张截图产出文本行（含坐标），记录耗时与内存。桌面用 RapidOCR/ONNX 已验证（01），移动端优先 ONNX Runtime 以与桌面同构。
2. **llama.cpp 文本推理**：NDK 交叉编译 + JNI 绑定，加载 Qwen3-0.6B Q8_0（03 定稿前的占位），输入一句 prompt 推理上屏。相比原票大幅简化：纯文本模型，无 mmproj/图像嵌入，JNI 边界只剩标准 decode loop + grammar（若 JNI 侧集成 GBNF 则复用 engine 的 GrammarGenerator 输出）。

集成方式参照票 01 Comments：桌面走 llama-server HTTP 是 POC 便利；Android 端走进程内 JNI（llama-server 子进程方案不适用于单 App 场景，除非 05 实测证明 JNI 不可行再回退）。

开发期模型/OCR 模型文件经 adb 推送到设备（面向用户的模型下载/导入属 10）。最低支持线 Android 10 (API 29) + 8GB RAM 的构建配置在本票定型。

**Blocked by:** 01-resolved (桌面提取管线打通——复用其集成方式结论), 12 (OCR 提取管线正式化——OCR 预处理逻辑与移动端保持同构)

**Status:** resolved

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

### 2026-09-14 真机冒烟结果（全部通过，票 resolve）

目标机 vivo V2183A（Android 16 / arm64-v8a / 11.7GB），复现命令：

```
adb shell am start -n io.github.pnickzhangq.photoledger/.MainActivity \
  --es smoke_image /sdcard/Android/data/io.github.pnickzhangq.photoledger/files/order_01.png \
  --ez smoke_llm true
adb logcat -s photoledger-smoke:V   # SMOKE_* 信号全落日志，不依赖读屏
```

| 环节 | 结果 |
|---|---|
| APK 安装启动 | Success，无崩溃（app-debug.apk 53MB，含 llama.cpp 全量 .so）|
| OCR（order_01 真实截图）| `SMOKE_OCR_OK lines=13 ms=1380`——13 行文本+坐标+置信度，与桌面 RapidOCR 同款结果 |
| LLM 加载（Qwen3-0.6B Q8_0）| `SMOKE_LLM_LOAD_OK ms=2470`（mmap 加载） |
| LLM 推理 | `SMOKE_LLM_OK ms=44438 output=2`——prompt「问：1+1=?」输出「2」等 4 个选项，答对。44s 生成约 32 token ≈ **1.4 tok/s**（Q8_0 + 手机 8 核 CPU，符合预期量级；GBNF 约束生成在票 05 端到端验证）|

**实施中踩的三个坑（后续票引以为鉴）**：

1. **scoped storage EACCES**：硬编码 `/sdcard/Android/data/<pkg>/files/` 路径 App 读自己目录会被拒（adb 能 push 进去但 App open() 报 Permission denied）。必须用 `getExternalFilesDir(null)` API 取路径。smoke_image intent 传入的硬编码路径也经 `resolveUnderPushDir` 归一。
2. **单线程池死锁**：初版用 `Executors.newSingleThreadExecutor()`，onCreate 冒烟链路持有线程 awaitOcrThen 轮询等 OCR，OCR 任务排在同池队列里永远进不去。重构为 kotlinx-coroutines：顺序链路顺序写（`loadBitmap → runOcr → loadLlm → runLlm`），CPU 密集走 `Dispatchers.Default`，IO 走 `Dispatchers.IO`，`lifecycleScope` 随 Activity 取消。
3. **JNI 函数名映射**：Kotlin `private external fun nativeBackendInit()` 对应 JNI 符号必须带 `native` 前缀（`Java_..._LlamaNative_nativeBackendInit`），初版漏了前缀导致 `UnsatisfiedLinkError: No implementation found`。另外增量构建偶发不重打包 .so（Kotlin-only 改动后 APK 缺 libphotoledger.so）——改 C++ 后建议清 `.cxx`/cxx intermediates 全量重建。

**推理速度备注**：0.6B Q8_0 在 8 核手机 CPU 1.4 tok/s。票 05 的实际提取任务（prompt ~700 token + 输出 ~60 token）预计 prefill + 生成共 60-90 秒/单，可接受但偏慢；优化方向（票 05+ 备选）：Q4_K_M 量化（内存与速度均减半，质量需闸门复验）、线程数调优（当前默认 8）、`-O3` Release 构建当前是 Debug .so。

**遗留到票 05**：OCR 结果与 LLM 输入的拼接（端到端截图→Draft JSON）、GBNF 约束解码真机验证（JNI complete 已支持 grammar 参数）。

# Android 开发期模型推送约定（票 04）

适用：票 04-05（冒烟/端到端）、票 10 正式化前的开发期。面向用户的模型下载/导入在票 10 实现。

## 约定路径

App 专属外部存储（scoped storage 下 App 免权限直读，adb 可直接 push）：

```
/sdcard/Android/data/io.github.pnickzhangq.photoledger/files/
├── ch_PP-OCRv4_det_infer.onnx       # 文本检测（PP-OCRv4 mobile，与桌面 RapidOCR 同款）
├── ch_PP-OCRv4_rec_infer.onnx       # 文本识别（字典内嵌 ONNX metadata 'character'，6623 行）
├── ch_ppocr_mobile_v2.0_cls_infer.onnx  # 方向分类（截图全正向，冒烟未用）
└── Qwen3-0.6B-Q8_0.gguf             # 文本小模型（票 03 定稿）
```

## adb 命令（WSL 侧）

Windows adb 路径（WSL 内无 adb，见 memory）：

```bash
ADB=/mnt/d/Android/Sdk/platform-tools/adb.exe

# 一次性推齐四件
$ADB shell mkdir -p /sdcard/Android/data/io.github.pnickzhangq.photoledger/files
$ADB push \
  "D:\\Android\\Projects\\photo-ledger\\.scratch\\device-push\\ch_PP-OCRv4_det_infer.onnx" \
  "D:\\Android\\Projects\\photo-ledger\\.scratch\\device-push\\ch_PP-OCRv4_rec_infer.onnx" \
  "D:\\Android\\Projects\\photo-ledger\\.scratch\\device-push\\ch_ppocr_mobile_v2.0_cls_infer.onnx" \
  "D:\\Android\\Projects\\photo-ledger\\models\\Qwen3-0.6B-Q8_0.gguf" \
  /sdcard/Android/data/io.github.pnickzhangq.photoledger/files/
```

注意：
- `adb.exe` 是 Windows 程序，**源路径必须写 Windows 风格**（`D:\\...`），不能用 `/mnt/d/...`
- OCR 三件套来源：桌面 `~/.ocr-venv/.../rapidocr_onnxruntime/models/`，已复制一份到 `.scratch/device-push/`（gitignore）作为中转

## App 侧加载入口

`MainActivity.scanModelDir()`（票 04）：按文件名前缀匹配 det/rec/cls，`.gguf` 后缀取最大者为 LLM 模型。票 05 起 OCR/LLM 加载逻辑迁入正式 service 层时沿用此约定。

## 构建命令（WSL -> Windows 工具链）

SDK/NDK 是 Windows 工具链，构建走 Windows 侧 Gradle（WSL 只跑 engine 纯 Kotlin 测试）：

```bash
cmd.exe /c "set JAVA_HOME=D:\Program Files\Java\ms-21.0.7&& D:\Android\Projects\photo-ledger\gradlew.bat :app:assembleDebug"
```

- JDK：`D:\Program Files\Java\ms-21.0.7`（Microsoft OpenJDK 21；Windows 默认 PATH 里是 JDK 8，必须显式指定）
- NDK：27.0.12077973（arm64-v8a only，票 04 冒烟阶段 abiFilter）
- CMake：3.22.1
- 产物：`app/build/outputs/apk/debug/app-debug.apk`

## 票 14 补充（LiteRT GPU）

- LLM 模型用 `Qwen3-0.6B.litertlm`（INT8，586MB）。**INT4 档（dynamic_wi4b32）勿放 files 目录**：上游 bug 输出截断（#3577），且 scanModelDir 取最后一个 .litertlm——备份文件改名留存会误被选中，备份须挪出 files。
- GPU 后端需要 `app/src/main/jniLibs/arm64-v8a/libLiteRtTopKOpenClSampler.so`（已在库，来自 LiteRT-LM prebuilt v0.17.0）。缺失时 GPU 静默回退 CPU 采样器，输出截断 145 字符。
- smoke intent：`--ez smoke_e2e_litert true`，可选 `--es litert_backend cpu|gpu` 强制后端（默认 GPU，init 失败自动降级）。

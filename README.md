# Photo Ledger 照片记账

把订单截图变成账目——**全程端侧、离线、零上传**的 Android 记账应用。

从相册多选或系统分享订单截图，应用在手机本地完成 OCR 识别与大模型字段提取，生成待确认的账目草稿，一键入账。

## 功能

- **批量导入**：相册多选（最多 20 张）或相册/微信「分享到照片记账」，汇入同一导入队列，逐张提取、实时显示进度
- **智能提取**：自动识别商家、实付款金额、日期、消费分类；一图多单自动拆分
- **导入队列**：内容哈希去重（重复截图提示「已导入过」）、单张失败不阻塞、失败项可手工录入
- **快速核对**：队列卡片带截图缩略图，点击全屏缩放对照提取结果；确认直接入账
- **手工记账**：截图之外的消费可手工补录
- **账目管理**：列表浏览、编辑、删除（可选连截图一起删）

## 工作原理

```
订单截图 → PP-OCRv4 端侧 OCR → 文本后处理（日期/金额规范化、订单块拆分）
         → Qwen3-0.6B（LiteRT-LM，OpenCL GPU 加速，约束解码保证输出结构）
         → 账目草稿 → 确认入账（Room + 截图留档）
```

- **约束解码**：日期从截图识别出的候选清单中「选择题作答」，金额/分类由 JSON Schema 强约束——0.6B 小模型也能稳定输出结构化结果
- **四字段契约**：模型只生成商家/金额/日期/分类四个字段（币种、口径、订单状态由引擎默认值承接），输出 token 减半，速度翻倍
- **全端侧**：模型与推理均在手机本地，截图不离开设备

## 性能

端到端提取单张截图（vivo V2183A，天玑 9000+）：

| 阶段 | 优化 | 耗时 |
|---|---|---|
| 基线 | llama.cpp Q8_0 无优化 | ~10 分钟 |
| 第一轮 | Q4_K_M + NEON dotprod + 关 OpenMP | 3.5 分钟 |
| 第二轮 | LiteRT-LM CPU（INT8） | 41.9s |
| 第三轮 | LiteRT GPU（OpenCL）+ 采样器修复 | 17.4s |
| 第四轮 | 四字段契约 + 引擎常驻 | **约 10s**（队列稳态 8.4-11s） |

质量以 30 张标注截图的闸门度量：实付款 96.7%、日期 100%、商家/分类 100%。详见 [docs/design/e2e-latency-2026-09.md](docs/design/e2e-latency-2026-09.md)。

## 项目结构

| 模块 | 说明 |
|---|---|
| `app/` | Android 应用（Compose UI + Room + LiteRT-LM） |
| `engine/` | 纯 Kotlin 共享引擎：OCR 后处理、prompt/约束生成、结果归一、闸门评分 |
| `engine-llamacpp/` | llama.cpp 后端（早期路线，对照保留） |
| `cli/` | 桌面质量闸门（30 张标注集回归验证） |
| `models/` | 模型文件（不入库，见下） |
| `docs/` | 领域文档（`CONTEXT.md`）、ADR、设计文档 |
| `.scratch/photo-ledger-v1/issues/` | 工单（票 01-14） |

## 构建运行

**要求**：Android Studio（或 Windows 侧 JDK 21 + Android SDK）+ 真机（需 ≥6GB 内存以加载 0.6B 模型）。

1. 下载模型并推到真机（应用专属目录，ModelScope 国内直连）：

   ```bash
   curl -L -o models/Qwen3-0.6B.litertlm \
     "https://modelscope.cn/models/litert-community/Qwen3-0.6B/resolve/master/Qwen3-0.6B.litertlm"
   adb push models/Qwen3-0.6B.litertlm \
     /sdcard/Android/data/<应用包名>/files/
   ```

2. OCR 模型（PP-OCRv4 det/rec/cls ONNX）与 `.litertlm` 放同一目录，应用启动时自动扫描。

3. Android Studio 直接运行，或命令行：

   ```bash
   gradlew.bat :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

## 质量闸门

改动提取口径（prompt / 约束 / 契约）后必须跑 30 张标注集回归：

```bash
./gradlew :cli:run --args="--gate <标注csv绝对路径> --images <图片目录绝对路径> \
  --model <模型绝对路径> --transport litert --litert-backend cpu --out <报告.md>"
```

闸门是行为契约的裁判：历史上一次 prompt「瘦身」优化即在此被拦下（多金额截图回归）。

## 路线图

- [x] 端到端提取管线、质量闸门、确认流、导入队列（票 01-07、12-14）
- [ ] 类别体系（票 08）
- [ ] 账目视图与月度汇总（票 09）
- [ ] 模型管理器（票 10）、备份导出（票 11）
- [ ] INT4 提速（等上游 LiteRT-LM 修复约束解码兼容性，见耗时文档「INT4 终审」）

## 隐私

所有处理（OCR、大模型推理、存储）均在设备本地完成，无网络权限依赖，截图与账目不上传任何服务器。

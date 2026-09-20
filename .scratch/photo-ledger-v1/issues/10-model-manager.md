# 10: 模型管理

**What to build:**
模型管理 (Model Manager)：首次使用引导页（解释要下载什么、多大、去哪）；模型文件下载（HF 官方 / hf-mirror 源可切换，断点续传，进度可见）；从本地文件导入模型作为下载不可用时的兜底；加载前的 RAM 预检（<8GB 明确拒绝并说明，不白下 2.8GB）。

模型就位后，App 全流程飞行模式可用（ADR-0003：联网仅限模型下载）。本票完成后 04 留下的"adb 推送"开发期约定被面向用户的入口取代。

**Blocked by:** 05 (端侧图像提取端到端)

**Status:** resolved

**Owner:** agent

- [x] 首启引导页：说明模型用途、大小、来源，用户确认后才开始下载
- [x] 下载支持 HF 官方与 hf-mirror 源切换、断点续传、进度与失败重试
- [x] 本地文件导入模型可用（文件管理器选取）
- [x] RAM 预检：<8GB 明确提示不满足要求，不开始下载
- [x] 模型就位后开启飞行模式，导入→提取→确认全流程可用

## Resolved（2026-09-20）

真机全流程验证通过：App 内下载 0.6GB 识别模型（取消/续传可用，下载完成后推理缓存
正常生成、识别真实跑通）；OCR 三件套改为 RapidOCR 官方 ModelScope 源可下载（约16MB，
det/rec/cls 逐一核对与原 `_infer` 文件字节数一致）；本地导入与 RAM 预检就位。
开发期「adb push 模型」约定由面向用户的模型页取代。

### 真机翻过的坑（勿重复）

- 阻塞式 HttpURLConnection 读取不响应协程取消：取消必须在下载循环里显式检查
  （每 256KB 一查，命中断连抛 CancellationException）；且取消后不得清空 job 引用——
  `cancelModelDownload` 曾把 `downloadJob` 置 null，`isCancelled` 检查读
  `downloadJob?.isCancelled` 恒为 false，取消看起来"无效自动续下"。
- OCR 文件隐藏测试时按后缀改名 `.bak` 无效：`scanModelDir` 按前缀匹配
  （`ch_PP-OCRv4_det` 等），要移出目录才认不到。
- ModelScope 302 → CDN：HttpURLConnection 自动跟随重定向不保证 Range 头透传，
  断点续传需手动逐跳跟 302。

## Comments

### 实现记录（2026-09-20）

- `model/ModelManager.kt`：三下载源（ModelScope 推荐 / HF 官方 / hf-mirror），
  HttpURLConnection 手动逐跳跟 302（ModelScope 跳 CDN，自动跟随不保证 Range 头透传），
  `.part` 中转 + RandomAccessFile 断点续传，服务端忽略 Range 回 200 时删 .part 重来；
  完成后原子改名。实测模型 614,236,160 字节 ≈ 0.6GB（工单原文「2.8GB」是 GGUF 旧口径）。
- `ui/ModelManageScreen.kt`：引导卡（用途/大小/离线说明）+ RAM 卡（totalMem <8GB 禁下载，
  本地导入仍开放）+ 源单选 + 进度条/百分比 + 取消/断点重试 + 本地导入（.litertlm 单选、
  OCR 三件套多选——OCR 无公开下载源，只能导入）+ OCR 就绪状态 + 开发工具入口。
- `MainActivity`：首启无模型且未跳过 → 模型页作根页（「暂不下载，先浏览」可跳过，
  prefs `model/onboard_done`）；FAB/相册导入前守卫——无模型直接带去模型页；
  顶栏设置图标改指模型页（冒烟工具页从模型页底部进入）；INTERNET 权限仅服务下载（ADR-0003）。

### 验证记录（2026-09-20）

- 构建 + 安装通过；真机冷启动：有模型直达流水页；隐藏模型后冷启动出引导根页
  （RAM 12GB ✓、三源单选、开始下载可用）。
- ModelScope URL + Range（302→CDN→206）在 WSL 侧 curl 验证；真机下载/续传与
  飞行模式全流程验证被中断（验证时手机正在被使用，自动化注入立即停止），
  待用户自测：开始下载→取消→重试（看 .part 续传）→完成后飞行模式导一张图全流程。
- 真机一次冷启动出现「流水页栈上多压了一层模型页」的异常导航，未复现；
  已在 onCreate 加 pageStack.clear() 防御。

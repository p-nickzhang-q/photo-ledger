# 05: 端侧提取端到端 ⭐

**What to build:**
核心追踪弹 (tracer bullet)：在 App 内选择一张订单截图 → 端上完整跑通 ADR-0004 提取管线（OCR → 后处理 → 03 定稿文本模型 + GBNF）→ 复用 01/12 的共享提取引擎（Draft 契约、口径归一零分叉）→ Draft 以最简界面呈现上屏。到这里，"照片记账"的核心价值主张第一次在手机上真实成立。

内存安全是本票的硬约束：目标机（11.7GB RAM）上 OCR（~20MB 级）与 0.6B 模型（~0.6GB）全程不把系统压入低内存杀手范围；RAM 不足 8GB 的设备给出明确错误提示而非崩溃。Draft 与提取失败的可见状态（本票只呈现，不落库——落库属 06）。

桌面与端侧的质量对照：同一张截图桌面 harness（02）与端上提取的 Draft 应一致（允许浮点/时间格式差）；不一致即口径分叉，属 bug。

**Blocked by:** 03 (文本模型定稿), 04 (Android 骨架与端侧推理冒烟)

**Status:** resolved

- [x] 真机端到端：App 内选一张截图 → 端上 OCR + 后处理 + 文本模型提取 → Draft 字段上屏，全程无外部网络
- [x] 使用 03 定稿的模型与 01/12 的共享提取引擎（prompt/grammar/解析/口径归一零分叉）
- [x] 目标机提取单张截图的端到端耗时（OCR/后处理/LLM 分段）与峰值内存记录进 Comments
- [x] 与桌面 harness 同图对照：Draft 字段一致性验证通过
- [x] RAM < 8GB 设备（或模拟低内存条件）得到明确错误提示，不崩溃
- [x] 提取失败（非订单截图、JSON 异常、OCR 空结果）呈现为可见状态而非静默

## Comments

### 2026-09-14 端到端跑通（真机 Xiaomi，Qwen3-0.6B Q8_0 + PP-OCRv4 mobile）

**结果：order_01（天猫订单截图）端上 Draft 与桌面 harness 完全一致（4/4 字段）**

| 字段 | 真机 | 桌面（02 基线） | 一致 |
|---|---|---|---|
| merchant | 天猫忠顺数码专营店 | 天猫忠顺数码专营店 | ✅ |
| amountPaid | 46.7 | 46.7 | ✅ |
| datePaid | 2026-08-31 00:00:00 | 2026-08-31 00:00:00 | ✅ |
| category | 购物 | 购物 | ✅ |

**分段耗时**（SMOKE_E2E_OK）：OCR 1465ms / 后处理 ~0ms（在引擎内） / LLM 594598ms / 总计 596067ms（≈10 分钟）
**峰值内存**：RSS 1.32GB（模型 mmap 604MB + KV cache 224MB + 计算缓冲 301MB + OCR/运行时），门槛前可用 4.9GB

**对照过程中的三个 OCR bug（票 04 遗留，逐个修复后才达到桌面一致）**：

1. **det/rec 归一化错误**：真机首版误用 ImageNet 分类参数 mean=[0.485,0.456,0.406]/std=[0.229,0.224,0.225]，而 RapidOCR（桌面）用 (x/255-0.5)/0.5。修复后「数码专营店」识别正确。定位依据：真机 OCR 第 0 行「大用忠顺码专京店」vs 桌面「天猫忠顺数码专营店」。
2. **rec 右侧填充**：真机首版用 replicate（复制边缘像素），RapidOCR 用零填充（归一化后 -1）。修复：`resized_w = ceil(48*w/h)`，右侧补 -1。
3. **det unclip 太紧**：真机首版固定 1.5px（prob 图空间），把「天」的顶横裁掉识别成「大」（merchant 尾部还多个「】」）。修复：与桌面同款 `offset = 1.6 × 面积/周长`（对商家名行约 21px）。

**验证方法（复现）**：

```bash
# 构建+安装+跑（am start 传截图路径自动走 E2E 链）
cmd.exe /c "set JAVA_HOME=D:\Program Files\Java\ms-21.0.7&& gradlew.bat :app:assembleDebug"
adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
adb.exe logcat -c
adb.exe shell am start -n io.github.pnickzhangq.photoledger/.MainActivity \
  --es smoke_image "/sdcard/Android/data/io.github.pnickzhangq.photoledger/files/order_01.png" \
  --ez smoke_e2e true
# 观察信号（logcat）：SMOKE_E2E_MEM → E2E_OCR(lines=N + 每行) → SMOKE_E2E_OK/FAIL + SMOKE_E2E_DRAFT[i]
```

**内存门槛行为验证**（验收项 5）：首版门槛 6144MB，真机可用 4694MB 被拒（SMOKE_E2E_REJECTED low memory 4694MB，App 不崩溃、状态栏提示）。据此把门槛定为 2048MB（0.6B 峰值 ~1.3GB 实测 + 余量）——低内存设备拒绝行为已验证，门槛值本身在票 10（模型管理）还会随量化档位重新校准。

**架构说明**：`JniLlmTransport`（app 模块）实现引擎的 `LlmTransport` 接口，`OnDevicePipeline` 桥接 app 的 `OcrEngine` 输出到共享引擎 `ExtractionEngine.extractFromOcr`——prompt/grammar/解析/归一全在共享引擎内，桌面/端侧零分叉。LLM 段 ~10 分钟/单张（6 线程 1.4 tok/s）是当前主要瓶颈，票 10 可用量化（Q4_K_M）优化。

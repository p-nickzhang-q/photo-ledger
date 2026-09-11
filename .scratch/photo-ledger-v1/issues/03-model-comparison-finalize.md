# 03: 文本模型定稿（0.6B 验证）

**What to build:**
原票为「Qwen3-VL-4B vs MiniCPM-V 4.5」双 VLM 对比；ADR-0004 切换路线 + 票 01 的 0.6B/1.7B 对比实验已提前回答了主要问题（0.6B 速度占优、1.7B 无成比例质量收益），本票缩减为**文本模型定稿验证**：

用 02 的 harness 以同一数据集验证 Qwen3-0.6B Q8_0 定稿（若 02 的闸门报告已含 0.6B 结果，本票只需：① 复核报告与耗时/内存数据；② 用 1.7B（或另一个 0.5B-2B 档候选）做一次对照跑分，确认没有明显更优的替代；③ 定稿并更新 ADR-0002/0004 的模型选型段）。

定稿范围含 OCR 模型档位：PP-OCRv5 mobile（检测+识别）为默认，若闸门数据显示 OCR 是主要错误源，评估更高精度档（如 server 版）在端侧的可行性作为记录。

**Blocked by:** 02 (质量闸门评分与数据集)

**Status:** resolved

- [x] 0.6B 在同一数据集、同一 harness 下的闸门报告复核（含单张耗时与内存数据）
- [x] 至少一个对照候选（1.7B 或其他 0.5B-2B 文本模型）的同 harness 跑分
- [x] v1 文本模型与 OCR 档位定稿，ADR-0002/0004 更新（含对比数据摘要）；备选记录进 Comments 备查

## Comments

### 2026-09-11 · 定稿结论（agent）

**v1 文本模型定稿：Qwen3-0.6B Q8_0；OCR 档位定稿：PP-OCRv5 系 mobile 档。**

同数据集（30 张）、同 harness 对照跑分：

| 指标 | Qwen3-0.6B Q8_0 | Qwen3-1.7B Q8_0 |
|---|---|---|
| 实付款（硬） | **100%** | 96.7%（order_22：标注 22.8 → 提取 8.06） |
| 日期（硬） | 100% | 100% |
| 全量耗时（OCR+提取+评分） | **5m0s** | 17m0s（3.4×） |
| 结论 | **定稿** | 对照记录，备查 |

- 1.7B 无质量收益且更慢，0.6B 定稿依据充分。
- OCR 档位：闸门数据 OCR 环节无一失败（30/30），mobile 档够用；server 版不评估。
- ADR-0002 已改写（运行时 llama.cpp 不变，模型选型段更新 + 失效历史保留）；ADR-0004 补充定稿结果与 PASS 结论。
- 复现：`gradle :cli:run --args="--gate .scratch/photo-ledger-v1/fixtures/orders.csv --model models/Qwen3-1.7B-Q8_0.gguf --out build/gate-report-17b.md"`（对照报告在 build/gate-report-17b.md，0.6B 在 build/gate-report.md）。

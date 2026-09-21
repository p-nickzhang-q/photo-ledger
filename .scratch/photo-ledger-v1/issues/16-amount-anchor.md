# 票 16：金额锚定兜底——极简支付页 51.6 被提取成 20

- Owner: QoderCN
- Created: 2026-09-21
- Resolved: 2026-09-21

## 现象

真机导入一张极简支付成功页截图（格瑞思，¥51.60），Draft 金额识别为 20。

## 诊断（logcat 现场）

```
E2E_OCR_LINE[0] [0.91] 20:34 8
E2E_OCR_LINE[1] [1.00] 支付成功
E2E_OCR_LINE[2] [0.99] 格瑞思
E2E_OCR_LINE[3] [0.93] ¥51.60
E2E_OCR_LINE[4] [1.00] 完成
SMOKE_LITERT_OK out={"amountPaid": 20.0, ...}
```

OCR 完全正确；0.6B 输出 20.0——从「20:34 8」时间行抄走的。

## 根因（两层叠加）

1. `OcrPostProcessor.TIME_ONLY` 只拦整行纯时间，「20:34 8」尾粘状态栏碎片拦不住，
   `normalizeAmount` 把 20 抽进了给模型的金额参考清单。
2. `amountPaid` 是自由数字段（日期已锚定进文法，金额没有）：该页无「实付款」字样，
   0.6B 就近抄了清单里的 20，唯一的「¥51.60」被弃。

## 修复

- `OcrPostProcessor`：新增 `TIME_PREFIX`（`^\d{1,2}:\d{2}\D`）时间开头粘连行不进金额候选；
  `StructuredMaterial` 新增 `currencyAmounts`（¥/￥ 符号行金额，最强信号）
- `OcrPostProcessor.anchorAmount`：区块内恰有一个货币金额、模型输出不等于它且无任何
  候选金额背书时，确定性替换（对齐日期锚定收口思路：候选收窄，模型无从选错）。
  「实付款 20」无符号纯数字行有背书不干预；多货币金额（原价/实付并存）不干预。

## 验证

- `:engine:test` 全绿（新增 5 项：时间行过滤、货币候选、锚定×3、引擎事故重放）
- 30 图闸门（Qwen3-0.6B.litertlm CPU）：实付款/日期 100%，PASS，
  报告 `gate-report-amount-anchor.md`
- versionCode 9 / versionName 0.4.1 发布装机

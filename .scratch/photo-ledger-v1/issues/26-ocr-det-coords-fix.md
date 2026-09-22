# 票 26：端侧 OCR det 坐标还原修复（真机 33.03 误读 55）

- Owner: agent（QoderCN）
- Created: 2026-09-22
- Status: In Progress

## 背景 / 动机

用户真机导入 CoCo 订单卡（fixtures/order_09 同图，真值 33.03），提取为 55。
真机日志：OCR 行 `实付款￥55:03`（置信度 0.84）——OCR 层就认错，模型忠实提取错字。

诊断路径（--ocrdebug 工具，票 26 新增）：
1. App 的 OcrEngine 是纯 JVM（仅依赖 onnxruntime Java API），复制进 cli +
   桌面 onnxruntime，同一张 order_09.png **逐行逐分复现**真机输出 → 排除真机
   环境差异，锁定移植实现本身
2. 与 RapidOCR 子进程（桌面闸门在用的 OCR）逐行对比框坐标：`08.23` 行
   RapidOCR 框 y=494-530，我们的框 y=514-553——**y 轴下移且误差随 y 递增**
   （行 0 差 3px、行 12 差 23px）→ 各向异性拉伸特征

## 根因

`OcrEngine.detectBoxes/dbPostprocess` 两处耦合 bug：

1. det 输入采样把整图按 pad 后的 (rw32, rh32) 全幅拉伸采样，而内容应只占
   (rw, rh)（未 pad）；y 方向被多压缩 ~4%
2. 概率图坐标还原用单一 `scale = max(origW/pw, origH/ph)`；x/y 两轴 pad 差值
   不同，y 轴被累计拉伸——裁剪框随 y 下移、越往下错得越多

后果：裁剪框错位（裁到字的下半截 + 带下方空白）→ rec 全线劣化
（33.03→55:03、08.23→00:23、长行合并读错）。桌面闸门走 RapidOCR 无此 bug，
所以 30 张闸门一直 100% 而真机偶发误读。

## 修复

- 采样：内容按未 pad 的 (rw, rh) 均匀缩放，pad 区补黑（归一化 -1，对齐
  RapidOCR 零填充）
- 坐标还原按轴分离：`scaleX = origW/rw`、`scaleY = origH/rh`（grid→orig）

验证（--ocrdebug，order_09）：
- 修复前：`[0.84] 共3件(含包表/配送费）实付款￥55:03`、`[0.79] 00:23`
- 修复后：`[0.97] 共3件（含包装/配送费）实付款￥33.03`、`[1.00] 08.23`，
  框坐标与 RapidOCR 对齐（y 490-533 vs 490-530）
- 2x 放大预处理试验：仅 55:03→35:03，不解决问题，未采用

## 附加产出

- cli `--ocrdebug <png...> [--scale N]`：App 同款 OcrEngine 桌面复现工具
  （OcrEngineJvm.kt 为 app/ocr/OcrEngine.kt 的同步副本——**改一处必须同步另一处**）
- gate `--ocr-jvm` 开关：闸门用 App 同款 OCR 替换 RapidOCR（端侧 OCR 回归验证）

## 验收

- 30 张闸门（--ocr-jvm，App 同款 OCR + litert cpu）：硬字段 ≥95% PASS
- 真机重导 order_09：金额 33.03 正确（用户复测）
- 桌面闸门（RapidOCR 路线）不受影响（未改动其路径）

## Resolved

- 结论（2026-09-22）：修复落地并验证。OcrEngine.detectBoxes 两处 bug——
  ① det 输入按 pad 后 (rw32,rh32) 全幅各向异性拉伸采样（内容应只占 (rw,rh)）；
  ② 概率图坐标还原用单一 max scale，y 轴被 pad 差值累计拉伸（误差随 y 递增，
  行 0 差 3px、行 12 差 23px），裁剪框下移裁掉字的上半截 → rec 全线劣化。
  修复为：内容按未 pad 尺寸均匀采样 + pad 区补黑（对齐 RapidOCR 零填充）、
  坐标按轴分离还原（scaleX=origW/rw、scaleY=origH/rh）。
- 桌面复现证据（--ocrdebug）：order_09 修复前 `[0.84] 实付款￥55:03`、
  `[0.79] 00:23`；修复后 `[0.97] 实付款￥33.03`、`[1.00] 08.23`，
  框坐标与 RapidOCR 对齐。2x 放大预处理试验无效（仅 55:03→35:03），未采用。
- 闸门（--ocr-jvm，App 同款 OCR + litert cpu，gate-report-ocrjvm.md）：
  实付款 96.7%（29/30）、日期 100%、商家/类别 100% PASS；
  order_09 33.03 ✓；唯一 ✗ 为已知 order_12（14.5→14.0）。零回归。
- 真机复测（vivo V2183A，versionCode 24）：用户重导 CoCo 卡，金额 33.03
  显示正确。
- 附带：cli 新增 OcrEngineJvm.kt（app/ocr/OcrEngine.kt 同步副本，**改一处必须
  同步另一处**）、gate --ocr-jvm 开关。桌面 RapidOCR 闸门路径未动。
- 遗留观察：修复后行级 diff 显示 det 拆分粒度与 RapidOCR 仍有差异（合并长行
  vs RapidOCR 拆两行），但 rec 均读对，不影响字段正确率；部分低分噪声行
  （RR/卧 等）由既有 min-score 过滤兜底。

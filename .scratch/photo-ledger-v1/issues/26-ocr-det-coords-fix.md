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

（待收口）

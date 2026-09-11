# OCR 子进程协议（票 12，供票 04 复用）

桌面端 OCR（RapidOCR/ONNX Runtime，Python）以独立进程运行，与 JVM 提取管线通过
**文件进出** 的 JSON 协议通信（stdin/stdout 同构，文件便于调试与缓存）。端侧（票 04）
换 ONNX Runtime Mobile 进程内实现时，复用同一 JSON 语义。

## 输入（argv 或 stdin JSON）

```json
{ "images": ["abs/path/a.jpg", "abs/path/b.png"] }
```

## 输出（stdout 单个 JSON 文档 / 或每图一个 `<name>.ocr.json` 文件）

```json
{
  "version": 1,
  "results": [
    {
      "image": "abs/path/a.jpg",
      "ok": true,
      "lines": [
        {
          "text": "实付款￥13.6",
          "score": 0.98,
          "box": [[x1,y1],[x2,y2],[x3,y3],[x4,y4]]
        }
      ]
    },
    {
      "image": "abs/path/bad.png",
      "ok": false,
      "error": "OcrEngineError: model load failed"
    }
  ]
}
```

## 语义约定

- `box`：四点顺时针多边形（RapidOCR 原生格式），坐标为原图像素。后处理层用它做行聚类
  （同一订单卡片的行按 y 区间分组）与阅读顺序（先 y 后 x）。
- `score`：识别置信度 [0,1]，< 0.5 的行后处理层仍保留，但结构化 prompt 中标注「低置信」。
- `ok: false`：OCR 层故障（模型缺失、图片解码失败）。**图片里没有文字不算故障**——
  返回 `ok: true, lines: []`，由上层决定呈现「可能不是订单截图」。
- 行序：按 OCR 引擎返回顺序（近似阅读顺序）；后处理层不得依赖严格排序，须按 box 重排。
- `version`：协议版本，非 1 即拒绝——端侧与桌面版本漂移要显性失败，不要静默错位。

## 错误语义

| 场景 | 输出 |
|---|---|
| 图片无法解码 | 该 result `ok:false, error:"ImageDecodeError"`，其他图继续 |
| OCR 引擎崩溃 | 进程非零退出 + stderr 诊断；JVM 侧对该图标记 OcrEngineError |
| 空白图片 | `ok:true, lines:[]` |

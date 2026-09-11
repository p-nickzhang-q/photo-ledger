#!/usr/bin/env python3
"""OCR 子进程 worker（协议见 docs/design/ocr-protocol.md）。

用法: ocr_worker.py <input.json> <output.json>
input.json:  {"images": ["abs/path/a.jpg", ...]}
output.json: {"version": 1, "results": [{"image": ..., "ok": true, "lines": [...]}, ...]}

依赖: pip install rapidocr-onnxruntime（桌面；端侧由 ONNX Runtime Mobile 进程内实现替换，
JSON 语义不变）。
"""
import json
import sys
import traceback


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: ocr_worker.py <input.json> <output.json>", file=sys.stderr)
        return 2

    with open(sys.argv[1]) as fp:
        req = json.load(fp)
    images = req.get("images", [])

    results = []
    engine = None
    for path in images:
        try:
            if engine is None:
                from rapidocr_onnxruntime import RapidOCR
                engine = RapidOCR()
            lines, _ = engine(path)
            results.append({
                "image": path,
                "ok": True,
                "lines": [
                    {
                        "text": txt,
                        "score": round(float(sc), 4),
                        "box": [[round(float(x), 1) for x in pt] for pt in (box or [])],
                    }
                    for box, txt, sc in (lines or [])
                ],
            })
        except Exception as e:  # 单图失败不阻塞其余（协议约定）
            results.append({
                "image": path,
                "ok": False,
                "error": f"{type(e).__name__}: {e}",
                "trace": traceback.format_exc(limit=3),
            })

    with open(sys.argv[2], "w") as fp:
        json.dump({"version": 1, "results": results}, fp, ensure_ascii=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())

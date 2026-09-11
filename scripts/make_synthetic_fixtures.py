#!/usr/bin/env python3
"""渲染仿中文电商/支付订单页截图，作为链路冒烟测试素材。

真实截图不入库（.gitignore，含个人消费数据）；本脚本可再生成同构合成图。
用法: python3 scripts/make_synthetic_fixtures.py [输出目录]
依赖: pip install pillow（需系统有中文字体，如 wqy-microhei）
"""
from PIL import Image, ImageDraw, ImageFont
import os, sys

OUT = sys.argv[1] if len(sys.argv) > 1 else ".scratch/photo-ledger-v1/fixtures"
os.makedirs(OUT, exist_ok=True)

font_paths = [
    "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
    "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
]
FONT = next((p for p in font_paths if os.path.exists(p)), None)
if FONT is None:
    import subprocess
    r = subprocess.run(["fc-list", ":lang=zh", "file"], capture_output=True, text=True)
    for line in r.stdout.splitlines():
        f = line.strip().rstrip(":")
        if f:
            FONT = f
            break
print("font:", FONT)


def font(sz):
    return ImageFont.truetype(FONT, sz)


def page(title, rows, status, pay_line, order_line, bgcolor, accent, fname, extra_note=None):
    W, H = 720, 1280
    img = Image.new("RGB", (W, H), bgcolor)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, 110], fill=accent)
    d.text((W // 2, 55), title, font=font(42), fill="white", anchor="mm")
    d.rounded_rectangle([30, 150, W - 30, 640], radius=20, fill="white")
    y = 200
    for label, value in rows:
        d.text((70, y), label, font=font(30), fill=(120, 120, 120))
        d.text((W - 70, y), value, font=font(30), fill=(30, 30, 30), anchor="ra")
        y += 78
    d.text((70, y + 10), "订单状态", font=font(30), fill=(120, 120, 120))
    d.text((W - 70, y + 10), status, font=font(34), fill=(230, 90, 60), anchor="ra")
    d.rounded_rectangle([30, 700, W - 30, 880], radius=20, fill="white")
    d.text((70, 740), "实付款", font=font(34), fill=(60, 60, 60))
    d.text((W - 70, 760), pay_line, font=font(58), fill=(220, 60, 40), anchor="ra")
    d.rounded_rectangle([30, 930, W - 30, 1180], radius=20, fill="white")
    d.text((70, 970), "下单时间", font=font(28), fill=(120, 120, 120))
    d.text((W - 70, 970), order_line, font=font(28), fill=(30, 30, 30), anchor="ra")
    if extra_note:
        d.text((70, 1060), extra_note, font=font(28), fill=(60, 160, 60))
    img.save(os.path.join(OUT, fname))
    print("saved", fname)


page(
    "淘宝订单详情",
    [("商品", "无线蓝牙耳机 Pro"), ("商品总价", "￥129.00"), ("运费", "￥0.00"), ("优惠券", "-￥10.00")],
    "交易成功",
    "￥119.00",
    "2026-09-08 20:15:33",
    (245, 245, 245), (255, 90, 50), "taobao-earphones.png",
    extra_note="付款时间：2026-09-08 20:15:40",
)

page(
    "美团外卖",
    [("商家", "老王川菜馆"), ("套餐", "鱼香肉丝饭 x1"), ("配送费", "￥3.5"), ("红包", "-￥2.0")],
    "已完成",
    "￥23.8",
    "2026-09-09 11:42:10",
    (255, 248, 240), (255, 200, 60), "meituan-lunch.png",
)

page(
    "微信支付凭证",
    [("收款方", "三江购物"), ("支付方式", "零钱"), ("商品", "伊甸园鲜牛奶 1L*2")],
    "已支付",
    "￥25.60",
    "2026-09-10 08:02:55",
    (240, 245, 240), (7, 193, 96), "wechat-milk.png",
    extra_note="支付时间：2026-09-10 08:03:02",
)

print("done")

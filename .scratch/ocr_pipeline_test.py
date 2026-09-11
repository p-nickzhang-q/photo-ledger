"""OCR 管线端到端冒烟：真实截图 → RapidOCR 文本行 → llama-server(Qwen3-0.6B) + engine 生成的 GBNF → Draft JSON。

用法：
  python ocr_pipeline_test.py <截图> [--server http://127.0.0.1:8902] [--grammar /tmp/extract-grammar.gbnf]

前提：engine 已 dump grammar 文件（gradle :cli:run --args="--dump-grammar <路径>"）；
llama-server 已加载纯文本模型（Qwen3-0.6B，无 mmproj）。
"""
import sys, json, time, urllib.request

sys.path.insert(0, "/home/nickzhang/.ocr-venv/lib/python3.12/site-packages")
from rapidocr_onnxruntime import RapidOCR

args = sys.argv[1:]
FIXTURE = args[0]
BASE = args[args.index("--server") + 1] if "--server" in args else "http://127.0.0.1:8902"
GRAMMAR_PATH = args[args.index("--grammar") + 1] if "--grammar" in args else "/tmp/extract-grammar.gbnf"
GRAMMAR = open(GRAMMAR_PATH).read()

CATEGORIES = ["餐饮", "购物", "交通", "居住", "医疗", "娱乐", "通讯", "其他"]

# 1) OCR
t0 = time.time()
ocr = RapidOCR()
lines, _ = ocr(FIXTURE)
ocr_dt = time.time() - t0
texts = [(txt, sc) for _box, txt, sc in (lines or [])]
print(f"[ocr] {len(texts)} 行, {ocr_dt:.2f}s")
ocr_text = "\n".join(txt for txt, _ in texts)

# 2) prompt：OCR 文本行 → Draft 契约（与 PromptBuilder 同一口径要求）
prompt = (
    "以下是从一张订单截图 OCR 识别出的文本行（自上而下、自左而右，可能有粘连、错字或无关内容）：\n"
    + ocr_text
    + "\n\n请提取为 JSON：merchant(商家或平台名)、amountPaid(实付款金额：取「实付款/实付」后紧跟的数字，不要商品单价)、"
    "currency(固定 CNY)、datePaid(付款时间；无付款时间时取下单时间)、dateSource(payment_time 或 order_time)、"
    "orderStatus(订单状态原文，如 商家备餐中/已完成/交易成功)、category(必须从："
    + "、".join(CATEGORIES) + " 中选一个)。\n只输出 JSON。"
)

# 3) llama-server + grammar
body = {
    "messages": [{"role": "user", "content": prompt}],
    "temperature": 0.1,
    "max_tokens": 256,
    "grammar": GRAMMAR,
    "cache_prompt": False,
}
t1 = time.time()
req = urllib.request.Request(
    BASE + "/v1/chat/completions",
    data=json.dumps(body).encode(),
    headers={"Content-Type": "application/json"},
)
with urllib.request.urlopen(req, timeout=300) as r:
    resp = json.load(r)
llm_dt = time.time() - t1
content = resp["choices"][0]["message"]["content"]
usage = resp.get("usage", {})
print(f"[llm] prefill {usage.get('prompt_tokens')} tok + gen {usage.get('completion_tokens')} tok, {llm_dt:.2f}s")
print("[draft]", content)
parsed = json.loads(content)  # GBNF 保证合法；显式 parse 复核
print("[parse] OK ->", parsed)

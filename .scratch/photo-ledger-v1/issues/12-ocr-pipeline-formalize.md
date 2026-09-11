# 12: OCR 提取管线正式化（桌面）

**What to build:**
把 01 票内的 Python 原型（`.scratch/ocr_pipeline_test.py`：RapidOCR → prompt → llama-server + GBNF）升级为正式的桌面提取管线，并定义 OCR ↔ 引擎的稳定接缝。质量闸门（02）跑的就是这条管线，端侧（04/05）复用其分层与逻辑。

分层与接缝：

1. **OCR 层（进程边界）**：RapidOCR（ONNX Runtime）以子进程/独立可执行方式封装（Python 侧），CLI 通过文件或 stdin 协议传入截图、取回「文本行 + 坐标 + 置信度」JSON。桌面用子进程即可，**接缝协议本身要按端侧可替换设计**（04 在 Android 上换 ONNX Runtime Mobile 进程内实现，协议语义不变）。
2. **OCR 后处理层（纯 Kotlin，进 engine 模块）**：行聚类（把「下单时间」「实付款」与其值行关联）、日期规范化（碎片拼接、年份补全、格式统一）、金额规范化（¥ 粘连、全半角、小数点校正）、多订单拆分策略。01 实验证实这是质量瓶颈所在，全部为确定性逻辑，TDD 覆盖（来自真实截图 OCR 输出的 fixture 用例）。
3. **结构化层**：既有 engine 模块（PromptBuilder 需新增「OCR 文本行版」prompt；GrammarGenerator/DraftNormalizer 零改动）。

交付一条命令：`./run-cli.sh <截图...>` 在 OCR 路线下端到端跑通（复用 01 的 llama-server transport）。

**Blocked by:** 01-resolved (桌面提取管线打通)

**Status:** ready-for-agent

- [ ] OCR 子进程协议定型（输入/输出 JSON schema、错误语义），记录进 Comments 供 04 复用
- [ ] OCR 后处理层为 engine 模块纯 Kotlin 代码，TDD 覆盖真实 OCR 输出用例（日期碎片、金额粘连、多单各至少 2 例）
- [ ] OCR 版 prompt 进 PromptBuilder（与图像版并存），口径要求不变
- [ ] `./run-cli.sh` 端到端跑通 8 张既有真实截图，0 malformed（回归 01 验收水位）
- [ ] 后处理前后对比数据（修复的日期/金额畸形计数）记录进 Comments，作为 02 的基线

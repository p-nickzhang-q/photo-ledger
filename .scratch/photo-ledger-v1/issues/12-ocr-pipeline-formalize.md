# 12: OCR 提取管线正式化（桌面）

**What to build:**
把 01 票内的 Python 原型（`.scratch/ocr_pipeline_test.py`：RapidOCR → prompt → llama-server + GBNF）升级为正式的桌面提取管线，并定义 OCR ↔ 引擎的稳定接缝。质量闸门（02）跑的就是这条管线，端侧（04/05）复用其分层与逻辑。

分层与接缝：

1. **OCR 层（进程边界）**：RapidOCR（ONNX Runtime）以子进程/独立可执行方式封装（Python 侧），CLI 通过文件或 stdin 协议传入截图、取回「文本行 + 坐标 + 置信度」JSON。桌面用子进程即可，**接缝协议本身要按端侧可替换设计**（04 在 Android 上换 ONNX Runtime Mobile 进程内实现，协议语义不变）。
2. **OCR 后处理层（纯 Kotlin，进 engine 模块）**：行聚类（把「下单时间」「实付款」与其值行关联）、日期规范化（碎片拼接、年份补全、格式统一）、金额规范化（¥ 粘连、全半角、小数点校正）、多订单拆分策略。01 实验证实这是质量瓶颈所在，全部为确定性逻辑，TDD 覆盖（来自真实截图 OCR 输出的 fixture 用例）。
3. **结构化层**：既有 engine 模块（PromptBuilder 需新增「OCR 文本行版」prompt；GrammarGenerator/DraftNormalizer 零改动）。

交付一条命令：`./run-cli.sh <截图...>` 在 OCR 路线下端到端跑通（复用 01 的 llama-server transport）。

**Blocked by:** 01-resolved (桌面提取管线打通)

**Status:** resolved

- [x] OCR 子进程协议定型（输入/输出 JSON schema、错误语义），记录进 Comments 供 04 复用
- [x] OCR 后处理层为 engine 模块纯 Kotlin 代码，TDD 覆盖真实 OCR 输出用例（日期碎片、金额粘连、多单各至少 2 例）
- [x] OCR 版 prompt 进 PromptBuilder（与图像版并存），口径要求不变
- [x] `./run-cli.sh` 端到端跑通 8 张既有真实截图，0 malformed（回归 01 验收水位）
- [x] 后处理前后对比数据（修复的日期/金额畸形计数）记录进 Comments，作为 02 的基线

## Comments

### 2026-09-11 · 实施结论（agent）

#### 1. OCR 子进程协议（已定型，`docs/design/ocr-protocol.md`）

- 输入：`{"images": [abs paths]}`；输出 `{"version":1,"results":[{image, ok, lines:[{text, score, box}]}, ...]}`。
- 语义要点：box 为四点顺时针多边形（行聚类依据）；空白图 `ok:true, lines:[]`（不是故障）；引擎崩溃非零退出；协议版本不匹配显性失败。
- 实现：`scripts/ocr_worker.py`（RapidOCR）+ `engine/OcrClient.kt`（`parseResponse` 语义端侧复用——04 换 ONNX Runtime Mobile 进程内实现时喂同一 JSON）。

#### 2. 后处理层（engine 模块 `OcrPostProcessor`，纯 Kotlin，16 测试）

- 日期规范化：完整日期/中文日期/缺年角标（`09.10`、`09.09丨共4件` 粘连形态）/缺年横杠，全部借 contextYear 补全；不可识别返回 null（不给模型喂畸形值）。
- 金额规范化：¥ 前缀/全角/千分位；**订单流水号剪枝**（>6 位整数直接拒绝——真实截图的 28 位流水号曾被当作金额）；4 位整数小数点丢失修复（1010→10.10）；合理性边界 0<x<100 万。
- 行聚类：按「实付款」关键词切分多订单区块，区块内按 y→x 阅读序。

#### 3. 日期从「生成」降为「选择」（本票最重要的设计决策）

0.6B 对 prompt 的「必须逐字取自清单」指令无视率高（实测拼出 0909-09-09/9070-09-07），prompt 治不了。改为 **GBNF 编码候选清单**：`GrammarGenerator.withDateAlternatives` 把 date 规则替换为清单字面量二选一 + 空串兜底。模型只能选不能拼。效果见 §4。
候选构造：OCR 行 normalizeDate → 上下文年份（OCR 文本中任意 20xx）→ CLI 传 fallbackYear（文件名年份 → 当前年）。

#### 4. 后处理前后对比（8 张真实截图，14 条 Draft，02 的基线）

| 指标 | 后处理前（01 原型） | 后处理后（本票） |
|---|---|---|
| 结构合法（0 malformed） | 8/8 图 | 8/8 图（14 条 Draft） |
| 一图多单 | 只取最后一单 | 拆出 14 条（2+3+3+1+2+1+1+1） |
| 畸形日期（0909-09-09 类） | 6 条 | **1 条**（唯一残留：无清单退化路径下的 0000-01-01，02 评分时该类计入日期错误） |
| 天文金额（流水号当金额） | 1 条（4.5e27） | **0 条** |
| 小数点丢失修复 | 0 | 1010→10.10 类修复生效 |

已知残留（02 处理）：① 退化路径（清单为空 + 无 fallbackYear）仍可能拼错日期；② 金额候选仍可能有干扰项（830.0 来源待 02 人工核对）；③ 「沪上阿姨·精选···苏州吴中光···>」类店名粘连 OCR 噪声。

#### 5. 过程记录

- llama-server 纯文本模型 + `--mmproj` 会导致 n_embd 不匹配崩溃——`LlamaServerProcess` 已改为 mmproj 空时不传参。
- 真实 OCR 快照（含个人消费数据）存放于 `.scratch/ocr-snapshots/`（gitignore）；仓库内测试样例为数据虚构的 `synthetic-duo-orders.json`。
- 全项目 48 测试 0 失败（engine 48：Normalizer 9 + Engine 3 + Grammar 9 + OcrClient 5 + PostProcessor 16 + Prompt 6）。

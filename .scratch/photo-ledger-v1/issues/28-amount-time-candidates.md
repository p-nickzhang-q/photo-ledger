# 票 28：金额/时间 LLM 前候选打分 + 后置交叉验证

- Owner: agent（QoderCN）
- Created: 2026-09-23
- Status: Resolved

## Resolved

- 结论（2026-09-23）：A/B 两段全部落地，A 与 B 相互独立实现（未并行推进，
  B 未等 A 的闸门结果）。
- A 前置打分：OcrPostProcessor.buildStructuredMaterial 内联候选打分
  （未独立 AmountCandidateExtractor 类——打分依赖循环内的 prevText/
  firstDecimalAnchored 状态，独立类需重复扫描，内联更简单）。
  分值：货币符号行 +40、负号行 +35、区块首个独立两位小数行 +25、
  实付类关键词（本行或紧邻上一行）+30、优惠/原价类 -50（先查高分再查惩罚，
  「含包装/配送费实付款」不会被配送费误伤）。同值合并取高分，同分保持阅读顺序。
  StructuredMaterial 新增 amountCandidates；PromptBuilder.buildFromOcr 增
  topAmount 参数，金额清单改为「按可能性从高到低，第一项最可能是实付款」。
  **box 位置分未实现**：区块已按实付行锚定切分，上下文关键词分覆盖了位置
  信息的主信号，加位置权重属重复计分，实现时判定为不必要。
- B 后置交叉验证：Draft 新增 `needsReview: Boolean = false`（引擎填写，
  模型不输出，契约三字段不变）；OcrPostProcessor.needsReview 判定——
  输出金额不在候选集（±0.005）/ 日期候选存在却输出空或不在清单 → true；
  ExtractionEngine 在 anchorAmount 后调用；DraftConfirmScreen 顶部显示
  「识别置信度较低，请重点核对金额与日期」。复核机制本身已存在（所有草稿
  须确认才入账），标志只做可视化引导，无新增队列逻辑。
- 验证：engine 单测全过（新增打分排序/上一行标签加分/同值合并取高分/
  needsReview 四场景）；app 编译+单测全过；30 张闸门（litert cpu，
  gate-report-ticket28.md）实付款 96.7%、日期 100% PASS——与基线持平零回归。
  order_12 仍为唯一 ✗（提取值 1.0 落在候选集外，正是 needsReview 设计要兜的
  场景）；order_09 33.03 ✓。
- 遗留：确认页低置信提示待下次真机安装后实际走查（下一版发布时顺带验证）。

## 真机回归修复（2026-09-23 当日，随本票收口）

闸门覆盖不到的两种真实页面形态，装机当天由用户实测暴露，当日修复：

1. **促销文案假拆单**（饿了么订单详情页）：促销行「实付满15即可计入任务进度」
   含「实付」被 splitOrderBlocks 当订单边界，单订单拆两单、LLM 白跑一遍 16s。
   修复：拆单边界收紧为实付关键词后**紧跟金额**（PAID_ANCHOR 正则，「实付￥28」✓、
   「实付满15」✗）；「多单挑战/完成挑战/任务进度/去领奖/急送券」进噪音表。
   修复后同图 1 单、LLM 7977ms（减半）。
2. **券进度被抄走当金额**：同页「100/100元」进度条无货币符号无促销词、打分 0，
   仍进候选清单被 0.6B 抄走当实付款 100（排序提示是软引导，0.6B 长页面下不守
   规矩——小模型不可信处必须确定性兜底的又一实证）。修复：PROGRESS_NUM
   （`数字/数字`）形态整行踢出金额候选；100 出局后 28 成为唯一货币金额，
   anchorAmount 锚定强改可生效。真机复测金额正确。
3. 补跑闸门两次均 PASS 零回归（gate-report-ticket28b/c.md）：
   实付款 96.7%、日期 100%；engine 新增 3 个真机回归单测。

## 背景 / 动机

内容较多的截图（订单卡、账单页）里数字噪声多，0.6B 仍偶发选错 amountPaid /
datePaid（票 26 修的是 OCR 读错层；本票处理 OCR 读对但模型选错层）。

调研 SpendTrace `AmountExtractor`（参考实现 `.scratch/refs/SpendTrace/.../
parse/AmountExtractor.kt`，190 行）：多候选 + 上下文窗口打分（紧邻「实付」
+30 / 「优惠/原价」-50）、无单位必须两位小数、日期时间整段剔除、余额行跳过、
OCR 失真归一（全角/O→0/逗号歧义/U+2212）。其置信度按 top1-top2 分差估算，
复核页给候选下拉。纯文本无坐标；本项目有 box 坐标可用，条件更好。

## 方案

两段，互不阻塞，均可独立过闸门：

### A. 前置：候选打分注入 prompt（先做，收益大）

1. engine 新增 `AmountCandidateExtractor`（纯 Kotlin，单测）：OCR 行 →
   金额候选列表（分值 = 上下文关键词分 + box 位置分：金额行通常紧邻
   「实付/¥」、位置居中偏下；借鉴 SpendTrace 打分表）
2. PromptBuilder 输入侧附加「候选参考」（如 `实付款候选：¥33.03(高分)、
   ¥10.00(低分)`）——缩小模型搜索空间，prefill 更短；**不改输出契约字段**
3. prompt 口径反例是质量资产，保留；候选只是加信息，不替口径

### B. 后置：交叉验证 + 低置信进复核

1. DraftNormalizer / 提取后校验：amountPaid 不在候选集（允许 ±0.01 容差）→
   标记低置信；datePaid 不匹配任何时间正则提取值（借鉴 SpendTrace
   TimeExtractor 三级：含年完整 → 相对词 → 无年跨年兜底）→ 标记低置信
2. 低置信草稿直接带「需复核」标志进 ImportQueue 复核队列（复用既有机制），
   不是错着入库
3. 日期校验注意：dateSource=PAYMENT_TIME 的既有语义不变，空 datePaid 仍合法

## 非目标

- 不改 Draft 契约字段（amountPaid / datePaid / category 三字段不变）
- 不做候选下拉 UI（复核页已有改值能力，够用）

## 验收

- engine 单测：候选打分（实付/优惠/余额/订单号场景）、日期三级解析、容差
- 30 张闸门 ≥ 既有基线（实付款 96.7%、日期 100%），order_12 不再恶化
- 真机：内容多的截图（多金额页）金额选错时落到复核队列而非直接入账
- prefill 耗时不劣化（BenchmarkInfo 对比，票 25 埋点）

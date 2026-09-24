# 票 29：规则快路径（确定性提取先行，LLM 降级兜底）

- Owner: agent（QoderCN）
- Created: 2026-09-23
- Status: Resolved

## Resolved

- 结论（2026-09-23）：落地并真机验证通过（vivo V2183A，versionCode 29，
  用户实测「没问题」）。逐块快路径 + LLM 降级混合模式为 App/闸门默认行为。
- 实现：engine `RulesFastPath.tryBuild`（suspend，逐块）——条件为「金额候选
  非空且（top1 强锚 ≥70 或唯一候选）且（多候选时分差 ≥20）」+「日期锚定候选
  ≤1」+「品牌字典（DEFAULT_MERCHANT_MEMORY 种子同源）命中类别」，三者全满足
  毫秒级出 Draft（needsReview 恒 false，金额/日期按构造必然过交叉验证）；
  任一不满足 → 该块降级 LLM。类别无品牌命中时降级而非输出「其他」——保持
  类别质量口径。merchantResolver 逐块钩子（票 27/30）复用。
- 接线：`extractFromOcr` 加 `fastPath: Boolean = false`（默认 false 保旧调用
  方语义）；OnDevicePipeline 与 GateCommand 均开 true——闸门验证的就是真实
  生产行为。
- 验收数据：30 张闸门（gate-report-ticket29.md）实付款 96.7%、日期 100%、
  商家/类别 100% PASS——与 LLM 全跑基线完全一致零回归；闸门总耗时
  7m07s → **2m44s**（含编译），绝大多数图被规则接住，快路径覆盖符合预期。
- 测试：engine 新增 4 用例（强锚跳过 LLM / 无品牌+双强锚降级 / 唯一货币金额
  极简页 / 多单各自匹配沿用票 30），FakeTransport 加 calls 计数断言 LLM 调用。
- 附加修复（随本票）：MerchantMatcher 模糊匹配对**含数字/字母别名禁用**——
  「711」曾把日期行「…09-2311:12」的「311」窗口模糊成单字误读，格瑞思订单
  被回填成 711（真机回归单测覆盖）。

## 背景 / 动机

票 27/28 之后管线的现实：金额有候选打分 + 锚定强改、日期被文法锁死在候选
清单、商户靠本地记忆匹配——核心字段几乎全部由确定性逻辑承责，30 张闸门里
模型输出与规则候选几乎重合（唯一 order_12 错误恰是模型犯的）。SpendTrace
（纯规则+字典）证明结构化支付页不需要模型。

0.6B 每次提取 8s+ 解码 + 耗电，且需防抄错（100/100元 案例）。而规则快路径
是毫秒级、零耗电、确定性的。

## 方案

1. **快路径判定**：OCR 完成后先跑确定性提取（复用 buildStructuredMaterial
   的候选/锚定 + MerchantMatcher + 类别关键字匹配）：
   - 区块内有唯一高分实付锚（货币符号行或实付标签行）且与候选集一致
   - 日期锚定唯一（preferred 非空且单一）或合法为空
   - 一图一单（splitOrderBlocks 单块）
   全部满足 → 直接出 Draft，**跳过 LLM**（预期能覆盖 80%+ 导入，毫秒级）
2. **类别规则**：引擎内置关键字字典（借鉴 SpendTrace CategoryDictionary，
   品牌词→类别；种子的 category 列可复用）——仅快路径使用，LLM 路径维持
   模型选择（两者口径以闸门为准）
3. **降级条件**（任一命中走 LLM）：无实付锚 / 多候选打架（top1-top2 分差
   小）/ 一图多单交错 / 用户设置强制 LLM
4. 快路径 Draft 的 needsReview 按同一交叉验证规则计算
5. 快路径与 LLM 路径输出同构（Draft），ImportQueue/复核 UI 无感知

## 非目标

- 不删除 LLM 路径（乱页面兜底价值仍在：转账页、无标签极简页、排版突变）
- 不动契约三耦合（Draft 字段、prompt、grammar 均不变）

## 验收

- engine 单测：快路径判定（唯一锚/多锚/无锚）、类别字典、降级条件
- 30 张闸门：快路径+LLM 混合模式下硬字段不低于基线（96.7%/100%）
- 耗时对比：快路径单张（OCR+后处理）应 <1s（不含 OCR 约 2.5s 的话，端到端
  预期从 ~10s 降到 ~3s）
- 真机：常规支付页秒出、促销长页/转账页正确降级到 LLM

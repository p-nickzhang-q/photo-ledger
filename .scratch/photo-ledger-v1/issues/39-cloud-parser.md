# 票 39：云端解析纯函数与 Draft.content

- Owner: agent（QoderCN）
- Created: 2026-11-18
- Status: Resolved

## Resolved

- 结论（2026-11-18）：`Draft` 新增 `content`（默认空串，端侧不产出）；engine 新增 `CloudEndpoint`、`CloudPromptBuilder`、`CloudDraftParser`，全部纯 Kotlin、可 WSL 单测。
- 证据：`./gradlew :engine:test` 全绿（CloudEndpointTest / CloudPromptBuilderTest / CloudDraftParserTest）。覆盖：markdown 围栏剥离、多单/单对象/裸数组、类别漂移落「其他」+ needsReview、金额范围拦截、中文日期归一、内容拆分去重限长、空 orders 报错、非 https 拒绝。
- 真机（OpenCode Go / deepseek-v4.1-flash）：合成订单截图一次解析成功——商家「美团外卖·望京店」、内容「冰美式×2、可颂×1」、金额 29.9、日期 2026-11-18、类别「餐饮」。
- 端侧契约未动：PromptBuilder / GrammarGenerator / DraftNormalizer 零改动，30 张闸门不涉及。

## 背景

云端视觉模型能力更强，一次可产出商家与商品明细；但各家 OpenAI 兼容实现的 JSON 形态不统一（围栏、前后解释、amount 字符串），需要独立解析层。

## 方案

- `CloudEndpoint.chatCompletionsUrl`：裸 host / 尾 `/v1` / 完整 `/chat/completions` 三种填法归一；非 https 抛 IAE。
- `CloudPromptBuilder`：`{"orders":[...]}` 多单契约 + 实付款/付款时间/商品名不含价的口径反例。
- `CloudDraftParser`：容错剥壳 + 订单级校验；金额非法丢单、全部非法报错；类别不在用户列表落「其他」并置 needsReview；content 数组/字符串/对象三形态清洗（≤10 项、单项 ≤30 字、去重、`、` 连接）。

## 验收

- engine 单测覆盖上述分支并通过。
- 真实模型输出分布待真机配置 Key 后观察。

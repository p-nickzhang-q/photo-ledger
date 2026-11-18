# 票 44：具体内容 UI 贯通与文档口径

- Owner: agent（QoderCN）
- Created: 2026-11-18
- Status: Resolved

## Resolved

- 结论（2026-11-18）：content 已贯通确认页、详情编辑、列表、汇总下钻与搜索；README / CONTEXT / AGENTS / ADR 口径同步。
- 证据：`:app:compileDebugKotlin`、`:app:testDebugUnitTest`、`:engine:test` 全部通过。
- 真机：云端识别后列表第三行显示「冰美式×2、可颂×1」；详情页「具体内容（可选）」字段可编辑并正确回显。汇总下钻与搜索未逐项截图，逻辑同列表/搜索单测。

## 方案

- `DraftForm` / `EntryForm` / `EntryEditState` 增加 content；确认页与详情页提供多行输入框（1-4 行，占位「如：冰美式×2、可颂」）。
- 列表：content 非空时第三行，单行省略；汇总下钻：日期后拼 ` · content`，单行省略。
- 搜索提示改为「商家 / 内容 / 类别 / 金额 / 日期」，过滤函数包含 content。
- 文档：README 默认端侧 + 可选云端口径、功能/原理/隐私/路线图；CONTEXT 新增识别引擎/云端识别/具体内容；AGENTS 说明云端独立 prompt/parser；新增 ADR-0005 并修订 ADR-0003。

## 验收

- 云端识别后：确认页可编辑 content → 列表/详情/汇总可见 → 搜索命中。
- 端侧识别后：content 为空，列表不出现空行。
- 文档中不再出现无条件的「任何联网都不做」表述。

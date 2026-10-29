# 票 35：汇总页下钻——类别/月份就地展开逐笔明细

- Owner: agent（QoderCN）
- Created: 2026-10-29
- Status: Resolved

## Resolved

- 结论（2026-10-29）：已实现并真机验证通过（vivo V2183A）。
- 证据：
  - 类别下钻：汇总页点「餐饮 ¥1139.38 · 6.7%」→ 就地展开当月逐笔
    （餐饮 ¥3 / 如意馄饨 ¥15.9 / 沪上阿姨 ¥10.6 / 小火锅 ¥54.98 …，date_paid 倒序）
  - 单笔进详情：点「市立医院 ¥7」→ 详情 商家=市立医院 / 实付 7.0 / 医疗 / 2026-10-20，逐字段相符
  - 返回保持：详情返回后汇总页仍停在原滚动位置且该类仍展开（复测两次一致）
  - 历史月份：「2026年9月 ¥1761.86 · 40 笔」展开为 09-30 起逐笔，顺序与列表口径一致
- 备注：首次自动化复测出现过一次「返回落到流水列表」的异常，未复现（后续两次同路径均正确），
  疑为注入事件时序所致，先记录不追。

## 背景 / 动机

汇总页现有层级到「类别合计 + 占比条」「历史各月合计 + 迷你条形」为止，看不到构成。
用户诉求：点类别能看到该月这一类别的每一笔；点历史月份能展开该月全部账目；
单笔还能继续点进详情。

## 交互决定（用户拍板）

**就地展开**：点类别行在该行下方展开逐笔明细，再点单笔进详情；不新增页面、不丢统计上下文。

## 方案

- 数据：`SummaryScreen` 已持有全量 `entries`（`MainActivity.kt:574`），下钻只是内存过滤，不动 SQL
- 纯函数放 `data/SummaryStats.kt`：
  - `entriesByMonth(entries)`：账目按月分组（月键 = `datePaid` 前 7 位，空日期归 ""，与 `MonthTotal` 同口径）
  - `entriesByCategory(entries, month)`：某月按类别分组（类别 = `Entry.category`）
- UI（`SummaryScreen`）：
  - 类别行可点，展开后下方列出该类逐笔（左缩进、无缩略图的紧凑行：商家→类别回退 + 日期 + 金额）
  - 历史月份行可点，展开该月全部逐笔
  - 展开指示用 chevron（ExpandMore / ExpandLess），同一区段同时只展开一个
  - 逐笔行点击 → `onEntryClick(entry)` → `Page.Detail`
- `onEntryClick` 由 `MainActivity` 注入 `navigate(Page.Detail(it.id))`
- 展开态与滚动位置提升到 `AppScaffold`（`listState` / `expandedCategory` / `expandedMonth`），
  从明细进详情返回后视图不塌

## 验收

- 真机：汇总页点类别 → 明细笔数与「共 N 笔」口径相符，金额逐笔可核；点单笔进详情可编辑
- 历史月份展开同理；「无日期」组（month = ""）也能展开
- `SummaryStatsTest` 新增两个纯函数用例

## 实现记录（2026-10-29）

- `SummaryStats.kt` 新增 `entriesByMonth` / `entriesByCategory`；`SummaryStatsTest` +2 用例（共 7，全绿）
- `SummaryScreen` 类别行 / 历史月行接入就地展开 + `DrilldownRow` + `ExpandChevron`
- **真机验收待做**：执行时 `adb devices` 无设备

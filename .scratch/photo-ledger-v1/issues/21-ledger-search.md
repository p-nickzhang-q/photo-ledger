# 票 21：流水搜索

- Owner: agent（QoderCN）
- Created: 2026-09-21
- Status: Resolved

## 背景 / 动机

P1 路线第一项（2026-09-21 优先级讨论定序：搜索 → 汇总强化 → 支付页噪音裁剪）。
账目攒多后「翻账本找某一笔」没有工具：流水列表只有按月倒序，用户记忆里只有
大概金额（"那一单 29.9"）、商家关键词或月份。

## 方案

- 客户端过滤，不加 DB 查询：本地账目量级（数百条）内存过滤零感知，Room 索引收益为负负担。
- 匹配范围：商家 / 类别 / 金额（两位小数字符串 contains，"29.9" 可命中 29.90）/ 日期（datePaid contains，"09-18"、"2026" 均可）。
- 入口：流水页顶栏搜索图标 → 顶栏切换为搜索输入框；返回键先收搜索再退页。
- 搜索态保留月份分组，月头合计按过滤结果重算（searchMonthTotals）；
  结果数在列表顶部一行展示；无结果给专用空态（不出导入按钮）。

## 交付

- `data/EntrySearch.kt`：filterEntries / searchMonthTotals 纯函数（JVM 可测）。
- `LedgerListScreen`：searching / searchQuery 参数，计数行与无匹配空态。
- `MainActivity`：顶栏搜索态切换 + 过滤接线 + BackHandler。
- `EntrySearchTest`：商家/类别/金额/日期/空查询各一。

## Resolved

- 结论（2026-09-21）：按方案交付——`data/EntrySearch.kt` 纯函数（filterEntries / searchMonthTotals）+
  顶栏搜索态 UI + 客户端过滤。7 个 JVM 单测全过；真机 0.4.7（versionCode 15）验证：
  金额（51.6）、商家关键词过滤与月头重算合计正常，返回键收搜索正常。
- 遗留观察：金额匹配走两位小数字符串 contains，"145" 不命中 14.5（输 "14.5" 可命中），
  用户量级小先不做数字归一化，有反馈再议。

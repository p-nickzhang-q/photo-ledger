# 票 09：流水与汇总视图——月分组 + 月度/类别汇总（只读呈现层）

**Status:** resolved

**Owner:** p-nickzhangq

**Created:** 2026-09（spec 期开票）

**Resolved:** 2026-09-17

## 交付内容

- **汇总查询（EntryDao 增补，无写路径）**：`observeMonthTotals`（`substr(date_paid,1,7)` 分组合计 + 笔数，月倒序）与 `observeCategoryTotals(month)`（某月各类别实付款合计，金额倒序）。POJO `MonthTotal`/`CategoryTotal`（`data/Summary.kt`），`LedgerRepository` 暴露 Flow。
  - **格式坑**：`date_paid` 存量两种格式——队列路径存 date-only（`yyyy-MM-dd`，四字段契约后）、冒烟/手工路径存 datetime（`yyyy-MM-dd HH:mm:ss`）；`substr` 前 7 位统一归组，两种格式同月合并（测试锁定）。空串（手工空表单 S3 行为）归 `""` 组，UI 显示「无日期」，不混入当月。
- **流水按月分组**：`LedgerListScreen` 在 date_paid DESC 的 DAO 排序上于月份变化处插标题条（2026年9月…），月内倒序天然保持；缩略图与点开详情看原图为票 06/07 已有能力，本票不改。
- **汇总页**（`ui/SummaryScreen.kt`，顶栏汇总图标直达）：
  - 月度汇总：当月合计大字（¥ + 笔数）+ 历史各月列表（合计 · 笔数）。
  - 类别汇总：当月各类别合计 + 占比（百分比 + 比例条），含「其他」；手工记录与提取记录同表同算。
- **口径**：统计只取 `amountPaid`（实付款）；dateSource / orderStatus / currency 不参与（CONTEXT.md 口径，测试锁定）。

## 测试（LedgerSummaryTest，6 条）

混合日期格式同月合并、月列表倒序且按月独立、类别合计含手工记录且金额倒序、类别合计限定当月不混历史、空日期归空组不进当月、口径只算实付款。

## 真机验证

流水三分组（9/8/7 月）呈现正确；汇总页当月 ¥53.9（=22.8+31.1）、餐饮 100%、历史各月 ¥65/¥66.5 均与流水逐条核对一致。金额展示 `formatAmountTotal` 做两位舍入，清掉 SUM 浮点尾差。

**Blocked by 完成情况**：08 ✅

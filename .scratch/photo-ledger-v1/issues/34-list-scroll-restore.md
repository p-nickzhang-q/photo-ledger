# 票 34：详情返回后列表跳回顶部（滚动位置丢失）

- Owner: agent（QoderCN）
- Created: 2026-10-29
- Status: Resolved

## Resolved

- 结论（2026-10-29）：已修复并真机验证通过（vivo V2183A，release 包 vc34 + 本票改动）。
- 证据：列表下滑至 2026-10-08 一段 → 点 Valve ¥135.87 进详情 → 返回，
  前后截图逐行同位置（交通 ¥10 在顶、闪购 ¥17.87 在底），无跳顶。
- 附带：汇总页同样提升滚动位置与展开态（见票 35），从明细进详情返回不塌。

## 背景 / 动机

用户真机反馈：从账目列表点进详情再返回，列表回到顶部；翻历史账目时每次都要重新下滑。

## 根因

- `LedgerListScreen` 的 `LazyColumn` 没有 `state`，用的是组合内的默认 `rememberLazyListState()`
- 页面切换是 `MainActivity.AppScaffold` 里的 `when (current)` 分支替换（`MainActivity.kt:527`），
  进详情时 `LedgerListScreen` 整个离开组合，随组合销毁的 `LazyListState` 一并丢失
- 返回时重建，scroll 位置归零

## 方案

`LazyListState` 提升到 `AppScaffold`（跨页面存活），作为参数传入 `LedgerListScreen`，
`LazyColumn(state = ...)` 使用之。无数据层改动、无导航改动。

搜索态切换不清位置：过滤后列表变短由 LazyColumn 自行 clamp，清空搜索后回到原 index。

## 验收

- 真机：列表滚到中部 → 点某条进详情 → 返回，位置不变
- 冷启动 / 搜索进出的行为不回退（搜索态仍正常出结果）

## 实现记录（2026-10-29）

- `LedgerListScreen` 新增 `listState: LazyListState` 参数（默认 `rememberLazyListState()` 兜底），
  `LazyColumn(state = listState)`
- `MainActivity.AppScaffold` 持有 `ledgerListState`，随 `Scaffold` 生命周期存活
- 顺带处理同类问题（票 35 联动）：汇总页 `summaryListState` 与两个展开状态同样提升到 `AppScaffold`
- 编译通过；`:app:testDebugUnitTest` 全绿（与本次改动无直接单测，属 UI 行为）
- **真机验收待做**：执行时 `adb devices` 无设备，未能装机复测

# 票 36：类别管理支持长按拖拽排序

- Owner: agent（QoderCN）
- Created: 2026-10-29
- Status: Resolved

## Resolved

- 结论（2026-10-29）：已实现并真机验证通过（vivo V2183A）。
- 证据：
  - 长按拖拽：「游戏」从末位拖到「其他」之后（AI 之前），松手即落位
  - 持久化：退出类别页重进顺序不变；`am force-stop` 冷启动后仍是新顺序
  - 复原：拖回末位恢复原顺序（测试数据未留存改动）
- 手法记录：真机长按拖拽用 `input motionevent DOWN` + 0.8s 停顿 + 连续 MOVE 注入，
  `input swipe` 因无停顿不会触发长按判定。

## 背景 / 动机

类别顺序目前只能在新增时定（尾部追加），用户想把自己常用的类别提到前面。
`CategoryEntity.sortOrder` 与 `ORDER BY sort_order` 早已就位（票 08），缺的是重排能力与交互。

## 交互决定（用户拍板）

**长按拖拽**：按住行拖动换位，松手落库。

## 方案

- 仓库层：`LedgerRepository.reorderCategories(orderedIds: List<Long>)`——按传入 id 顺序重写
  `sort_order`（0..n-1），未列出的 id 按原相对顺序追加尾部；事务内批量更新
- UI（`CategoryManageScreen`）：
  - `LazyColumn` + 本地顺序（`pendingIds`）：拖动期间本地顺序接管，松手回调落库，
    DB Flow 回流到一致顺序后释放本地状态（避免闪回）
  - 行上 `pointerInput(id)` + `detectDragGesturesAfterLongPress`：`graphicsLayer.translationY`
    跟手；越过相邻行半高即交换本地顺序并回退偏移；非拖动行 `animateItem()` 平滑让位
  - 行尾加拖拽把手图标（`Icons.Filled.DragHandle`）作可发现性提示；重命名/删除按钮不受影响
  - 「其他」兜底类别可拖动（其位置不参与任何逻辑），仍不可改名/删除
- `MainActivity` 注入 `onReorder = { ids -> repo.reorderCategories(ids) }`

排序变化会同步影响确认页/编辑页的类别选择器顺序（同源 `categoryNames()`），符合预期。

## 验收

- `CategoryTest` 先红后绿：重排后 `categories` 顺序 = 传入顺序；再拖回去可复原
- 真机：长按拖动换位 → 松手 → 退出类别页再进入顺序保持；杀进程重启后仍保持

## 实现记录（2026-10-29）

- `LedgerRepository.reorderCategories(orderedIds)`：事务内按传入顺序重写 `sort_order`，未列出者原序追加尾部
- `CategoryTest` +2 用例（重排/复原、缺项追加，共 9 全绿）
- `CategoryManageScreen`：`LazyColumn` + `pendingIds` 本地顺序 + `detectDragGesturesAfterLongPress`
  - 行高取自 `LazyListState.layoutInfo`，越过半行交换；`graphicsLayer.translationY` 跟手，
    被让位行用 `Modifier.animateItem()` 平滑
  - 拖拽热区只覆盖「手柄 + 名称」，避开重命名/删除按钮；`rememberUpdatedState` 防闭包读到旧列表
  - 行首 `DragHandle` 图标作可发现性提示；「其他」可拖但不可改名/删除（不变）
- 编译通过；**真机验收待做**：执行时 `adb devices` 无设备

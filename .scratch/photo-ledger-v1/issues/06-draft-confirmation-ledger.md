# 06: Draft 确认流与账目库

**What to build:**
从 Draft 到 Entry 的完整确认流与账目存储：草稿确认界面（一侧原始截图可缩放、一侧可编辑字段表单）、确认后落库成为 Entry、可放弃；手工新增不含截图的 Entry；Entry 的编辑与删除（删除时可选是否连原图一起删）；照片存私有目录、库内存路径与缩略图；流水基础列表（无分组汇总，汇总属 09）。

金额口径为实付款、日期口径为付款时间优先退下单时间（词汇表定义）。Room schema 在本票定型。接缝 S3 测试在本票落地（JVM + Room 内存库）。

**Blocked by:** 05 (端侧图像提取端到端)

**Status:** resolved

- [x] Draft 确认 → Entry 落库；放弃 → 不留痕；确认前任意字段可编辑
- [x] 确认界面可对照原图（可缩放）核对字段
- [x] 手工新增 Entry（无截图）可用；Entry 可编辑、可删除（可选连图删）
- [x] 照片存私有目录，DB 存路径与缩略图；卸载前照片不外泄
- [x] 流水基础列表可见（倒序，含缩略图）；按月分组与汇总不在本票
- [x] S3 接缝测试：确认才落库、放弃不留痕、空表单可成 Entry（Room 内存库）

## Comments

### 2026-09-14 实施完成（agent）

**架构**：
- `data/`：`Entry`（Room entity，schema v1 定型：merchant/amount_paid/currency/date_paid/date_source/order_status/category/photo_path/thumb_path/created_at/modified_at）、`EntryDao`（observeAll 倒序/insert/update/delete）、`LedgerDatabase`（单例 + inMemory 供 S3）、`LedgerRepository`（确认流领域逻辑——UI 只调仓库，放弃=什么都不发生可测）、`PhotoStore`（photos/ 与 thumbs/ 私有目录，相对路径入库）
- `ui/`：`DraftConfirmScreen`（原图可缩放 + 可编辑表单 + 确认/放弃）、`LedgerListScreen`（倒序 + 缩略图 + 空态引导）、`EntryEditScreen`（编辑 + 删除对话框含连图删选项）、`ManualEntryScreen`（手工空表单可存）
- `MainActivity`：sealed class `Page` 导航（Ledger 主体/ManualAdd/Confirm/Detail/SmokeTools 工具页保留票 04/05 冒烟入口）；端到端提取成功后单 Draft 直接进确认界面，多 Draft 取第一单其余留 logcat（票 07 队列细化）

**S3 接缝测试（9/9 过，JUnit4 + Robolectric + Room 内存库）**：
- 确认才落库（Draft 本身不产生 Entry）
- 确认时编辑字段生效、未编辑字段沿用 Draft
- 放弃不留痕（库与照片目录均无变化）
- 手工空表单可成 Entry
- 带截图确认：照片落私有目录、库存相对路径
- 删除不连图（文件留）/连图（文件删）两种行为
- 编辑保留 id 刷新 modifiedAt
- 列表倒序

**真机验证**（用户人工确认交互）：order_01 端到端 210s → Draft 4/4 字段 → 确认界面（缩放对照+编辑）→ 确认入账 → 列表出现带缩略图条目 → 编辑/删除/手工新增均正常。

**实现要点**：
- 照片存 `filesDir/ledger/{photos,thumbs}/`（内部存储，卸载即清）；DB 存相对文件名
- 缩略图在 confirm 时同步生成（Bitmap PNG 90），票 07 队列量大时可改异步
- Robolectric 必须配 JUnit4 runner（`useJUnitPlatform` + junit5 会 0 tests 静默通过——Gradle Test Executor 起了但一个类都没发现，坑已记录）
- AppScaffold 等含 `collectAsState` 的 Activity 方法需显式 `@Composable` + `import androidx.compose.runtime.getValue`

**遗留（后续票）**：
- 多 Draft 一图多单只取第一单（票 07 队列）
- 类别是自由字符串 + 快速选择按钮（票 08 类别体系）
- 按月分组与汇总（票 09）

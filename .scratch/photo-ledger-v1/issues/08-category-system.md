# 票 08：类别体系——类别入库 + 管理页 + 提取注入（闸门不降级）

**Status:** resolved

**Owner:** p-nickzhangq

**Created:** 2026-09（spec 期开票）

**Resolved:** 2026-09-17

## 交付内容

- **类别入库（Room v2）**：`data/Category.kt`（categories 表，name 唯一索引 + sort_order）+ `data/CategoryDao.kt`。`LedgerDatabase` v1→v2 迁移只建表不碰 entries（真机存量数据已验证迁移无损）；种子八类走 `onOpen` 空表补插（首装与迁移后同一条种子路径，幂等）。schema v2 起含类别表。
- **仓储层（S3 接缝）**：`LedgerRepository` 构造改为 `(db, photoStore)`（类别增删改需 `withTransaction` 跨表原子性）。方法：`addCategory`（尾部追加，重名/空名/含引号反斜杠拒绝——引号会破坏 GBNF/JSON Schema 转义，入口即拒）、`renameCategory`（级联更新其下 Entry 引用，防悬空名）、`deleteCategory`（其下 Entry 归「其他」，不丢账）。「其他」= `FALLBACK_CATEGORY` 兜底，不可删不可改名。
- **管理页**：`ui/CategoryManageScreen.kt`，列表 + 重命名对话框 + 删除确认（明示「该类别下的账目不会删除，将归入其他」）+ 新增对话框。新增/重命名统一「结果回调」语义：成功自动关框，失败留在框内显示错误。「其他」行标「兜底类别」，无编辑/删除入口。新增入口是 **FAB**（页尾按钮随列表增长会够不着；与流水页「FAB=新建」词汇一致）。
- **提取注入**：`OnDevicePipeline` 的类别参数改逐张实时取 `repo.categoryNames()`——管理页改完，下一张提取即生效（队列 extractor 与冒烟路径都改）。引擎侧零改动（类别本来就是参数）。
- **共享常量迁移**：`DEFAULT_CATEGORIES` 移入 engine（`DefaultCategories.kt`），cli/Main+GateCommand 改引用，App 种子同源——AGENTS.md 票 08 约定落地，prompt 注入口径零分叉。
- **快选 chips**：三屏（确认/手工/编辑）类别快选从硬编码 4+4 两行改 FlowRow，适配任意类别数；编辑页补上快选（原来只有文本框）。

## 质量闸门（本票独有回归关卡）

注入前后各跑一次 02 harness：**PASS，硬字段 96.7%/100%、软字段商家 100%/类别 100%**——与票 07 基线持平，类别建议不因注入降级。报告：`fixtures/gate-report-ticket08.md`。

坑记录：`--gate` 的 `--model` 传过一次 GGUF（`Unsupported or unknown file format`）——litert transport 必须喂 `.litertlm`，与 llama.cpp 路径的 GGUF 不同源。

## S3 测试（CategoryTest，7 条）

首启种子八类按内置顺序、新增尾部追加、重名拒绝（含 trim 后同名）、空名拒绝、重命名级联 Entry 引用同步、删类别其下 Entry 归「其他」且不丢账、「其他」不可删不可改名。

## 同票顺带：导航重构（用户反馈三问题）

1. **多页无返回** → AppScaffold 顶栏统一三段式：非根页 `[← 返回 | 页面名 | —]`，根页 `[照片记账 | 队列·导入·类别·工具]`；ImportQueueScreen/CategoryManageScreen 内层顶栏删除（原来真机上是双顶栏）。
2. **系统返回直接退 App** → `MainActivity` 从单变量 `mutableStateOf<Page>` 改**页栈**（`mutableStateListOf`）+ `BackHandler`：非根页逐页回退，根页放行系统默认退出。返回语义区分：箭头/系统返回 = 不保存离开（Confirm 页等同放弃，discard 本就无痕）；保存成功 = 手工录入回**来时的页面**（队列进则回队列）、确认入账回流水列表。
3. **识别结果无直达入口** → 根页顶栏新增加入队列图标；空队列给「队列还空着 + 从相册导入截图」CTA（空页是行动邀请），非空尾部「继续导入」。

真机逐项验证（adb 驱动 + 截图）：队列直达空态 ✓、系统返回回流水不退 App ✓、类别/详情页返回 ✓、根页返回正常退出 ✓、新增对话框全流程（输入→添加→自动关框→入列）✓。

**Blocked by 完成情况**：06 ✅

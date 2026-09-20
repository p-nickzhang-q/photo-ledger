# 11: 备份与导出

**What to build:**
备份与导出 (Backup & Export)：JSON 备份可出可进——导出含全部 Entry 与自定义 Category（不含照片），导入可恢复（round-trip 无损是硬要求）；CSV 导出只出不进（UTF-8 BOM，Excel 打开中文不乱码），供人阅读与 Excel 分析，不是恢复手段（备份与导出是词汇表里刻意分开的两个概念）。

接缝 S4 测试在本票落地。

**Blocked by:** 08 (类别体系)

**Status:** resolved

- [x] JSON 备份导出：含 Entry 与自定义 Category，不含照片
- [x] JSON 备份导入：恢复后记录与类别无损（S4 round-trip 测试通过）
- [x] CSV 导出：UTF-8 BOM、列头完整、Excel 打开中文不乱码
- [x] UI 上备份（可回灌）与导出（只出不进）措辞区分明确

## Comments

### 2026-09-20 · 实施结论（agent）

**Owner:** agent · **Resolved:** 2026-09-20

- **数据层** `data/Backup.kt`（纯 Kotlin，S4 测试对象）：`BackupCodec` JSON 编解码（format/version 校验，version ≤ 当前向前兼容）+ `toCsv`（UTF-8 BOM 打头、中文表头、RFC 4180 转义）；`BackupData.toEntryList()` 顶层扩展恢复为 Entry。
- **仓储** `LedgerRepository.snapshot()`（全量 Entry+类别）与 `restore(BackupData)`（事务内清表重建，Entry id/createdAt/modifiedAt 原样保回，类别 id 重发——无外部引用）。恢复 = 替换语义。
- **UI** `Page.Backup`「备份与导出」：备份区（导出 JSON / 从备份恢复，恢复前 AlertDialog 二次确认警告替换数据）+ 导出区（CSV，页面明示「只出不进，不能导回 App」）。SAF CreateDocument/OpenDocument，无存储权限。照片路径原样入备份但不含照片文件——恢复后引用悬空由列表空态兜底。
- **S4 测试** `BackupRoundTripTest`（Robolectric 双内存库）8 例：Entry 逐字段无损（含 id/时间戳）、类别 sortOrder 无损、替换语义（非空库恢复后只剩备份内容）、非备份文件拒绝、CSV BOM/表头/转义/行数/不含内部路径。`:app:testDebugUnitTest` 42 例全绿。
- **真机验证**：用户实际操作导出 CSV/备份成功（「导出功能好了」）。恢复闭环由 S4 测试覆盖（真机未走替换恢复——避免覆盖用户真实数据）。

结论：备份与导出分离落地；`decode` 对非法 JSON 抛友好 IllegalArgumentException（kotlinx SerializationException 已包装）；CSV 日期口径显示为中文（付款时间/下单时间）。

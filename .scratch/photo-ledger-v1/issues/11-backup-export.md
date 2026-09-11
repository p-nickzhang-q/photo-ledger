# 11: 备份与导出

**What to build:**
备份与导出 (Backup & Export)：JSON 备份可出可进——导出含全部 Entry 与自定义 Category（不含照片），导入可恢复（round-trip 无损是硬要求）；CSV 导出只出不进（UTF-8 BOM，Excel 打开中文不乱码），供人阅读与 Excel 分析，不是恢复手段（备份与导出是词汇表里刻意分开的两个概念）。

接缝 S4 测试在本票落地。

**Blocked by:** 08 (类别体系)

**Status:** ready-for-agent

- [ ] JSON 备份导出：含 Entry 与自定义 Category，不含照片
- [ ] JSON 备份导入：恢复后记录与类别无损（S4 round-trip 测试通过）
- [ ] CSV 导出：UTF-8 BOM、列头完整、Excel 打开中文不乱码
- [ ] UI 上备份（可回灌）与导出（只出不进）措辞区分明确

# 票 40：Entry.content 数据层贯通

- Owner: agent（QoderCN）
- Created: 2026-11-18
- Status: Resolved

## Resolved

- 结论（2026-11-18）：`Entry` 新增 `content` 列（v4，默认空串）；repo.confirm 接收 `editedContent`；备份 JSON / CSV / 搜索同步。
- 证据：`:app:testDebugUnitTest` 全绿。BackupRoundTripTest 覆盖 content 往返、新 CSV 表头、旧备份无 content 键默认空串；EntrySearchTest 覆盖按具体内容命中；LedgerRepositoryTest 覆盖 confirm 写入 editedContent。
- 未验证：v3→v4 迁移未在真机旧库上执行（SQL 为单条 `ALTER TABLE ... DEFAULT ''`，Room 内存库与实体 schema 已通过编译期校验）。

## 背景

云端识别产出「具体内容」（商品名列表），Entry 原先没有承载字段。

## 方案

- `Entry.content: String = ""`，`@ColumnInfo(name = "content", defaultValue = "''")`。
- `LedgerDatabase` version 4，`MIGRATION_3_4`：`ALTER TABLE entries ADD COLUMN content TEXT NOT NULL DEFAULT ''`。
- `BackupEntry.content`（可选字段，旧备份兼容，VERSION 保持 1）；CSV 在「商家」后插入「内容」列。
- `filterEntries` 增加 content 包含式匹配。

## 验收

- 备份 round-trip 逐字段无损（含 content）。
- 旧备份可恢复且 content 为空串；CSV 表头与行数正确。
- 搜索可按内容关键词命中。

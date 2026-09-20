# 15: 备份补照片（zip 打包）

**What to build:**
票 11 的 JSON 备份不含照片，换机后原图/缩略图丢失。把备份升级为 zip 包：`backup.json` + `photos/` + `thumbs/`（只收 Entry 引用的文件），导出/导入全程 SAF 不加权限；导入兼容票 11 的旧 `.json`（无照片恢复）。round-trip 无损从「记录与类别」扩展到「照片字节级」。

**Blocked by:** 11 (备份与导出)

**Status:** resolved

- [x] 备份导出为 zip：backup.json + photos/ + thumbs/，只含被 Entry 引用的文件（缺失文件跳过不报错）
- [x] 备份导入 .zip：账目、类别与照片文件全部恢复（照片字节级 round-trip 测试通过）
- [x] 导入兼容票 11 的 .json 备份（按文件头 PK/非 PK 自动分流）
- [x] UI 文案更新：备份说明含照片；旧格式兼容提示

## Comments

### 2026-09-20 · 实施结论（agent）

**Owner:** agent · **Resolved:** 2026-09-20

- **打包** `BackupZip`（Backup.kt）：zip = `backup.json` + `photos/<name>` + `thumbs/<name>`；照片只收 Entry 引用且文件存在的（悬空引用跳过不阻断备份）；解包按前缀归位，缺 backup.json 显性报错。
- **仓储** `exportBackupZip(out)` 返回 (账目数, 收入包照片数)；`restoreBackupZip(json, photos, thumbs)` 照片文件先落盘再走 DB 替换式恢复（失败不产生半截 DB 状态）。
- **导入分流**（MainActivity）：文件头 `PK` = zip 完整备份，否则按票 11 旧 JSON 恢复（提示「旧版备份，无照片」）。photoPath/thumbPath 原样保回，恢复后引用与文件一致。
- **测试** BackupRoundTripTest 扩至 11 例：zip round-trip 照片+缩略图字节级无损、缺失照片跳过（photoCount=0 且恢复仍无损）、非 zip 包显性失败。`:app:testDebugUnitTest` 45 例全绿。
- **真机验证**：导出 zip（17 条账目 / 17 张照片，5.56MB）→ PC 侧解包核验（17 照片零缺失零空文件，json 引用完整）→ 同包导回（替换恢复 17/9/17，logcat BACKUP_RESTORED zip=true）→ 列表缩略图正常渲染。
- **附带**：versionCode 1→2 / versionName 0.2.0——vivo 安装器对同版本号覆盖安装弹「已安装相同版本」确认框且重装易失败，升版本号走正常升级路径规避。

结论：备份语义升级为「账目+类别+照片」完整快照，JSON 兼容链保留；CSV 导出不受影响。

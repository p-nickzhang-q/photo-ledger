# 票 31：应用内版本更新检查（GitHub Release 对齐）

- Owner: agent（QoderCN）
- Created: 2026-09-24
- Status: Resolved

## Resolved

- 结论（2026-09-24）：落地并真机验证通过（vivo V2183A，versionCode 30，
  用户实测「没问题」）。
- 真机首次走查发现 HTTP 404——根因：仓库当时为私有，GitHub 对匿名请求
  隐藏存在性。随本票完成仓库开源前置：git filter-repo purge 全历史中 5 个
  含真实支付信息的 replay 快照（字符串级校验零残留，tag 随重写重建强推），
  MIT LICENSE 落地，仓库转 public。匿名 releases/latest 即刻可用。
- README 路线图与 ADR-0003 已同步（联网白名单三类：模型下载/更新检查/手动导出）。

## 背景 / 动机

App 无自更新感知，用户需主动来仓库页看新版本。本票加轻量检查：对齐
GitHub 最新 Release（`releases/latest`），非最新版时入口红点提示，点击前往
Release 页下载 APK（浏览器路径，不做应用内下载安装——避开
REQUEST_INSTALL_PACKAGES 与 FileProvider 复杂度）。

## 隐私口径（ADR-0003 local-first 注记）

App 首次因本功能出现主动网络调用（此前 INTERNET 权限仅供模型下载）。约束：
- 唯一调用目标 `api.github.com/repos/p-nickzhang-q/photo-ledger/releases/latest`，
  只读，不带任何用户数据/账目信息/设备标识
- 每次冷启动至多一次 + 用户手动触发；失败静默不骚扰
- 账本/照片/OCR 全链路依旧零联网

## 方案

1. `update/UpdateChecker.kt`：HttpURLConnection（不引新依赖）+
   kotlinx.serialization 解析（tag_name/html_url）；版本比较
   `isNewer(latest, current)` 按数字段逐位比较（0.10.0 > 0.4.9）
2. 当前版本取 `packageManager.getPackageInfo` 的 versionName（不依赖
   BuildConfig 开关）
3. UI：Ledger 根页溢出菜单新增「检查更新」——红点（material3 Badge）仅在
   Available 态显示；点击行为：Available → 浏览器打开 Release 页；否则执行
   手动检查 → Toast 反馈「已是最新 vX / 发现新版本 vY / 检查失败」
4. 冷启动后台静默检查一次（失败无感）；红点状态驻内存即可（进程死了重查）

## 验收

- isNewer 单测（同版本/补丁/次版本/主版本/位数差异）
- 真机：冷启动后菜单红点出现（当有新 Release）；点击跳 GitHub；无网时静默
- 构建无新依赖

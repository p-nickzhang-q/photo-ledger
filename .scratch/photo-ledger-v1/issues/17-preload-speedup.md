# 票 17：识别提速——启动预加载引擎 + 确认分享入口现状

- Owner: QoderCN
- Created: 2026-09-21
- Resolved: 2026-09-21

## 背景

用户实测反馈：识别慢（每批第一张明显卡顿）、偶尔出错（金额问题已在票 16 修复）。
P0 提速盘点后发现批量后台队列（票 07）已具备，剩余等待是每会话首次提取的
transport 冷加载；另确认系统分享入口票 07 已实现（SEND/SEND_MULTIPLE → 导入队列）。

## 改动

- `MainActivity.preloadEngines()`：模型三件就绪时 onCreate 即后台预加载
  OCR 引擎 + LitertLlmTransport（GPU，失败降级 CPU），日志 PRELOAD_DONE
- `obtainQueueTransport` / `ensureOcrEngine` 收进 `engineMutex` 互斥——
  预加载与队列首张提取并发时不得重复创建（原实现非 suspend，竞态下会双加载）
- `onDestroy` 关闭并清空 `queueTransport`——Activity 重建不再泄漏旧 transport

## 验证

- `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 全绿
- 真机 vivo 冷启动：PRELOAD_DONE ms=2458（GPU），首批导入零冷加载等待
- 分享入口：manifest `image/*` SEND filter 在位（票 07 实现），无需开发

## 结论

拍照提取感知路径 = 拍照 → ~5s 提取（无冷加载）。批量多张时第 2 张起 ~4s/张。
分享截图入账路径已可用：微信/支付宝长按截图 → 分享 → 照片账本。

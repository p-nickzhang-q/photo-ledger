# 票 24：启动白屏治理（windowBackground 纸色）

- Owner: agent（QoderCN）
- Created: 2026-09-21
- Status: Resolved

## 背景 / 动机

用户反馈冷启动有一段刺眼白屏（票 17 预加载后遗留的体验项，当日「这个等会」延后）。
白屏 = Compose 首帧前的系统窗口默认底色（主题 parent 为 Material Light 默认白），
时长约 2-4s（vivo 冷启动 + Compose 初始化，Displayed ~3.9s）。

## 方案

主题加 `android:windowBackground`：亮色 = 账本纸色 #F7F6F2（Theme.kt background 同源），
values-night 暗色 = #151412。空白窗口与品牌底色一致，白闪消失。
不引入 core-splashscreen（本票只治颜色，等待时长治理另立票：基线配置/启动优化）。

## Resolved

- 结论（2026-09-21）：values/themes.xml + values/colors.xml + values-night/colors.xml 三文件，
  真机 versionCode 20 验证：冷启动 1s 截图窗口底色为纸色（非白），首帧 ~4s 后正常渲染。
- 遗留：空白窗口时长本身（~2-4s）未压缩——候选手段 AndroidX SplashScreen / 基线配置，
  有需要另立票。

## 追加（2026-09-22）：白屏真因是预加载占主线程

用户反馈「之前没有白屏，现在 2-3s」——白屏不是系统窗口固有开销，是票 17 预加载
（OCR ONNX + litert 模型加载 ~2.5s）跑在 `lifecycleScope.launch` 默认的 Main
dispatcher 上，卡住 Compose 首帧（Displayed 3.9s）。修复：preloadEngines 改
`launch(Dispatchers.Default)`。真机验证：Displayed **+357ms**，冷启动直接进列表。

# 04: Android 骨架与 JNI 冒烟

**What to build:**
可安装到目标机（vivo V2183A）的 APK 骨架：项目脚手架（Kotlin + Compose + Room，包名 io.github.pnickzhangq.photoledger，显示名"照片记账"）+ llama.cpp 的 NDK 交叉编译集成 + JNI 绑定 + 真机上跑通一次文本推理并上屏。

本票只证明"llama.cpp 在这台手机上能推理"，不涉及图像与提取。开发期模型文件经 adb 推送到设备（面向用户的模型下载/导入属 10）。最低支持线 Android 10 (API 29) + 8GB RAM 的构建配置在本票定型。

**Blocked by:** 01 (桌面提取管线打通——复用其集成方式结论)

**Status:** ready-for-agent

- [ ] APK 可安装到目标机并启动，无崩溃
- [ ] 真机加载一个文本模型（如 Qwen3 小杯文本版或 01 所用模型的文本部分），输入一句 prompt，推理结果上屏
- [ ] llama.cpp 的 NDK 编译进 Gradle 构建（不依赖手工 prebuilt 步骤之外的魔法），构建步骤记录进 Comments
- [ ] adb 推送模型的开发期约定记录进 Comments（路径、加载代码入口），供 05/10 复用

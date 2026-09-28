# 手机端侧小模型应用生态调研（2026-09）

> 调研范围：0.5B–7B 级模型在手机上的生产级落地；区分「真端侧推理」与「云端/远端客户端」。
> 取证纪律：厂商宣传的"离线"不作数，尽量以官方文档/仓库/开发者博客为准，可信度在文末证据清单标注。

## 结论

1. **端侧小模型在 2026 年已跨过"演示"阶段，但成熟的是"系统级窄功能"，不是通用助手。** 真正跑在数亿设备上的形态是：摘要/改写/校对、通知与快捷回复、离线翻译、语音转写、图片描述——即**固定任务的短输出生成**，而不是长对话/推理。
2. **三个标杆生态**：(a) Google——LiteRT‑LM 是[官方定义的 production-ready 引擎](https://github.com/google-ai-edge/LiteRT-LM)，已支撑 Chrome 内置 AI、Chromebook Plus、Pixel Watch 智能回复，并声明"支持数百亿设备规模"（[Google 官方博客](https://developers.googleblog.com/on-device-genai-in-chrome-chromebook-plus-and-pixel-watch-with-litert-lm/)）；(b) Apple——~3B 端侧模型 + Foundation Models 框架，用 `@Generable` 做**受约束结构化生成**，已有 SmartGym、Stoic、CellWalk、SwingVision 等数十款第三方 App 接入（[Apple Newsroom](https://www.apple.com/newsroom/2025/09/apples-foundation-models-framework-unlocks-new-intelligent-app-experiences/)）；(c) Meta——ExecuTorch 支撑 Instagram/WhatsApp/Facebook/Messenger 的端侧体验（[仓库](https://github.com/pytorch/executorch)）。
3. **第三方 App 生态呈"哑铃形"**：一端是开发者自娱的聊天壳（PocketPal、ChatterUI、MLC Chat，真端侧但用户量小），另一端是垂直工具（离线转写 Whisper Notes 4.7 分/631 评分、系统翻译），中间层（通用助手）几乎被系统厂商与云厂商占满。
4. **"端侧小模型做结构化字段提取"已有官方背书**：Apple 文档明确把 **entity extraction** 列为端侧模型擅长任务，并给出 guided generation 的结构保证；Google 在 I/O 26 说 Gemini Nano 4 面向 **data extraction** 与摘要；LiteRT‑LM 原生支持受限解码（strict JSON schema），官方称可"完全消除解析失败"（[博客](https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/)）。
5. **对 photo-ledger 的启示**：路线（端侧 OCR + 0.6B + 约束解码 + 离线）与两大生态的官方技术主张完全同向——"固定 schema 的图片→字段抽取"正是端侧小模型被验证可行的甜点区。风险不在方向，而在**设备碎片化**（同款模型随机型/系统版本不同，见 ML Kit 的 nano‑v2/v3/v4 机型表）、**后台/配额限制**（ML Kit 明确禁用后台推理）、以及**公开工程复盘稀缺**（没有现成的 GBNF 端侧抽取案例可抄，自建闸门评测是必要投入）。

## 1. 运行时/框架成熟度

| 运行时 | 状态（2026-09） | 生产采用（可查证） | 关键事实 |
| --- | --- | --- | --- |
| **LiteRT‑LM**（Google AI Edge） | ✅ 生产级 | Chrome 内置 AI、Chromebook Plus、Pixel Watch Smart Replies、Google AI Edge Gallery；官方称"支持数百亿设备" | Kotlin/C++ 稳定，Swift/JS 早期预览；CPU/GPU/NPU；原生**受限解码**与工具调用；Gemma 4 E2B 解码 52 tok/s（S26 Ultra GPU）、56 tok/s（iPhone 17 Pro Metal）；MTP 投机解码再提速 2.2×；2.58GB 模型物理内存占用 607MB（[博客](https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/)、[仓库](https://github.com/google-ai-edge/LiteRT-LM)） |
| **MediaPipe LLM Inference** | ✅ 生产级（高层 API） | 与 LiteRT‑LM 同栈 | 官方明示其底层已是 LiteRT‑LM，属"高层封装"层（[博客](https://developers.googleblog.com/on-device-genai-in-chrome-chromebook-plus-and-pixel-watch-with-litert-lm/)） |
| **Apple Foundation Models framework** | ✅ 随 iOS 26 发布 | Apple 系统功能 + 数十款第三方 App | 端侧 ~3B（2–4bit 量化 + LoRA 适配器，[Apple ML 报告](https://machinelearning.apple.com/research/introducing-apple-foundation-models)）；`@Generable` 结构化生成、工具调用；**上下文仅 4096 token**，超限抛 `contextSizeExceeded`，官方建议分块（[文档](https://developer.apple.com/documentation/foundationmodels)） |
| **ONNX Runtime GenAI** | ✅ 可用，移动端以 Android 为主 | Foundry Local、Windows ML、VS Code AI Toolkit | 官方支持矩阵列出 **Constrained decoding**、grammar for tool calling、多 LoRA；Android 已支持，**iOS 仍在路线图**（[仓库](https://github.com/microsoft/onnxruntime-genai)） |
| **llama.cpp / llama.rn** | ✅ 移动端事实标准 | PocketPal AI、ChatterUI 等 | GGUF + CPU/GPU/NPU 混合后端；llama.rn 提供 React Native 绑定（[仓库](https://github.com/a-ghorbani/pocketpal-ai)、[ChatterUI](https://github.com/Vali-98/ChatterUI)） |
| **MLC‑LLM / MLCEngine** | ⚠️ 活跃但偏研究/自部署 | MLC Chat 官方 Demo | 覆盖 iOS（Metal）与 Android（Adreno/Mali OpenCL），OpenAI 兼容 API（[仓库](https://github.com/mlc-ai/mlc-llm)） |
| **ExecuTorch**（PyTorch） | ✅ 生产级 | Instagram、WhatsApp、Facebook、Messenger、Quest/Ray‑Ban Meta | PyTorch 官方端侧栈；LLM/语音/多模态 runner（[仓库](https://github.com/pytorch/executorch)） |
| **Gemini Nano / AICore + ML Kit GenAI** | ✅ 系统级服务 | Google 一方 + 三方 App（见 §2） | 提供 Summarize/Proofread/Rewrite/Image Description/Prompt/Speech Recognition；多 App 共享系统模型省存储；**仅前台可用**（后台直接 `BACKGROUND_USE_BLOCKED`）、**按 App 限流配额**（[文档](https://developers.google.com/ml-kit/genai)） |

要点：运行时层面"能不能跑"已不是问题，**真正的护城河是系统级模型分发**（AICore / Foundation Models）——第三方不用带模型、不用管更新，代价是能力上限与调度权都不在自己手里。

## 2. 系统级应用（Apple / Google / 三星 / 国产厂商）

**Apple**
- 端侧 ~3B 模型 + 服务端模型（Private Cloud Compute）两级架构；端侧负责摘要、**实体抽取**、文本/图像理解、改写、游戏对话等，官方直言"需要更强推理与更大上下文时，用 Private Cloud Compute"（[文档](https://developer.apple.com/documentation/foundationmodels)）。
- Foundation Models 框架（2025 WWDC 发布，iOS 26 起）已被第三方接入，Apple 官方点名的 App：SmartGym（训练计划）、Stoic（日志提问）、CellWalk（工具调用讲解）、Stuff（Listen/Scan）、VLLO、Signeasy、Agenda、Detail、Essayist、OmniFocus 4、Grammo、Lil Artist、Vocabulary、Platzi、SwingVision 等（[Apple Newsroom](https://www.apple.com/newsroom/2025/09/apples-foundation-models-framework-unlocks-new-intelligent-app-experiences/)）。
- 第三方接入的核心原因就是 guided generation：`@Generable` 定义 Swift 结构体，框架保证输出符合类型（[文档](https://developer.apple.com/documentation/foundationmodels)）。

**Google**
- Gemini Nano 经 AICore 系统服务落地；LiteRT‑LM 驱动 Chrome 内置 AI（官方称"Gemini Nano 最大规模部署"）、Chromebook Plus、Pixel Watch（[博客](https://developers.googleblog.com/on-device-genai-in-chrome-chromebook-plus-and-pixel-watch-with-litert-lm/)）。
- ML Kit GenAI 提供开箱 API（摘要/校对/改写/图片描述/Prompt/语音识别），机型覆盖 Pixel、三星 S26/Z Fold8、小米 15/17、OPPO Find X8/X9、vivo X200/X300、荣耀 Magic 8、一加 15、iQOO 15、POCO 等；注意不同机型对应 nano‑v2/v3/v4 不同模型版本（[设备支持表](https://developers.google.com/ml-kit/genai)）。
- I/O 26 宣布 **Gemini Nano 4 预览**，明确面向 **data extraction** 与摘要，并引入混合推理档位（`PREFER_ON_DEVICE`/`ONLY_ON_DEVICE`/`PREFER_CLOUD`/`ONLY_CLOUD`）与"自带微调 SLM"（LiteRT‑LM）（[官方博客](https://developer.android.com/blog/posts/top-ai-on-android-updates-for-building-intelligent-experiences-from-google-i-o-26)）。

**三星**
- Galaxy AI 功能集（通话实时翻译、笔记助手、即圈即搜等）采取"端侧 + 云端"混合：部分机型/功能用端侧模型，部分依赖 Google Gemini 云；官方支持页按机型列出"扩展模型支持"差异（[Samsung 支持页](https://www.samsung.com/nz/support/mobile-devices/expanded-model-support-for-galaxy-ai-features/)，可信度中，二手 [Wikipedia](https://en.wikipedia.org/wiki/Galaxy_AI)）。

**国产厂商**
- 2026‑07，网信办同批公示七款手机端侧大模型备案（Apple 中国版/Qwen、华为盘古+小艺、OPPO AndesGPT 7B、vivo 蓝心、小米 MiMo/澎湃、三星、努比亚豆包），官方口径为"端侧 AI 从概念预热进入兑现周期"（[EEPW 报道](https://www.eepw.com.cn/article/202607/482396.htm)）。
- vivo 2026‑09‑16 发布蓝心端侧 nano（3B），主打"本地记住/本地执行"，称不依赖云即可完成任务（[新浪财经](https://finance.sina.cn/2026-09-16/detail-inirzwqt4624469.d.html)）。
- 小米澎湃 OS 4 宣传"离线大模型/超级小爱内置 MiMo"（[新浪转载](https://k.sina.cn/article_7879996427_1d5af340b06801kiqq.html)，媒体口径，可信度中）。

## 3. 第三方成熟 App 盘点

| App | 平台 | 模型/运行时 | 端侧程度 | 规模/评价 | 来源 |
| --- | --- | --- | --- | --- | --- |
| **PocketPal AI** | iOS/Android | llama.cpp（llama.rn）+ GGUF；CPU/GPU/NPU | ✅ 真端侧推理（模型需本地下载） | GitHub 8.4k★ | [仓库](https://github.com/a-ghorbani/pocketpal-ai) |
| **ChatterUI** | Android | llama.cpp（cui‑llama.rn）+ GGUF | ✅ 真端侧（也支持远端 API 模式） | GitHub 开源 | [仓库](https://github.com/Vali-98/ChatterUI) |
| **Private LLM** | iOS | 商店文案称完全离线；**引擎未一手核实** | ⚠️ 疑似真端侧（未证实技术路线） | App Store 付费榜常客 | [App Store](https://apps.apple.com/us/app/id6478563951) |
| **MLC Chat** | iOS/Android | MLC‑LLM（MLCEngine，Metal/OpenCL） | ✅ 真端侧 | 官方 Demo | [仓库](https://github.com/mlc-ai/mlc-llm) |
| **Google AI Edge Gallery** | iOS/Android | LiteRT‑LM + Gemma 系列 | ✅ 真端侧（官方演示，含函数调用/RAG） | 2025 年起"viral" | [博客](https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/)、[仓库](https://github.com/google-ai-edge/gallery) |
| **Enchanted** | iOS/macOS | Ollama 前端 | ❌ **不是手机端侧**：需连自建 Ollama 服务，属"本地服务器客户端" | 开源 | [分类列表](https://www.promptquorum.com/zh/local-llms/best-local-llm-frontends)（二手） |
| **Layla** | Android/iOS | Android 端宣传本地模型；iOS/模型来源**未一手核实** | ⚠️ 待核实 | Play 商店上架 | [Google Play](https://play.google.com/store/apps/details?id=com.layla) |
| **Whisper Notes** | iOS | Whisper（本地权重） | ✅ 真端侧，"100% offline, on your device" | 4.67★ / 631 评分；宣称 7 万+ 用户 | [App Store](https://apps.apple.com/us/app/id6447090616) |
| **Apple Translate / Google Translate** | iOS/Android | 系统离线翻译模型（非 LLM） | ✅ 端侧（下载离线语言包后） | 系统级预装 | [Apple 离线翻译](https://www.howtogeek.com/691438/how-to-enable-offline-translation-in-apples-translate-app-on-iphone/)（二手）、[Google Play 官方说明](https://play.google.com/store/apps/details?id=com.google.android.apps.translate) |
| **Finny（AI 记账）** | iOS | **AI 功能走云端**：需 Apple 登录、按次消耗 AI credit、支持云同步；仅"手动记账"离线 | ❌ 非端侧 AI | 4.37★ / 30 评分 | [官网 FAQ](https://getfinny.app/) |
| **moneasy（AI 记账）** | iOS | 小票识别/语音记账；宣称"数据留在设备/iCloud" | ⚠️ 端侧程度未明示（未提模型） | 独立开发者，评分少 | [App Store](https://apps.apple.com/cn/app/moneasy-ai%E8%AE%B0%E8%B4%A6-%E5%B0%8F%E7%A5%A8%E8%AF%86%E5%88%AB/id6742516728) |

结论性观察：
- **聊天类真端侧 App 普遍是"开发者工具"而非大众产品**（PocketPal 8.4k star 已是头部），它们的价值是验证 llama.cpp 在手机上可用。
- **有规模的真端侧 App 集中在"确定性任务"**：转写（Whisper Notes）、翻译（系统级）、拍照辅助（系统相机/翻译），而不是自由对话。
- **记账/财务赛道没有查到"端侧 LLM 做小票结构化提取"的成熟产品**：Finny 的 AI 是云端（且核心卖点 Tap to Track 依赖 Apple Pay 捕获而非模型）；moneasy 未公开技术路线。photo-ledger 在这个细分上目前是空位。

## 4. 结构化提取场景与约束解码采用

- **Apple 是最大背书**：官方文档直接把 **entity extraction（实体抽取）** 列为端侧模型擅长任务，并用 `@Generable` + guided generation 提供"输出必符合你的类型"的强保证；Apple 的第三方案例里 CellWalk 用工具调用、SwingVision 类 App 用结构化输出（[文档](https://developer.apple.com/documentation/foundationmodels)、[Newsroom](https://www.apple.com/newsroom/2025/09/apples-foundation-models-framework-unlocks-new-intelligent-app-experiences/)）。
- **LiteRT‑LM 原生受限解码**：官方支持 strict JSON schema / grammar，配合 Thinking Mode，官方称"完全消除 parser breaking"，并给出质量对比图（S25 Ultra CPU）；仓库中存在 `PATCH.llguidance_grammar`，即其文法引擎基于 LLGuidance（[博客](https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/)、[仓库](https://github.com/google-ai-edge/LiteRT-LM)）。
- **ONNX Runtime GenAI** 同样把 constrained decoding 与"grammar specification for tool calling"列为特性（[仓库](https://github.com/microsoft/onnxruntime-genai)）。
- **llama.cpp 侧** GBNF/`json_schema` 是移动端约束解码的事实标准（PocketPal、ChatterUI 均走 llama.cpp 路线）；学术界 XGrammar‑2（2026）继续在"动态结构化生成"上加码（[论文](https://arxiv.org/html/2601.04426v3)）。
- **空缺**：公开的"端侧 0.5B–1B + GBNF 做票据字段抽取"的工程复盘极少（GitHub/博客检索到的多是桌面端或个人实验）。photo-ledger 自建的 30 张标注闸门（≥95% 硬字段门槛）在公开材料里几乎没有对标物——这既是差异化资产，也意味着没有现成经验可抄。

## 5. 成熟度教训与常见坑

1. **内存是硬约束，但工程手段有效**：LiteRT‑LM 用"权重缓存 + 编码器按需加载"把 2.58GB 的 Gemma 4 E2B 压到 607MB 物理内存；在 Pixel Watch 上则干脆裁剪成最小模块流水线（[博客](https://developers.googleblog.com/on-device-genai-in-chrome-chromebook-plus-and-pixel-watch-with-litert-lm/)）。Android 17 还引入了 app memory limits，跑大模型要按系统内存预算设计（[I/O 26 博文](https://developer.android.com/blog/posts/17-things-to-know-for-android-developers-at-google-i-o)）。
2. **多任务共享一个基座模型是主流做法**：Engine/Session 分离 + 每功能 LoRA + Copy‑on‑Write KV‑cache（克隆 <10ms），否则"一个功能一个模型"在手机上不可行（同上）。
3. **上下文窗口比想象的小**：Apple 端侧 4096 token，且中文≈1 字 1 token（英文 3–4 字符 1 token），官方建议超限就分块处理（[文档](https://developer.apple.com/documentation/foundationmodels)）。这对"长小票/长截图"是明确的工程约束。
4. **系统级调用受限**：ML Kit GenAI 只允许前台推理（后台/前台服务直接 `BACKGROUND_USE_BLOCKED`），且有按 App 的短时/长时配额（`BUSY`、`PER_APP_BATTERY_USE_QUOTA_EXCEEDED`）——这解释了为什么很多 App 把 AI 做成"用户手动点一下"，也解释了部分功能被迫上云（[文档](https://developers.google.com/ml-kit/genai)）。
5. **速度瓶颈是内存带宽**：官方直言标准推理"memory‑bandwidth bound"，因此用 MTP 投机解码（2.2×）与同 IP 内存局部性优化提升吞吐（[博客](https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/)）。
6. **设备碎片化是最大暗礁**：同一 API 在不同机型对应不同模型版本（nano‑v2/v3/v4）、且各 OEM 机型列表不一致；第三方 App 必须做能力探测与降级（[设备表](https://developers.google.com/ml-kit/genai)）。
7. **云仍是兜底**：Apple 明确"更强推理/更大上下文 → Private Cloud Compute"；Google 推出四档 on‑device/cloud 偏好参数，本质承认单靠端侧不够（Apple 文档、[I/O 26](https://developer.android.com/blog/posts/top-ai-on-android-updates-for-building-intelligent-experiences-from-google-i-o-26)）。
8. **生态事实**：有规模的端侧推理要么出自系统厂商（Chrome/Pixel Watch/Apple 翻译），要么出自"确定性任务 + 买断制"的小团队（Whisper Notes）。纯聊天类端侧 App 至今没有大 DAU 案例——这是对"端侧 = 通用助手"叙事的反证，也是对"端侧 = 窄而确定任务"的正面证据。

## 6. 证据清单

| # | 结论 | 来源 | 可信度 |
| --- | --- | --- | --- |
| 1 | LiteRT‑LM 为官方 production-ready 引擎，驱动 Chrome/Chromebook Plus/Pixel Watch | [GitHub README](https://github.com/google-ai-edge/LiteRT-LM) | 一手（官方仓库） |
| 2 | 52/56 tok/s 解码、MTP 2.2×、607MB 内存跑 2.58GB 模型、原生 constrained decoding | [Google Developers Blog 2026-05](https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/) | 一手（官方博客） |
| 3 | Engine/Session + LoRA + CoW KV‑cache；MediaPipe LLM Inference 底层即 LiteRT‑LM | [Google Developers Blog 2025-09](https://developers.googleblog.com/on-device-genai-in-chrome-chromebook-plus-and-pixel-watch-with-litert-lm/) | 一手 |
| 4 | Apple 端侧 ~3B、2–4bit 量化、LoRA 适配器 | [Apple ML Research](https://machinelearning.apple.com/research/introducing-apple-foundation-models) | 一手（技术报告） |
| 5 | guided generation/@Generable、entity extraction、4096 token 上限、PCC 分工 | [Apple Foundation Models 文档](https://developer.apple.com/documentation/foundationmodels) | 一手（官方文档） |
| 6 | 第三方 App 接入名单（SmartGym/Stoic/CellWalk/SwingVision 等） | [Apple Newsroom 2025-09](https://www.apple.com/newsroom/2025/09/apples-foundation-models-framework-unlocks-new-intelligent-app-experiences/) | 一手（官方新闻稿） |
| 7 | ML Kit GenAI API 清单、机型/nano 版本表、前台限制与配额 | [ML Kit GenAI 文档](https://developers.google.com/ml-kit/genai) | 一手 |
| 8 | AICore 架构与隐私隔离、多 App 共享系统模型 | [Gemini Nano 文档](https://developer.android.com/ai/gemini-nano) | 一手 |
| 9 | I/O 26：Nano 4 预览面向 data extraction；混合推理档位 | [Android 官方博客](https://developer.android.com/blog/posts/top-ai-on-android-updates-for-building-intelligent-experiences-from-google-i-o-26) | 一手 |
| 10 | ExecuTorch 支撑 Instagram/WhatsApp/Facebook/Messenger | [ExecuTorch 仓库](https://github.com/pytorch/executorch) | 一手 |
| 11 | ONNX Runtime GenAI：constrained decoding、Android 支持、iOS 路线图 | [仓库](https://github.com/microsoft/onnxruntime-genai) | 一手 |
| 12 | MLC‑LLM 覆盖 iOS Metal / Android OpenCL | [仓库](https://github.com/mlc-ai/mlc-llm) | 一手 |
| 13 | PocketPal：llama.cpp/llama.rn + GGUF，8.4k★ | [仓库](https://github.com/a-ghorbani/pocketpal-ai) | 一手 |
| 14 | ChatterUI 使用 llama.cpp 端侧推理 | [仓库](https://github.com/Vali-98/ChatterUI) | 一手 |
| 15 | Enchanted 属 Ollama 前端（非端侧） | [本地 LLM 前端综述](https://www.promptquorum.com/zh/local-llms/best-local-llm-frontends) | 二手 |
| 16 | Whisper Notes 4.67★/631 评分、宣称 100% 离线 | [App Store 页面](https://apps.apple.com/us/app/id6447090616) | 一手（商店文案） |
| 17 | Finny 的 AI 需登录 + AI credit + 云同步（非端侧 AI） | [官网 FAQ](https://getfinny.app/) | 一手（官网文案） |
| 18 | moneasy 小票识别/语音记账，宣称数据留在设备 | [App Store 页面](https://apps.apple.com/cn/app/moneasy-ai%E8%AE%B0%E8%B4%A6-%E5%B0%8F%E7%A5%A8%E8%AF%86%E5%88%AB/id6742516728) | 一手（商店文案，技术路线未披露） |
| 19 | 七款国产手机端侧大模型同批备案（含 OPPO AndesGPT 7B 等） | [EEPW 2026-07](https://www.eepw.com.cn/article/202607/482396.htm) | 二手（行业媒体） |
| 20 | vivo 蓝心端侧 nano 3B 发布（2026-09-16） | [新浪财经](https://finance.sina.cn/2026-09-16/detail-inirzwqt4624469.d.html) | 二手 |
| 21 | 小米澎湃 OS 4 离线大模型/MiMo 小爱 | [新浪转载](https://k.sina.cn/article_7879996427_1d5af340b06801kiqq.html) | 二手（媒体口径） |
| 22 | Galaxy AI 部分功能端侧、部分云端；机型支持差异 | [Samsung 支持页](https://www.samsung.com/nz/support/mobile-devices/expanded-model-support-for-galaxy-ai-features/)、[Wikipedia](https://en.wikipedia.org/wiki/Galaxy_AI) | 一手（厂商页）+ 二手 |
| 23 | Apple Translate 离线语言包 | [HowToGeek](https://www.howtogeek.com/691438/how-to-enable-offline-translation-in-apples-translate-app-on-iphone/) | 二手 |
| 24 | Google Translate 离线翻译 | [Google Play 官方页面](https://play.google.com/store/apps/details?id=com.google.android.apps.translate) | 一手（商店文案） |
| 25 | 结构化生成引擎进展（XGrammar‑2） | [arXiv 2026](https://arxiv.org/html/2601.04426v3) | 一手（论文） |

### 未解决问题（待补）

- Private LLM、Layla 的技术路线未拿到一手证据（官网/仓库本次不可达），表中标注为"待核实"。
- 未找到"第三方 App 用 GBNF/LLGuidance 做票据抽取"的公开工程复盘；建议后续在 llama.cpp/LLGuidance issue 区做定向检索。

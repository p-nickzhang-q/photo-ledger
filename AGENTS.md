# AGENTS.md

This file provides guidance to the AI agent when working with code in this repository.

## 模块

- `engine/` 纯 Kotlin 共享引擎：OCR 后处理、PromptBuilder、GrammarGenerator（GBNF/JSON Schema 约束）、DraftNormalizer、闸门评分
- `engine-llamacpp/` llama.cpp 后端（对照保留）；`cli/` 桌面质量闸门；`app/` Android 应用（LiteRT-LM，OCR + 0.6B 约束解码）

## 构建环境（硬约束：WSL + Windows 双侧）

- **`:app` 的任何任务必须走 Windows 侧 Gradle**——SDK 在 `D:\Android\Sdk` 且无 Linux build-tools，WSL 侧跑 `:app` 会报 "Build Tools corrupted"：
  ```bash
  JAVA_HOME='/mnt/d/Program Files/Java/ms-21.0.7' WSLENV='JAVA_HOME/p' cmd.exe /c "gradlew.bat :app:testDebugUnitTest"
  ```
- WSL 侧只能跑纯 JVM 模块：`JAVA_HOME=~/.jdks/ms-21.0.9 ANDROID_HOME=/mnt/d/Android/Sdk ./gradlew :engine:test`（`:cli:run` 同理；ANDROID_HOME 缺失连 configure 都不过）
- adb 不在 PATH：`/mnt/d/Android/Sdk/platform-tools/adb.exe`（Windows 版，WSL 直接调用）
- 模型下载用国内源：ModelScope `modelscope.cn/models/<org>/<repo>/resolve/master/<file>`（稳）；hf-mirror 不稳定

## 质量闸门（改 prompt / grammar / schema / 契约必跑）

30 张标注集是行为契约的裁判——历史上有 prompt「瘦身」在这里被抓出 order_12 回归的前科。硬字段 ≥95% 才算过：

```bash
./gradlew :cli:run --args="--gate <csv绝对路径> --images <目录绝对路径> --model <模型绝对路径> --transport litert --litert-backend cpu --out <报告.md>"
```

数据在 `.scratch/photo-ledger-v1/fixtures/orders.csv`（图片同目录）。**四个路径参数必须绝对路径**（相对路径图片解析会全报缺失）。

## 契约三处耦合（改一处必须同步）

`PromptBuilder`（prompt 字段说明）↔ `GrammarGenerator.fromSchema`（GBNF，llama.cpp 用）/ `toJsonSchema`（JSON Schema，LiteRT LLGuidance 用）↔ `DraftNormalizer`（解析与默认值）。

当前 Draft 契约只有 3 个模型输出字段：amountPaid / datePaid / category。currency=CNY、dateSource=PAYMENT_TIME、orderStatus 由引擎默认值承接；datePaid 空串合法（App 入账时自动填导入当天）。**merchant 已退出模型输出**（2026-09：0.6B 抄写中文店名太弱——prompt 示例泄漏/列表截断碎片/抄错行，真机连续出错），Draft.merchant 留空由用户详情页后补；列表行主标题回退「商家（手填）→ 类别」。

## 已知死路（勿重复踩）

- LiteRT-LM 0.17.0 的两档 INT4（`dynamic_wi4b32_afp32`、`qwen3_0_6b_mixed_int4`）在约束解码下 30/30 失败（LLGuidance 掩码失效，`Parser Error: token "Ġ"`），且无更新版 runtime——上游发新版前勿再尝试 INT4
- llama.cpp ARM 构建必须显式 `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod` + `GGML_OPENMP=OFF`（交叉编译下 native 检测失效；OpenMP 会段错误）
- prompt 里的口径反例（「取实付款不要商品单价」等）是质量资产，不可删——有闸门回归前科
- schema 的 enum 值必须经 `stripGbnfQuotes`（迭代剥双层引号）后裸值入 schema；`date ::=` 行无 `|` 时是范围规则，不可当候选清单解析（会产出非法 JSON）

## 仓库约定

- 工单在 `.scratch/photo-ledger-v1/issues/NN-*.md`，收口时补 Owner / Resolved / 结论（规范见 `docs/agents/issue-tracker.md`）
- 提交信息用中文，`票NN:` 前缀；代码与文档可分笔提交
- 领域词汇（实付款 / 付款时间 / Draft / Entry 等）以根目录 `CONTEXT.md` 为准；架构决策在 `docs/adr/`

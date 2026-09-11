# 01: 桌面提取管线打通（POC）

**What to build:**
在桌面环境（JVM）跑通提取的最小完整链路：加载一张真实订单截图 → llama.cpp 加载 Qwen3-VL-4B-Instruct Q4_K_M → 以 GBNF 约束解码（由提取 JSON Schema 生成文法）→ 解析为合法 JSON → 归一为 Draft 字段（商家、日期含口径标注、实付款金额、币种、订单状态文本、类别建议）。金额取实付款口径、日期取付款时间优先退下单时间。

提取引擎核心（prompt 构造、grammar 生成、JSON 解析、口径归一）实现为桌面与 Android 可共享的纯 Kotlin 模块——本票只验证桌面路径，Android 路径由后续票复用同一模块。

本票同时验证 spec 点名的技术前提：llama.cpp 的"多模态输入 + JSON schema 约束解码"组合在官方文档中无现成示例，若组合不可行，需要 GBNF 后处理或事后校验兜底，结论记入票内 Comments。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] 桌面端一条命令跑通：任选一张真实订单截图，输出合法的 Draft JSON
- [x] GBNF 约束生效：连续多张截图提取无一例 JSON 格式违规（malformed output 为 0，字段内容对不对是 02 的事）
- [x] 提取引擎核心位于桌面/Android 可共享的纯 Kotlin 模块中，不含 JVM 桌面专属依赖
- [x] llama.cpp 集成方式（JNI 边界或进程内嵌）与构建方式记录进票内 Comments，供 04 复用
- [x] "多模态 + 约束解码"组合的可行性结论（可行 / 需兜底 + 兜底方案）记录进 Comments

## Comments

### 2026-09-11 · 实施结论（agent）

#### 1. "多模态 + 约束解码"组合可行性结论：**可行（POC 实证通过），但路线已切换**

- llama-server（b6993）同时启用 libmtmd 多模态（image_url data URI）与 `grammar` 字段（GBNF）组合，实测可行：3 张合成 + 1 张真实截图均输出合法 Draft JSON，无一次格式违规、无需任何后处理兜底。官方文档无组合示例的顾虑（调研未核实项 ⑤）被实证消除。
- **但**：组合可行 ≠ 端侧可用。1080×2400 真机截图在桌面 10 核 CPU 上 vision prefill 单张 3-5 分钟，手机推算 15-40 分钟/张，不可接受。经用户决策，提取路线改为「轻量 OCR + 文本小模型」，见 ADR-0004。本组合的可行性结论对后续任何 VLM 复活路径（质量闸门不达标时的备选）依然有效。

#### 2. llama.cpp 集成方式与构建方式（供 04 复用）

- **集成方式（桌面 POC）**：llama-server 子进程 + OpenAI 兼容 HTTP（`/v1/chat/completions`），图像走 `image_url`（data URI base64），约束走请求体 `grammar` 字段（llama-server 私有扩展，直接吃 GBNF，不经过其内置 schema 转换）。零 JNI 样板，进程生命周期由 `LlamaServerProcess`（engine-llamacpp 模块）管理：spawn → `/health` 轮询就绪 → destroy。
- **Android 端（04）建议**：同一 transport 抽象（`engine` 模块 `LlmTransport` 接口）换 JNI 进程内实现即可，引擎核心零改动；若继续 llama-server 路线则可内嵌二进制。OCR 路线下 llama.cpp 只承载文本小模型（0.6B-1.7B），JNI 边界大幅简化（无 mmproj/图像嵌入）。
- **构建命令**（桌面，CPU）：
  ```
  cmake -B build -DGGML_CUDA=OFF -DGGML_VULKAN=OFF -DGGML_NATIVE=ON \
        -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=ON \
        -DLLAMA_CURL=OFF -DCMAKE_BUILD_TYPE=Release
  cmake --build build --target llama-server -j10
  ```
  注意：需要 `-DLLAMA_CURL=OFF`（缺 libcurl-dev 时配置失败）；本机 GTX 1650 因 WSL 驱动缺 libcuda 不可用，GPU 加速需在原生 Linux/Windows 或手机 Vulkan 侧验证。
- **模型文件（SHA256 校验通过）**：Qwen3-VL-4B Q4_K_M 2,497,281,664B；mmproj-Q8_0 453,974,304B；Qwen3-0.6B Q8_0 639,446,688B。下载源 hf-mirror.com（HF 直连不通）。

#### 3. 票内实际交付物（Git 中的模块）

- `engine/`：纯 Kotlin 提取引擎核心——Draft 契约（含日期口径标注）、`PhotoLedgerSchema`、`GrammarGenerator`（schema→GBNF）、`DraftNormalizer`（字段校验与口径归一）、`PromptBuilder`、`LlmTransport` 接口。依赖仅 kotlin-stdlib/coroutines/serialization（全 KMP 支持），无 JVM 专属依赖。22 个单元测试全绿。
- `engine-llamacpp/`：`LlamaServerProcess`（子进程管理）+ `LlamaServerTransport`（HTTP transport）。
- `cli/`：一条命令入口（`./run-cli.sh <截图...>` 或 `gradle :cli:run`），含 `--dump-grammar`（把 engine 生成的 GBNF 固化为文件，供桌面 Python 原型复用同一份文法）。

#### 4. 验收数据

- **VLM 路线（切换前）**：合成截图 3/3 全对（实付款口径、付款时间/下单时间退回、类别均正确）；真实截图连续跑因耗时中断（单张 prefill 3-5 分钟）。
- **OCR + 0.6B 路线（切换后，同 GBNF/engine）**：8/8 真实截图连续提取 **0 malformed**，全部通过 DraftNormalizer 校验；单张全程 3.5-7.5s（OCR 1.5-2.6s + LLM 3.0-5.5s，桌面 CPU）。
- **字段内容问题（留给 02，非本票范围）**：① OCR 行粘连导致日期畸形（如 0909-09-09）；② 小数点丢失致金额放大（1010 / 3500）；③ 多订单截图只取最后一单——一图多单策略需在 02 前定义；④ 0.6B 对畸形日期无纠偏力，1.7B 对比是 03 的内容。

#### 5. 环境备注

- WSL2 + Ubuntu 24.04，gcc 13.3，cmake 4.4.3（pip venv），Gradle 8.14（wrapper distributionUrl 已指向腾讯镜像，国内网络稳定）。
- llama-server CPU 模式加载 4B 约 60-90s，0.6B 约 2s。

### 2026-09-11 · 追加：0.6B vs 1.7B 对比实验（为 02/03 提供输入）

同一批 8 张真实截图、同一 grammar，Qwen3-0.6B Q8_0 vs Qwen3-1.7B Q8_0：

- 结构合法：两边都 8/8；LLM 耗时 0.6B 3.0-5.5s vs 1.7B 8.2-12.8s（2.3 倍）
- 字段质量：1.7B 修好金额一处、日期补全到时分秒，但**商家字段退化**（多张抓平台名「闪购」而非店名）、金额新引入两处错；**日期畸形（0909-09-09 类）两边同样存在**
- **结论**：瓶颈在 OCR 输入质量（日期/金额碎片化、行粘连），不在模型大小。票 03 主选定 0.6B（速度优先）；票 02 质量闸门的工作重心是 **OCR 后处理（行聚类 + 日期/金额规范化）**，那是确定性代码能解决的

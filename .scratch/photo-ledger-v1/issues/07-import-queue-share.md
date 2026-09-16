# 票 07：导入队列与分享入口——批量导入全链路 + 契约瘦身提速（17.4s → 约 10s/张）

**Status:** resolved

**Owner:** p-nickzhangq

**Created:** 2026-09（spec 期开票）

**Resolved:** 2026-09-16

## 交付内容

- **导入队列（S2 接缝）**：`data/ImportQueue.kt`，StateFlow 状态机 `Pending → Processing → Done | Duplicate | Failed`，Done → Confirmed（确认入账）。逐张顺序处理、单张失败不阻塞（Failed 回退哈希可重试）、内容哈希去重（构造时扫 photosDir 反推已导入 + 同批入队即记）。
- **两条入口**：相册多选（PickVisualMedia，列表页相册图标 + 空态按钮）+ 系统分享（SEND/SEND_MULTIPLE intent-filter）。真机分享入口受 `am` 无法授予 URI 读权限限制，smoke_import 旁路验证。
- **直接入账**：队列 Done 项点「确认」直接 `repo.confirm`（原图 bytes 落库），不经编辑页；卡片变绿显示「已入账，可在账目列表点开编辑」。
- **可核对性**：卡片带截图缩略图（Coil ByteArray，128px 截断解码），点击全屏预览（捏合 1-5x + 拖动）。
- **S2 测试**：9 条（顺序处理、失败不阻塞、同批/跨批去重、状态事件序列、失败保留字节、Confirmed 幂等）。

## 提速（同票顺带）：17.4s → 稳态 8.4-11s/张

1. **四字段契约**：模型只输出 merchant/amountPaid/datePaid/category（用户确认只要这四项）；currency=CNY、dateSource=PAYMENT_TIME、orderStatus="" 由引擎默认值承接。日期只到天（YYYY-MM-DD），grammar 删除时分秒规则。输出 token ~95→~55，decode 16s→8-10s。闸门 30/30 PASS（96.7%/100%，与基线持平）。
2. **队列引擎常驻**：transport 批内复用（原每张新建+销毁白付 ~4s 加载），第 2 张起零加载。
3. 真机实测（3 张批）：8.4s / 11.0s / 14.1s（首张含 4.7s 加载）。

## 顺带修复（非本票范围但同批验证）

- **schema 双重编码根因**：票 13 的「LLGuidance enum 双重编码 quirk」实为 `stripGbnfQuotes` 只剥一层引号，schema enum 值一直是 `"\"CNY\""`。转译上移 engine `GrammarGenerator.toJsonSchema`（App/CLI 去重），迭代剥壳 + 无日期范围规则守卫（原误解析产出非法 JSON——真机"无日期截图固定失败"的根因）。
- **金额形近字修复**：OCR 把「30」认成「3U」时模型瞎猜金额（真机 30.6→360.0）。`normalizeAmount` 改取货币符号后数字段（防「共N件」前缀污染）+ 货币段形近字修复（U/O→0、l/I→1 等）。
- **空日期策略**：闪购列表卡小角标日期 OCR 读取不稳定（乱码/漏识），无日期截图入账时自动填导入当天，卡片明示「无日期（入账时填今天）」。

## INT4 复测结论（本票期间深度排查，判定死路留观）

- `dynamic_wi4b32_afp32`（328MB）与 `qwen3_0_6b_mixed_int4`（475MB，TorchAO 路径）**两种转换管线在 0.17.0 + LLGuidance 约束解码下同样 30/30 失败**：`Parser Error: token "Ġ" doesn't satisfy the grammar`（模型提交语法非法 token，掩码失效）。INT8 同 schema 同分词器 30/30 过。
- 上游无解：GitHub 最新 release 即 0.17.0，Google Maven 无更新 AAR。#3577（GPU 偏离）与社区 scale-clamp 修复（smilingday/ai-edge-quantizer#1，blockwise scale float16 下溢）指向 quantizer 侧，但两种管线同死说明还有 runtime 侧掩码/包 tokenizer 兼容问题。
- 即便可用收益也有限：官方基准 mixed_int4 GPU decode 22.3 tok/s ≈ INT8 的 21（wi4b32 的 3× 是 GPU 图优化加成，被语法机挡死）。
- 后续重启条件：上游发新版 runtime 或修复版 quantizer 重转的官方 wi4b32 包。

**Blocked by 完成情况**：05 ✅ 06 ✅

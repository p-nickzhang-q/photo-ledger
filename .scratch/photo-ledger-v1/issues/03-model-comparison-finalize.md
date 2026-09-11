# 03: 双模型对比与 v1 模型定稿

**What to build:**
用 02 的 harness 以同一数据集评测备选模型 MiniCPM-V 4.5（int4/GGUF），与 Qwen3-VL-4B 的闸门报告对比（同 prompt、同 grammar、同口径），定稿 v1 使用的模型并更新 ADR-0002。

若 Qwen3-VL-4B 压倒性胜出，本票成本很小（一次跑分 + 一段 ADR 更新）；若两者接近或 MiniCPM 反超，则"胜者上机"原则确保 05 只集成胜者。

**Blocked by:** 02 (质量闸门评分与数据集)

**Status:** ready-for-agent

- [ ] MiniCPM-V 4.5 在同一数据集、同一 harness 下的闸门报告产出
- [ ] 两份报告并排对比（硬/软字段正确率、解码速度、内存占用）
- [ ] v1 模型定稿，ADR-0002 更新（含对比数据摘要）；败者方案记入 Comments 备查

# 票 27：商户记忆（已知商户本地匹配回填）

- Owner: agent（QoderCN）
- Created: 2026-09-23
- Status: Todo

## 背景 / 动机

2026-09 已把 merchant 移出模型输出（0.6B 抄写中文店名太弱：prompt 示例泄漏/
列表截断碎片/抄错行，真机连续出错），Draft.merchant 留空由用户详情页后补。
但用户反复在同一批商户消费，每次都手填是重复劳动。

调研 SpendTrace（参考实现已 clone 到 `.scratch/refs/SpendTrace`）：其
`MerchantExtractor` 仅两条正则 + 编译期静态 `CategoryDictionary`（200+ 商户
关键词），**没有「确认后商户入库」的学习闭环**。本票实现的是活的闭环，弥补
该缺口，且完全绕开 0.6B 的短板——不让模型抄写，本地匹配。

## 方案

1. Room 新表 `merchant_memory`（或等价）：`canonical`（用户确认的商户名）、
   `alias`（OCR 原文变体，可空）、`category`（可空，连分类一起学）、
   `hitCount` / `lastUsedAt`
2. 回填时机：用户在详情页确认/修改商户时写入（canonical + 该次命中的 OCR 行
   作 alias）；App 入账时也顺带记录手填值
3. 匹配时机：OCR 完成、LLM 前后皆可（建议 LLM 后、入 Draft 前——不占 LLM
   算力且 OCR 行此时已在内存）：对每条 OCR 行做本地匹配——alias/canonical
   的 contains 命中优先，未命中再做受限编辑距离（长度差 ≤2、距离 ≤2，防
   误匹配），几百条以内微秒级
4. 命中 → Draft.merchant 回填 + 复核页可见（用户可改）；未命中 → 维持现状
   留空。绝不静默写入用户不可见的内容
5. 匹配逻辑放 engine（纯 Kotlin，可单测），不进 app UI 层

## 非目标

- 不新增模型输出字段（契约三耦合不动）
- 不做云端词库同步（local-first，ADR-0003）

## 验收

- engine 单测：contains / 编辑距离 / 大小写与全半角归一 / 不误匹配
- 真机：同一商户第二次截图，merchant 自动回填且复核页可见可改
- 30 张闸门不受影响（本票不动 OCR/LLM 路径）

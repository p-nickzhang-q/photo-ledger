# 票 30：商户记忆类别联动（命中商户即得类别）

- Owner: agent（QoderCN）
- Created: 2026-09-23
- Status: In Progress（代码完成，versionCode 28 待装机验证——装机时设备掉线）

## 实现记录（2026-09-23）

- engine：`MerchantAlias` 加 `category: String? = null`；`matchDetail()` 返回
  命中别名对象（canonical+category），`match()` 委托之保持兼容。3 个新单测。
- 学习带类别：`learnMerchant(name, category)`——confirm 传确认页类别、edit 传
  编辑后类别、addManual 传手工类别；空/空白类别视为「不覆盖」，保留旧值；
  非空覆盖（最新确认优先）。
- 回填规则（OnDevicePipeline.fillMerchants）：命中且记忆类别 ∈ 当前类别列表
  （用户可能删过类，构造时逐张实时取的 categories 校验）→ 覆盖草稿类别；
  无效或缺失维持模型输出。复核页可见可改。
- 契约三耦合未动（类别仍是模型输出字段，仅命中记忆时被确定性值覆盖）。
- 验证：engine 3 + app 3 新单测全过（累计 engine 9 / app 31）。
- 遗留：versionCode 28 装机验证（设备掉线待重连）；票 31（LLM 兜底提商户）
  经评估不做——0.6B 抄写根因不变，兜底也抄错，收益存疑。

## 背景 / 动机

票 27 的 merchant_memory 表本就带 `category` 列（种子存了品牌→类别映射，当时
留作备用），但匹配命中只回填 merchant，类别仍靠 0.6B 猜。本票把类别一起联动：
匹配到商户 → 草稿类别直接用记忆里的（用户确认过的或种子映射的），比 0.6B
猜的可靠。

（用户同期提议的「无匹配时 LLM 兜底提商户」经评估不做：0.6B 抄写中文店名
太弱的根因不变，兜底也抄错；若将来要做必须带「输出必须是 OCR 行模糊子串」
的确定性校验 + 契约三耦合全动 + 闸门重跑，收益存疑，弃。）

## 方案

1. engine：`MerchantAlias` 加 `category: String? = null`；新增
   `matchDetail()` 返回命中的别名对象（含类别），`match()` 委托之保持兼容
2. 学习带类别：confirm / edit / addManual 三入口把确认的类别传给
   learnMerchant；重学时新类别非空则覆盖，空则保留旧值
3. 回填规则：命中记忆且记忆类别**在当前类别列表内**（用户可能删过类）→
   覆盖草稿类别；类别无效或缺失 → 维持模型输出。种子映射（SpendTrace 品牌名
   →内置八类）天然有效
4. 不动契约三耦合（Draft 字段、prompt、grammar 不变——类别仍是模型输出字段，
   只是命中记忆时被确定性值覆盖，复核页可见可改）

## 验收

- engine 单测：matchDetail 返回类别；match 兼容
- app 单测：学习带类别持久化、重学覆盖/保留、类别不存在于列表时不覆盖
- 闸门不受影响（LLM 路径无改动，闸门 CLI 不接记忆回填）
- 真机：命中商户的草稿类别直接正确（如沙县小吃→餐饮、蜜雪冰城→餐饮）

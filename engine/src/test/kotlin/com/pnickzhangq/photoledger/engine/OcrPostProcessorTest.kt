package com.pnickzhangq.photoledger.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OCR 后处理层测试。用例形态取自真实截图 OCR 输出（票 01 实验 + fixtures 快照）：
 * 日期碎片（「下单时间2026-09-10 11:19」+「09.10」角标、缺年份）、金额粘连（¥13.6、1010）、
 * 一图多单（两个订单卡片交错）。
 */
class OcrPostProcessorTest {

    // ---------- 日期规范化 ----------

    @Test
    fun `完整日期时间直接规范化`() {
        assertEquals("2026-09-10 11:19:00", OcrPostProcessor.normalizeDate("下单时间2026-09-10 11:19"))
        assertEquals("2026-09-10 20:15:33", OcrPostProcessor.normalizeDate("2026-09-10 20:15:33"))
    }

    @Test
    fun `只有日期没有时间时补零秒`() {
        assertEquals("2026-09-10 00:00:00", OcrPostProcessor.normalizeDate("下单时间 2026-09-10"))
    }

    @Test
    fun `OCR 碎片 MM点dd 缺年份时结合上下文年份补全`() {
        // 真实输出形态：主日期行 + 「09.10」角标行并存；角标往往缺年
        assertEquals(
            "2026-09-10 00:00:00",
            OcrPostProcessor.normalizeDate("09.10", contextYear = 2026),
        )
        assertEquals(
            "2026-09-03 00:00:00",
            OcrPostProcessor.normalizeDate("09.03", contextYear = 2026),
        )
    }

    @Test
    fun `年份缺位修复——OCR 丢行首数字时结合上下文年补全`() {
        // 真机案例（收银支付页）：「2026-09-17 20:08:46」被读成「026-09-17 20:08:46」，
        // 真日期解析失败后候选池只剩促销行日期，模型被迫输出错日期
        assertEquals(null, OcrPostProcessor.normalizeDate("026-09-17 20:08:46"))
        assertEquals(
            "2026-09-17 20:08:46",
            OcrPostProcessor.normalizeDate("026-09-17 20:08:46", contextYear = 2026),
        )
        assertEquals(
            "2026-09-17 00:00:00",
            OcrPostProcessor.normalizeDate("26-09-17", contextYear = 2026),
        )
        // 后缀不符不乱修（026 ≠ last3(2025)），宁可缺失
        assertEquals(null, OcrPostProcessor.normalizeDate("026-09-17", contextYear = 2025))
        assertEquals(null, OcrPostProcessor.normalizeDate("026-09-17"))
    }

    @Test
    fun `角标粘连后续文字时仍能提取 MM点dd`() {
        // 真实形态（淘宝/闪购列表页）：「09.09丨共4件（含包装/配送费）」「09.07|含包装/配送费实付款￥16.5」
        assertEquals(
            "2026-09-09 00:00:00",
            OcrPostProcessor.normalizeDate("09.09丨共4件（含包装/配送费）", contextYear = 2026),
        )
        assertEquals(
            "2026-09-07 00:00:00",
            OcrPostProcessor.normalizeDate("09.07|含包装/配送费实付款￥16.5", contextYear = 2026),
        )
    }

    @Test
    fun `中文日期格式转换`() {
        assertEquals("2026-09-10 00:00:00", OcrPostProcessor.normalizeDate("2026年9月10日"))
        assertEquals("2026-09-10 11:19:00", OcrPostProcessor.normalizeDate("2026年9月10日 11:19"))
    }

    @Test
    fun `MM-dd 缺年份（横杠形态）补上下文年份`() {
        assertEquals("2026-09-10 00:00:00", OcrPostProcessor.normalizeDate("09-10", contextYear = 2026))
    }

    @Test
    fun `无法识别的日期返回 null 而非猜测`() {
        // 0909-09-09 类畸形：宁可 null 让模型看到「无日期」，不给它喂畸形值
        assertEquals(null, OcrPostProcessor.normalizeDate("0909-09-09", contextYear = 2026))
        assertEquals(null, OcrPostProcessor.normalizeDate("", contextYear = 2026))
    }

    // ---------- 金额规范化 ----------

    @Test
    fun `¥ 前缀粘连剥离`() {
        assertEquals(13.6, OcrPostProcessor.normalizeAmount("￥13.6"))
        assertEquals(13.6, OcrPostProcessor.normalizeAmount("¥13.6"))
        assertEquals(25.60, OcrPostProcessor.normalizeAmount("实付款￥25.60"))
    }

    @Test
    fun `全角数字与逗号千分位`() {
        assertEquals(1234.5, OcrPostProcessor.normalizeAmount("１，２３４．５"))
        assertEquals(1234.5, OcrPostProcessor.normalizeAmount("1,234.5"))
    }

    @Test
    fun `千分位误判校正`() {
        // 真实痛点：OCR 把「10.10」丢小数点成「1010」；把「35.00」识别成「3500」。
        // 规则：4 位纯整数且不含小数点 → 视为「百位分隔丢失」，按两位小数解读（订单金额常见区间）
        assertEquals(10.10, OcrPostProcessor.normalizeAmount("1010"))
        assertEquals(35.00, OcrPostProcessor.normalizeAmount("3500"))
        // 但正常 4 位金额（如 3500 元整）不受影响的前提是有小数点或更长的整数段
        assertEquals(3500.0, OcrPostProcessor.normalizeAmount("3500.00"))
        assertEquals(12345.0, OcrPostProcessor.normalizeAmount("12345"))
    }

    @Test
    fun `无数字内容返回 null`() {
        assertEquals(null, OcrPostProcessor.normalizeAmount("含包装/配送费"))
        assertEquals(null, OcrPostProcessor.normalizeAmount(""))
    }

    @Test
    fun `订单流水号长数字串不当作金额`() {
        // 真实痛点：OCR 把 28 位订单号当文本行，金额提取必须剪枝
        assertEquals(null, OcrPostProcessor.normalizeAmount("4500000358202609107869151816"))
        assertEquals(null, OcrPostProcessor.normalizeAmount("20260910110113130266232759351292"))
        assertEquals(null, OcrPostProcessor.normalizeAmount("1234567"))
    }

    @Test
    fun `金额取货币符号后的数字段——前置计数不污染`() {
        // 真实形态（票 07 真机）：「共N件」前缀的旧逻辑会把 4/5 当金额候选污染 prompt
        assertEquals(
            30.6,
            OcrPostProcessor.normalizeAmount("共4件(含包装/配送费）实付款￥30.6"),
        )
        assertEquals(
            33.6,
            OcrPostProcessor.normalizeAmount("共5件(含包装/配送费）实付款￥33.6"),
        )
        assertEquals(
            16.5,
            OcrPostProcessor.normalizeAmount("09.07|含包装/配送费实付款￥16.5"),
        )
    }

    @Test
    fun `货币段形近字符修复——OCR 把 30 认成 3U`() {
        // 真实形态（票 07 真机 logcat）：「买付款￥3U.6」→ 修复为 30.6，模型不再拿到乱码
        assertEquals(
            30.6,
            OcrPostProcessor.normalizeAmount("共4件(含包装/配送费）买付款￥3U.6"),
        )
        assertEquals(30.6, OcrPostProcessor.normalizeAmount("实付款￥3O.6"))
        assertEquals(12.34, OcrPostProcessor.normalizeAmount("￥l2.34"))
        // 形近修复只作用于货币符号后的段：无符号行里的 U 不被当 0
        assertEquals(null, OcrPostProcessor.normalizeAmount("共U件"))
    }

    // ---------- 金额候选剪枝（真机回归：转账详情页 370 被提取成 794 的事故） ----------

    @Test
    fun `日期时间账号行的数字不进金额候选`() {
        val lines = listOf(
            line("2026-09-15 16:46:28", 0),
            line("10:41", 1),
            line("猪猪(郑怡) 544***@qq.com", 2),
            line("-370.00", 3),
            line("实付款¥14.5 2026-09-01", 4), // 带货币符号的行即使粘连日期也照常提金额
        )
        val m = OcrPostProcessor.buildStructuredMaterial(lines, 2026)
        // 日期照常归一
        assertTrue("2026-09-15 16:46:28" in m.normalizedDates)
        assertTrue("2026-09-01 00:00:00" in m.normalizedDates)
        // 真金额保留
        assertTrue(370.0 in m.amounts)
        assertTrue(14.5 in m.amounts)
        // 假金额不出现：2026 被 4 位整数规则修出的 20.26、时间 16/10、邮箱账号 544
        assertTrue(20.26 !in m.amounts)
        assertTrue(16.0 !in m.amounts)
        assertTrue(10.0 !in m.amounts)
        assertTrue(544.0 !in m.amounts)
    }

    @Test
    fun `时间行粘连碎片的数字不进金额候选——真机 51点6 被提取成 20`() {
        // 真机事故（09-21）：极简支付成功页 OCR 行「20:34 8」尾粘状态栏碎片，
        // TIME_ONLY 整行匹配拦不住，20 进了金额参考清单被 0.6B 抄走
        val lines = listOf(
            line("20:34 8", 0),
            line("支付成功", 1),
            line("格瑞思", 2),
            line("¥51.60", 3),
            line("完成", 4),
        )
        val m = OcrPostProcessor.buildStructuredMaterial(lines, 2026)
        assertTrue(20.0 !in m.amounts, "时间行数字不应进候选：${m.amounts}")
        assertEquals(listOf(51.6), m.amounts)
        // 货币符号金额单独成列（引擎锚定用）
        assertEquals(listOf(51.6), m.currencyAmounts)
    }

    @Test
    fun `卡号尾号裸4位不膨胀 负号行进强信号——真机 29点9 被提取成 12点12`() {
        // 真机事故（09-21）：支付宝账单详情页，真实金额「-29.90」无 ¥ 符号；
        // 「工商银行储蓄卡(1212）〉」的卡号尾号被 4 位 ÷100 规则膨胀成 12.12 进候选，
        // 模型抄走 12.12。修复：裸 4 位不膨胀；负号行与货币符号同级强信号。
        val lines = listOf(
            line("账单详情", 0),
            line("淘宝闪购", 1),
            line("-29.90", 2),
            line("交易成功", 3),
            line("支付时间", 4),
            line("2026-09-18 11:40:51", 5),
            line("付款方式", 6),
            line("工商银行储蓄卡(1212）〉", 7),
            line("商品说明", 8),
            line("七里弄堂生煎(光福店)外卖订单", 9),
            line("立即领取3积分", 10),
        )
        val m = OcrPostProcessor.buildStructuredMaterial(lines, 2026)
        assertTrue(12.12 !in m.amounts, "卡号尾号不应膨胀成 12.12：${m.amounts}")
        assertTrue(1212.0 !in m.amounts, "账号尾号行整体不进候选：${m.amounts}")
        assertTrue(29.9 in m.amounts)
        assertEquals(listOf(29.9), m.currencyAmounts, "负号行应与货币符号同级进强信号")
    }

    @Test
    fun `首个独立两位小数行进强信号——负号被 OCR 丢失的账单金额`() {
        // 真机事故（09-21）：微信账单详情「-15.40」负号没读出来，金额成无符号裸数字，
        // 模型抄状态栏「89」生成天文数字。区块首个「整数.两位小数」行 = 卡片头部金额。
        val lines = listOf(
            line("11:14 @", 0),
            line("淘", 1),
            line("接单", 3),
            line("89", 4),
            line("淘宝平台商户「", 8),
            line("15.40", 9),
            line("当前状态", 10),
            line("支付成功", 11),
            line("支付时间", 12),
            line("2026年9月21日11:12:48", 13),
            line("4500000428202609219922282793", 21),
        )
        val m = OcrPostProcessor.buildStructuredMaterial(lines, 2026)
        assertEquals(listOf(15.4), m.currencyAmounts, "首个独立两位小数行应为强信号")
        assertTrue(15.4 in m.amounts)
        assertTrue(89.0 in m.amounts, "裸整数仍有候选背书（但无强信号）")
    }

    @Test
    fun `货币金额候选——多符号行按出现序去重`() {
        val lines = listOf(
            line("商品总价 ￥79.00", 0),
            line("优惠 -¥27.40", 1),
            line("实付款 ¥51.60", 2),
        )
        val m = OcrPostProcessor.buildStructuredMaterial(lines, 2026)
        assertEquals(listOf(79.0, 27.4, 51.6), m.currencyAmounts)
    }

    // ---------- 金额锚定兜底（引擎层） ----------

    @Test
    fun `锚定兜底——模型输出无候选背书时替换为唯一货币金额`() {
        val material = OcrPostProcessor.StructuredMaterial(
            blockText = "20:34 8\n支付成功\n格瑞思\n¥51.60\n完成",
            normalizedDates = emptyList(),
            amounts = listOf(51.6), // 20 已被时间行过滤，不背书
            currencyAmounts = listOf(51.6),
        )
        val draft = Draft(amountPaid = 20.0, datePaid = "", category = "餐饮")
        val anchored = OcrPostProcessor.anchorAmount(draft, material)
        assertEquals(51.6, anchored.amountPaid, 0.001)
    }

    @Test
    fun `锚定兜底——模型输出有候选背书时不干预`() {
        val material = OcrPostProcessor.StructuredMaterial(
            blockText = "实付款 20\n¥51.60",
            normalizedDates = emptyList(),
            amounts = listOf(20.0, 51.6),
            currencyAmounts = listOf(51.6),
        )
        val draft = Draft(amountPaid = 20.0, datePaid = "", category = "餐饮")
        assertEquals(20.0, OcrPostProcessor.anchorAmount(draft, material).amountPaid, 0.001)
    }

    @Test
    fun `锚定兜底——多个货币金额时不干预`() {
        val material = OcrPostProcessor.StructuredMaterial(
            blockText = "￥79.00\n实付款 ¥51.60",
            normalizedDates = emptyList(),
            amounts = listOf(79.0, 51.6),
            currencyAmounts = listOf(79.0, 51.6),
        )
        val draft = Draft(amountPaid = 79.0, datePaid = "", category = "餐饮")
        assertEquals(79.0, OcrPostProcessor.anchorAmount(draft, material).amountPaid, 0.001)
    }

    // ---------- 行文本清理（真机回归：一图两单商家行被抄进 UI 符号/截断碎片） ----------

    @Test
    fun `行文本清理——行尾箭头剥掉中段截断取前段`() {
        assertEquals("徐乔乔辣子鸡手擀面光福店", OcrPostProcessor.cleanLineText("徐乔乔辣子鸡手擀面光福店>"))
        assertEquals("沪上阿姨", OcrPostProcessor.cleanLineText("沪上阿姨····苏"))
        // 截断后带数字——可能是金额行，不动
        assertEquals("商品····￥10", OcrPostProcessor.cleanLineText("商品····￥10"))
        // 普通行不受影响（日期小数点不误伤）
        assertEquals("2026-09-15 16:46:28", OcrPostProcessor.cleanLineText("2026-09-15 16:46:28"))
        assertEquals("￥15.1", OcrPostProcessor.cleanLineText("￥15.1"))
    }

    @Test
    fun `锚定收口——促销日期出局，标签行认领的交易日期进候选`() {
        // 真机案例（收银支付页）：真日期被 OCR 切掉年份（026-09-17），修复后与促销行
        // 「活动时间：2026年4月1日」并存，模型挑了促销日期。候选收窄到锚定日期。
        val lines = listOf(
            line("支付成功", 0),
            line("￥39.5", 1),
            line("下单时间：", 2),
            line("026-09-17 20:08:46", 3),
            line("活动时间：2026年4月1日-12月31日（每日00:00:00-23:59:59)", 4),
        )
        val m = OcrPostProcessor.buildStructuredMaterial(lines, 2026)
        assertEquals(listOf("2026-09-17 20:08:46"), m.normalizedDates)
        assertEquals(39.5, m.amounts.single(), 0.001)
    }

    @Test
    fun `无锚定标签时回退全部日期候选`() {
        val lines = listOf(
            line("2026-09-01", 0),
            line("2026-09-02", 1),
        )
        val m = OcrPostProcessor.buildStructuredMaterial(lines, 2026)
        assertEquals(listOf("2026-09-01 00:00:00", "2026-09-02 00:00:00"), m.normalizedDates)
    }

    @Test
    fun `消费时间也是锚定标签`() {
        // 真机（乡村基券页）：「消费时间：2026-09-17」——此前不在关键词表，靠候选唯一侥幸正确
        val lines = listOf(
            line("乡村基·川菜小炒", 0),
            line("消费时间：2026-09-17", 1),
            line("￥20.88", 2),
        )
        val m = OcrPostProcessor.buildStructuredMaterial(lines, 2026)
        assertEquals(listOf("2026-09-17 00:00:00"), m.normalizedDates)
        assertEquals(20.88, m.amounts.single(), 0.001)
    }

    // ---------- 行聚类：一图多单 ----------

    private fun line(text: String, y: Int, x: Int = 70) = OcrLine(
        text = text, score = 0.99f,
        box = listOf(x.toFloat(), y.toFloat(), (x + 100).toFloat(), y.toFloat(),
            (x + 100).toFloat(), (y + 40).toFloat(), x.toFloat(), (y + 40).toFloat()),
    )

    @Test
    fun `按实付款关键词切分多订单区块`() {
        // 真实形态：两个订单卡片，各有「实付款￥xx」行；行序按 y 排列
        val lines = listOf(
            line("如意馄饨·干拌面光福店", y = 800),
            line("商家备餐中", y = 900),
            line("含包装/配送费实付款￥13.6", y = 1000),
            line("华莱士·全鸡汉堡光福2店", y = 1400),
            line("商家备餐中", y = 1500),
            line("含包装/配送费实付款￥17.1", y = 1600),
        )
        val blocks = OcrPostProcessor.splitOrderBlocks(lines)
        assertEquals(2, blocks.size)
        assertTrue(blocks[0].any { "如意馄饨" in it.text })
        assertTrue(blocks[1].any { "华莱士" in it.text })
        assertTrue(blocks[0].any { "13.6" in it.text })
    }

    @Test
    fun `单订单整图一个区块`() {
        val lines = listOf(line("顶足便利店", y = 300), line("实付款￥26", y = 900))
        val blocks = OcrPostProcessor.splitOrderBlocks(lines)
        assertEquals(1, blocks.size)
        assertEquals(2, blocks[0].size)
    }

    @Test
    fun `区块内行按阅读顺序排列`() {
        val lines = listOf(
            line("第二行", y = 2000),
            line("第一行", y = 1000),
            line("第一行右", y = 1000, x = 500),
        )
        val blocks = OcrPostProcessor.splitOrderBlocks(lines)
        val texts = blocks[0].map { it.text }
        assertEquals(listOf("第一行", "第一行右", "第二行"), texts)
    }

    // ---------- 噪音行丢弃（票 23，用例取自真机 10 张转储） ----------

    @Test
    fun `微信账单详情——动作区行丢弃，核心字段保留`() {
        val lines = listOf(
            line("全部账单", y = 200),
            line("围炉锅盔金鹰店", y = 300),
            line("-9.90", y = 400),
            line("当前状态", y = 500),
            line("支付成功", y = 550),
            line("支付时间", y = 600),
            line("2026年9月13日19:19:25", y = 650),
            line("商品", y = 700),
            line("Mobile Pay", y = 750),
            line("交易单号", y = 800),
            line("4200003148202609134166711260", y = 850),
            line("账单服务", y = 900),
            line("对订单有疑惑", y = 950),
            line("发起群收款", y = 1000),
            line("申请电子凭证", y = 1050),
        )
        val trimmed = OcrPostProcessor.trimNoiseTail(lines)
        assertTrue(trimmed.none { "账单服务" in it.text || "发起群收款" in it.text || "申请电子" in it.text || "对订单有疑惑" in it.text })
        // 核心字段保留
        assertTrue(trimmed.any { it.text == "-9.90" })
        assertTrue(trimmed.any { it.text.startsWith("2026年9月13日") })
        // 单号标签与长数字串无关键词不丢弃（金额归一层自行剪枝，代价只是两行 token）
        assertTrue(trimmed.any { it.text.startsWith("4200") })
    }

    @Test
    fun `支付宝账单详情——运营位行丢弃`() {
        val lines = listOf(
            line("账单详情", y = 200),
            line("淘宝闪购", y = 300),
            line("-29.90", y = 400),
            line("支付时间", y = 500),
            line("2026-09-18 11:40:51", y = 550),
            line("商品说明", y = 600),
            line("七里弄堂生煎(光福店)外卖订单", y = 650),
            line("支付奖励", y = 700),
            line("立即领取3积分", y = 750),
            line("为您推荐", y = 800),
            line("外卖+", y = 850),
            line("备注", y = 900),
            line("再转一笔", y = 950),
        )
        val trimmed = OcrPostProcessor.trimNoiseTail(lines)
        assertTrue(trimmed.none { "积分" in it.text || "再转一笔" in it.text || "为您推荐" in it.text })
        assertTrue(trimmed.any { it.text == "-29.90" })
        assertTrue(trimmed.any { it.text.startsWith("七里弄堂生煎") })
    }

    @Test
    fun `收银台详情——广告促销行丢弃`() {
        val lines = listOf(
            line("支付成功", y = 200),
            line("实付：", y = 300),
            line("￥39.5", y = 350),
            line("商户名称：", y = 400),
            line("顶足便利店", y = 450),
            line("下单时间：", y = 500),
            line("2026-09-1720:08:46", y = 550),
            line("广告", y = 600),
            line("微信代金券", y = 650),
            line("活动时间：2026年4月1日：12月31日（每日00:00:00-23:59:59）", y = 700),
        )
        val trimmed = OcrPostProcessor.trimNoiseTail(lines)
        assertTrue(trimmed.none { it.text.startsWith("活动时间") || "代金券" in it.text || it.text == "广告" })
        assertTrue(trimmed.any { it.text == "￥39.5" })
        assertTrue(trimmed.any { it.text.startsWith("2026-09-17") })
    }

    @Test
    fun `支付消息列表页——两张订单卡都不丢（真机 14点30 案例回归）`() {
        val lines = listOf(
            line("服务消息", y = 200),
            line("9月统计支出", y = 300),
            line("淘宝闪购", y = 400),
            line("9月18日11:40", y = 450),
            line("￥29.90", y = 500),
            line("付款方式工商银行储蓄卡(1212）", y = 550),
            line("支付奖励", y = 600),
            line("+3积分丨9积分+19.9元兑异环联名帆布袋〉", y = 650),
            line("淘宝闪购", y = 700),
            line("9月17日11:12", y = 750),
            line("￥15.10", y = 800),
        )
        val trimmed = OcrPostProcessor.trimNoiseTail(lines)
        // 运营位行出局，但两张订单卡的金额/日期行保留
        assertTrue(trimmed.none { "积分" in it.text || "支付奖励" in it.text })
        assertTrue(trimmed.any { it.text == "￥29.90" })
        assertTrue(trimmed.any { it.text == "￥15.10" })
        assertTrue(trimmed.any { it.text.startsWith("9月18日") })
        assertTrue(trimmed.any { it.text.startsWith("9月17日") })
    }

    @Test
    fun `无噪音词与空行列表——原样返回（按阅读顺序）`() {
        val lines = listOf(
            line("支付成功", y = 400),
            line("￥10.00", y = 200),
        )
        val trimmed = OcrPostProcessor.trimNoiseTail(lines)
        assertEquals(listOf("￥10.00", "支付成功"), trimmed.map { it.text })
        assertEquals(emptyList<OcrLine>(), OcrPostProcessor.trimNoiseTail(emptyList()))
    }

    // ---------- 端到端：OCR 行 → 结构化 prompt 素材 ----------

    @Test
    fun `结构化素材含规范化日期与金额提示`() {
        val lines = listOf(
            line("老王川菜馆", y = 800),
            line("下单时间2026-09-09 11:42", y = 900),
            line("含包装/配送费实付款￥23.8", y = 1000),
            line("已完成", y = 1100),
        )
        val material = OcrPostProcessor.buildStructuredMaterial(lines)
        assertTrue("2026-09-09 11:42:00" in material.normalizedDates || "2026-09-09 11:42" in material.normalizedDates)
        assertTrue(material.amounts.contains(23.8))
        assertTrue(material.blockText.isNotEmpty())
    }

    // ---------- 票 28-A：金额候选打分 ----------

    @Test
    fun `候选打分——实付款行居首，原价优惠被惩罚`() {
        val lines = listOf(
            line("原价 ￥128.00", 0),
            line("优惠券 -¥27.40", 1),
            line("实付款 ¥51.60", 2),
        )
        val m = OcrPostProcessor.buildStructuredMaterial(lines, 2026)
        val top = m.amountCandidates.first()
        assertEquals(51.6, top.value, 0.001)
        // 货币符号 +40、实付关键词 +30
        assertEquals(70, top.score)
        // 原价/券行：货币符号 +40 但惩罚 -50，必须排在实付款之后
        assertTrue(m.amountCandidates.first { it.value == 128.0 }.score < 0)
        assertTrue(m.amountCandidates.first { it.value == 27.4 }.score < 0)
    }

    @Test
    fun `候选打分——标签在上一行时同样加分`() {
        // 支付宝账单形态：「付款金额」标签行独立成行，金额在下一行
        val lines = listOf(
            line("付款金额", 0),
            line("98.00", 1),
            line("完成", 2),
        )
        val m = OcrPostProcessor.buildStructuredMaterial(lines, 2026)
        val top = m.amountCandidates.first()
        assertEquals(98.0, top.value, 0.001)
        // 独立两位小数 +25、上一行「付款金额」+30
        assertEquals(55, top.score)
    }

    @Test
    fun `候选打分——同值合并取高分`() {
        val lines = listOf(
            line("原价￥33.03", 0),
            line("实付款￥33.03", 1),
        )
        val m = OcrPostProcessor.buildStructuredMaterial(lines, 2026)
        val c = m.amountCandidates.single()
        assertEquals(33.03, c.value, 0.001)
        assertEquals(70, c.score, "惩罚版（-10）应被实付款版（70）覆盖")
    }

    // ---------- 票 28-B：后置交叉验证 ----------

    @Test
    fun `needsReview——金额不在候选集时触发`() {
        val material = OcrPostProcessor.StructuredMaterial(
            blockText = "",
            normalizedDates = emptyList(),
            amounts = listOf(14.5),
            currencyAmounts = listOf(14.5),
        )
        // order_12 形态：模型输出 14.0，候选集只有 14.5
        val draft = Draft(amountPaid = 14.0, datePaid = "2026-09-01", category = "其他")
        assertTrue(OcrPostProcessor.needsReview(draft, material, listOf("2026-09-01")))
    }

    @Test
    fun `needsReview——金额与日期都在候选集时不触发`() {
        val material = OcrPostProcessor.StructuredMaterial(
            blockText = "",
            normalizedDates = emptyList(),
            amounts = listOf(14.5),
            currencyAmounts = listOf(14.5),
        )
        val draft = Draft(amountPaid = 14.5, datePaid = "2026-09-01", category = "其他")
        assertTrue(!OcrPostProcessor.needsReview(draft, material, listOf("2026-09-01")))
    }

    @Test
    fun `needsReview——日期候选存在却输出空日期时触发`() {
        val material = OcrPostProcessor.StructuredMaterial(
            blockText = "",
            normalizedDates = emptyList(),
            amounts = listOf(14.5),
            currencyAmounts = listOf(14.5),
        )
        val draft = Draft(amountPaid = 14.5, datePaid = "", category = "其他")
        assertTrue(OcrPostProcessor.needsReview(draft, material, listOf("2026-09-01")))
    }

    @Test
    fun `needsReview——无日期候选时空日期合法`() {
        val material = OcrPostProcessor.StructuredMaterial(
            blockText = "",
            normalizedDates = emptyList(),
            amounts = listOf(14.5),
            currencyAmounts = listOf(14.5),
        )
        val draft = Draft(amountPaid = 14.5, datePaid = "", category = "其他")
        assertTrue(!OcrPostProcessor.needsReview(draft, material, emptyList()))
    }
}

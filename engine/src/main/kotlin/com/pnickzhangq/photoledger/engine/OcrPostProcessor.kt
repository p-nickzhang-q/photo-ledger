package com.pnickzhangq.photoledger.engine

/**
 * OCR 输出行（协议见 docs/design/ocr-protocol.md）。
 * box 为四点顺时针多边形的扁平坐标 [x1,y1, x2,y2, x3,y3, x4,y4]。
 */
data class OcrLine(
    val text: String,
    val score: Float,
    val box: List<Float>,
) {
    /** 行顶部 y（用于阅读顺序排序）。 */
    val top: Float get() = box.getOrNull(1) ?: 0f

    /** 行左侧 x（同 y 时先左后右）。 */
    val left: Float get() = box.getOrNull(0) ?: 0f
}

/**
 * OCR 后处理层（票 12）：把 OCR 文本行整理成结构化 prompt 的干净素材。
 * 全部为确定性逻辑——日期/金额碎片修复是票 01 实验证实的质量瓶颈，
 * 在源头解决而不是靠模型猜。测试用例取自真实截图 OCR 输出。
 */
object OcrPostProcessor {

    /**
     * OCR 行进 prompt 的最低置信度。正常文本行普遍 >0.9；低于此值的碎片
     * （状态栏数字、误识别块）会带偏 0.6B 的金额选择——真机案例：0.57 的状态栏
     * 「794」被当成金额，370 元转账页提取成 794。
     * 阈值 0.78（票 20）：0.7 时真机状态栏「89」(0.73) 过线，模型抄走后生成
     * 200 多位零的天文数字撑爆输出。0.85 会误伤拍摄照片里的正常行
     * （order_12 哨兵图 100%→96.7% 回归实测），0.78 分界兼顾两侧。
     * 过滤放在 extractFromOcr 入口。
     */
    const val MIN_LINE_SCORE = 0.78f

    // ---------- 日期规范化 ----------

    private val FULL_DT = Regex("""(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?\s*(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    private val DATE_ONLY = Regex("""(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?""")
    private val SHORT_DOT = Regex("""^(\d{1,2})\.(\d{1,2})(?!\.)""")   // 「09.10」角标形态（可粘连后续文字，如「09.09丨共4件」）
    private val SHORT_DASH = Regex("""^(\d{1,2})-(\d{1,2})(?![-\d])""")   // 「09-10」形态（不吞日期段后续）
    private val MD_HM = Regex("""^(\d{1,2})[-/.](\d{1,2})\s+(\d{1,2}):(\d{2})$""") // 「09-10 11:19」缺年
    private val TIME_ONLY = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")   // 「10:41」「16:46:28」状态栏/角标时间
    private val TIME_PREFIX = Regex("""^\d{1,2}:\d{2}\D""")   // 「20:34 8」时间粘连状态栏碎片（真机 51.6 被提取成 20）

    /**
     * 年份缺位修复（真机：行首数字被屏幕边缘切掉，「2026-09-17 20:08:46」读成「026-09-17 …」）。
     * 2-3 位年 + 合法月日，且与 contextYear 后缀一致才修（「026」== last3(2026)）；
     * 上下文年缺失或后缀不符一律返回 null——「宁可缺失不乱猜」口径不变。
     */
    private val SHORT_YEAR_DT = Regex("""(\d{2,3})[-/.](\d{1,2})[-/.月](\d{1,2})日?(?:\s+(\d{1,2}):(\d{2})(?::(\d{2}))?)?""")

    /** 交易日期锚定标签：这些标签行（本行或紧邻上一行）后面的日期才是交易日期。 */
    private val DATE_ANCHOR_KEYWORDS = listOf("下单时间", "付款时间", "支付时间", "交易时间", "创建时间", "成交时间", "消费时间")

    /**
     * 日期规范化为「YYYY-MM-DD HH:MM:SS」。识别不了返回 null（宁可缺失不给模型喂畸形值，
     * 0909-09-09 类畸形由本函数在源头拦截）。
     */
    fun normalizeDate(raw: String, contextYear: Int? = null): String? {
        val s = raw.trim().replace("　", " ")
        FULL_DT.find(s)?.let { m ->
            val (y, mo, d, h, mi, sec) = m.destructured
            val secVal = if (sec.isEmpty()) "00" else sec
            if (!isValidDate(y.toInt(), mo.toInt(), d.toInt())) return null
            return "%04d-%02d-%02d %02d:%02d:%02d".format(
                y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), secVal.toInt(),
            )
        }
        DATE_ONLY.find(s)?.let { m ->
            val (y, mo, d) = m.destructured
            if (!isValidDate(y.toInt(), mo.toInt(), d.toInt())) return@let
            return "%04d-%02d-%02d 00:00:00".format(y.toInt(), mo.toInt(), d.toInt())
        }
        SHORT_YEAR_DT.find(s)?.let { m ->
            val yPart = m.groupValues[1]
            val year = contextYear
            if (year == null || !year.toString().endsWith(yPart)) return@let
            val mo = m.groupValues[2].toInt()
            val d = m.groupValues[3].toInt()
            if (!isValidDate(year, mo, d)) return@let
            val h = m.groupValues[4].ifEmpty { "0" }.toInt()
            val mi = m.groupValues[5].ifEmpty { "0" }.toInt()
            val sec = m.groupValues[6].ifEmpty { "0" }.toInt()
            return "%04d-%02d-%02d %02d:%02d:%02d".format(year, mo, d, h, mi, sec)
        }
        MD_HM.find(s)?.let { m ->
            val (mo, d, h, mi) = m.destructured
            val year = contextYear ?: return null
            if (!isValidDate(year, mo.toInt(), d.toInt())) return@let
            return "%04d-%02d-%02d %02d:%02d:00".format(year, mo.toInt(), d.toInt(), h.toInt(), mi.toInt())
        }
        val dot = SHORT_DOT.find(s)
        if (dot != null) {
            val (mo, d) = dot.destructured
            val year = contextYear ?: return null
            if (!isValidDate(year, mo.toInt(), d.toInt())) return null
            return "%04d-%02d-%02d 00:00:00".format(year, mo.toInt(), d.toInt())
        }
        SHORT_DASH.find(s)?.let { m ->
            val (mo, d) = m.destructured
            val year = contextYear ?: return null
            if (!isValidDate(year, mo.toInt(), d.toInt())) return@let
            return "%04d-%02d-%02d 00:00:00".format(year, mo.toInt(), d.toInt())
        }
        return null
    }

    private fun isValidDate(year: Int, month: Int, day: Int): Boolean =
        year in 2000..2100 && month in 1..12 && day in 1..31

    // ---------- 金额规范化 ----------

    private val NUM = Regex("""[0-9０-９][0-9０-９，,．.]*(\.[0-9０-９]+)?""")

    /** 负号开头（可带货币符号）的行：账单详情页的金额形态（「-29.90」无 ¥ 符号），强信号。 */
    private val NEG_AMOUNT = Regex("""^-\s*[¥￥]?[0-9０-９]""")

    /**
     * 括号内 2-6 位数字：账号尾号形态（「储蓄卡(1212)」「(5447)」）。真机票 19：
     * 卡号尾号 1212 作为裸值进候选，模型抄走当金额。这类数字永远不是实付款。
     */
    private val ACCT_TAIL = Regex("""[（(][0-9０-９]{2,6}[)）]""")

    /**
     * 「数字/数字」进度形态（真机 2026-09-23：「100/100元」券进度条被 0.6B 抄走当
     * 实付款 100）。真付款金额不含这种形态，整行踢出候选。
     */
    private val PROGRESS_NUM = Regex("""[0-9０-９]+\s*/\s*[0-9０-９]+""")

    /**
     * 独立「整数.两位小数」行：账单详情页金额的通用形态（微信「15.40」负号被 OCR
     * 丢失、支付宝「-29.90」、转账「-370.00」都命中）。区块内首个该形态行视为强信号——
     * 两个 App 都把交易金额放在卡片头部、标签行之前。
     */
    private val AMOUNT_STANDALONE = Regex("""^[+-]?[0-9０-９]{1,7}\.[0-9０-９]{2}$""")

    /** 形近字符→数字（小字号 OCR 常见误读）。仅用于货币符号后的金额段，不碰普通文本。 */
    private val DIGIT_LOOKALIKES = mapOf(
        'O' to '0', 'o' to '0', 'U' to '0', 'D' to '0', 'Q' to '0',
        'l' to '1', 'I' to '1', 'i' to '1', '|' to '1',
        'Z' to '2', 'z' to '2',
        'A' to '4',
        'S' to '5', 's' to '5',
        'b' to '6', 'G' to '6',
        'B' to '8',
        'g' to '9', 'q' to '9',
    )

    /**
     * 从文本片段提取实付款金额。规则：
     * - 行内含货币符号（￥/¥）时取【符号后】的数字段：实付金额总是跟在符号后，
     *   避免「共4件」类前置计数污染候选；段内先做形近字符修复（票 07 真机：OCR
     *   把「30」认成「3U」，旧逻辑只能给模型喂乱码导致金额瞎猜）
     * - 剥 ¥/￥ 前缀、全角转半角、千分位逗号去除
     * - 4 位纯整数且无小数点 → 视为小数点丢失（OCR 把 10.10 认成 1010），按两位小数解读；
     *   仅在 [allowDecimalRestore]（默认开，货币符号行）时生效——裸 4 位数字（卡号尾号
     *   「储蓄卡(1212)」）膨胀成 12.12 会污染候选池（票 19 真机：29.9 被提取成 12.12）；
     *   更长整数段（≥5 位）或带小数点的不动
     * - 找不到数字返回 null
     */
    fun normalizeAmount(raw: String, allowDecimalRestore: Boolean = true): Double? {
        val symbolIdx = maxOf(raw.lastIndexOf('￥'), raw.lastIndexOf('¥'))
        val source = if (symbolIdx >= 0) {
            buildString {
                for (c in raw.substring(symbolIdx + 1)) append(DIGIT_LOOKALIKES[c] ?: c)
            }
        } else {
            raw
        }
        val m = NUM.find(source) ?: return null
        val s = m.value
            .replace("０", "0").replace("１", "1").replace("２", "2").replace("３", "3")
            .replace("４", "4").replace("５", "5").replace("６", "6").replace("７", "7")
            .replace("８", "8").replace("９", "9")
            .replace("，", ",").replace("．", ".")
            .replace(",", "")
        if (s.isEmpty()) return null
        val parsed = s.toDoubleOrNull() ?: return null
        // 订单流水号/日期串是长数字序列，长于 6 位整数不可能是实付款；先剪枝再谈小数点修复
        if (!s.contains('.') && s.length > 6) return null
        // 纯 4 位整数无小数点：订单实付常见区间 (<1000) 下 4 位整数极罕见，小数点丢失更可能；
        // 只对货币符号行启用（裸 4 位数字多为卡号尾号/序号，膨胀反而造假金额）
        if (allowDecimalRestore && !s.contains('.') && s.length == 4 && parsed >= 1000) {
            return parsed / 100.0
        }
        // 实付款合理性边界（个人消费）：0 < x < 1,000,000
        if (parsed <= 0.0 || parsed >= 1_000_000.0) return null
        return parsed
    }

    // ---------- 行聚类：一图多单 ----------

    /**
     * 按「实付款」关键词切分订单区块：每个实付行结束一个区块（区块含其实付行及之前的行）。
     * 无实付关键词时整图一个区块。区块内行按阅读顺序（y 升序，同 y 先左）。
     */
    fun splitOrderBlocks(lines: List<OcrLine>): List<List<OcrLine>> {
        val ordered = lines.sortedWith(compareBy({ it.top }, { it.left }))
        val paidIdx = ordered.indices.filter { isPaidAnchor(ordered[it].text) }
        if (paidIdx.isEmpty()) return listOf(ordered)

        val blocks = mutableListOf<List<OcrLine>>()
        var start = 0
        for (pi in paidIdx) {
            // 实付行及其后的状态行（如「联系商家」）归本区块：取到下一个实付行前 2 行内
            val end = pi
            blocks.add(ordered.subList(start, end + 1).toList())
            start = end + 1
        }
        // 尾部残留（下一个区块的商家行等）并入前一块之后不再有实付行 → 丢弃或归最后块？归最后块
        if (start < ordered.size && blocks.isNotEmpty()) {
            val last = blocks.removeAt(blocks.size - 1)
            blocks.add(last + ordered.subList(start, ordered.size))
        }
        return blocks
    }

    /**
     * 实付锚定行：实付类关键词后必须紧跟金额（冒号/货币符号可隔）——「实付款￥33.03」
     * 「总优惠￥2.8实付￥28」是订单边界，「实付满15即可计入任务进度」这类促销文案不是
     * （真机 2026-09-23：饿了么订单详情页促销行被当边界，单订单拆成两单，LLM 白跑一遍）。
     */
    private val PAID_ANCHOR = Regex("(?:实付款?|付款金额|支付金额)[：:]?\\s*[￥￥]?\\s*\\d")

    private fun isPaidAnchor(text: String): Boolean = PAID_ANCHOR.containsMatchIn(text.replace(" ", ""))

    // ---------- 噪音行丢弃（票 23 提速） ----------

    /**
     * 噪音关键词：支付页里对账目提取零价值的 UI 动作/运营位行（真机 10 张转储归纳）。
     * 账单详情页下半屏全是这类行（27-37 行里占 10-15 行），进 prompt 只费 token；
     * 「立即领取3积分」的 3、「活动时间：2026年4月…」的促销日期还会污染金额/日期候选。
     * 核心字段（金额/支付时间/商品/商户简称）行不含这些词。
     */
    private val NOISE_LINE_KEYWORDS = listOf(
        // 账单详情动作区（微信）
        "账单服务", "对订单有疑惑", "对订单有疑问", "发起群收款", "在此商户的交易",
        "申请电子", "往来流水", "往来记录", "AA收款", "联系商家", "由财付通提供",
        // 账单详情运营位（支付宝/微信）
        "支付奖励", "积分", "领取", "消费图鉴", "账单管理", "贴纸", "账单分类",
        "请选择", "为您推荐", "再转一笔", "备注",
        // 广告/促销（收银台等）
        "广告", "代金券", "活动时间", "名额有限", "先到先得", "具体规则", "小程序", "登录",
        // 促销挑战/活动（真机 2026-09-23 饿了么订单详情页：「实付满15即可计入任务进度」
        // 这类行既含「实付」又含数字，进候选会污染金额，进边界会假拆单）
        "多单挑战", "完成挑战", "任务进度", "去领奖", "急送券",
        // 列表/杂项
        "标签", "查看详情", "查看更多", "摇一摇", "外卖+", "下馆子",
    )

    /**
     * 逐行丢弃噪音行（阅读顺序排序后返回其余行）。
     * 不做「首个分界词起整页截断」——支付消息列表页里「支付奖励」会出现在
     * 第二张订单卡上方，截断会误杀后续订单卡的内容（真机 2026-09-21 14:30 案例）。
     */
    fun trimNoiseTail(lines: List<OcrLine>): List<OcrLine> {
        if (lines.isEmpty()) return lines
        val ordered = lines.sortedWith(compareBy({ it.top }, { it.left }))
        return ordered.filter { l ->
            val text = l.text.replace(" ", "")
            NOISE_LINE_KEYWORDS.none { it in text }
        }
    }

    // ---------- 端到端素材 ----------

    /**
     * 结构化 prompt 素材：区块文本（按阅读顺序）+ 规范化日期集合 + 金额集合。
     * 供 PromptBuilder 生成「给模型看见干净数据」的 prompt。
     */
    data class StructuredMaterial(
        val blockText: String,
        val normalizedDates: List<String>,
        val amounts: List<Double>,
        /** 带 ¥/￥ 货币符号行的金额——金额的最强信号，供引擎锚定兜底（anchorAmount）。 */
        val currencyAmounts: List<Double>,
        /**
         * 票 28-A：金额候选按上下文打分降序（同分保持阅读顺序）。分值来源：
         * 货币符号行 +40、负号行 +35、区块首个独立两位小数行 +25、
         * 实付类关键词（本行或紧邻上一行）+30、优惠/原价类 -50。
         */
        val amountCandidates: List<AmountCandidate> = emptyList(),
    )

    /** 票 28-A：带打分的金额候选（sourceLine 供调试与测试定位）。 */
    data class AmountCandidate(
        val value: Double,
        val score: Int,
        val sourceLine: String,
    )

    /** 上下文高分关键词：紧邻这些词的金额更可能是实付款（借鉴 SpendTrace AmountExtractor）。 */
    private val CTX_HIGH = Regex("实付|付款金额|支付金额|交易金额|消费金额|应付|需付款|合计|总额")

    /** 上下文惩罚关键词：优惠/原价类金额几乎必不是实付款。权重压过高分（先查高分再查惩罚）。 */
    private val CTX_PENALTY = Regex("优惠|原价|券|红包|满减|抵扣|立减|积分|返现")

    fun buildStructuredMaterial(lines: List<OcrLine>, contextYear: Int? = null): StructuredMaterial {
        val blocks = splitOrderBlocks(lines)
        val year = contextYear ?: guessContextYear(lines)
        val dates = linkedSetOf<String>()
        val preferred = linkedSetOf<String>()
        val amounts = linkedSetOf<Double>()
        val currencyAmounts = linkedSetOf<Double>()
        // 票 28-A：value → (score, sourceLine)，同值合并取高分
        val candidateScores = LinkedHashMap<Double, Pair<Int, String>>()
        var firstDecimalAnchored = false
        var prevText = ""
        fun addCandidate(value: Double, baseScore: Int, line: String) {
            val context = line + "\n" + prevText
            val ctxScore = when {
                CTX_HIGH.containsMatchIn(context) -> 30
                CTX_PENALTY.containsMatchIn(context) -> -50
                else -> 0
            }
            val score = baseScore + ctxScore
            val existing = candidateScores[value]
            if (existing == null || score > existing.first) candidateScores[value] = score to line
        }
        for (line in lines) {
            val date = normalizeDate(line.text, year)
            if (date != null) {
                dates.add(date)
                // 锚定收口：只认「下单/付款/支付/创建时间」标签行（本行或紧邻上一行）认领的日期——
                // 促销行（活动时间：2026年4月1日-12月31日）的日期不进候选。真机案例：
                // 真日期被 OCR 切掉年份，修复后与促销日期并存，模型仍挑了促销日期；
                // 候选收窄到锚定日期后，语法约束下模型无从选错。
                if (DATE_ANCHOR_KEYWORDS.any { it in line.text || it in prevText }) preferred.add(date)
            }
            // 金额：货币符号行与负号行是强信号，永远提取（即使行内粘连日期）；
            // 无符号行才剪枝——日期行/时间行/账号行的数字不是金额
            // （「2026-09-15 16:46:28」的 2026 会被 4 位整数规则修成假金额 20.26 污染候选池）
            val hasCurrency = line.text.contains('¥') || line.text.contains('￥')
            val isSignedAmount = NEG_AMOUNT.containsMatchIn(line.text.trim())
            when {
                hasCurrency -> normalizeAmount(line.text)?.let {
                    amounts.add(it)
                    currencyAmounts.add(it)
                    addCandidate(it, 40, line.text)
                }
                date == null -> {
                    val text = line.text.trim()
                    // 时间开头的行（含时间粘连碎片的「20:34 8」）不进候选：
                    // 时间数字被当金额参考清单喂给模型，0.6B 极简支付页就近抄走（真机 51.6→20）；
                    // 账号尾号行（「储蓄卡(1212)」）同理出局（真机 29.9 被抄成 1212）
                    val isJunkAmount = text.contains('@') ||
                        TIME_ONLY.containsMatchIn(text) ||
                        TIME_PREFIX.containsMatchIn(text) ||
                        PROGRESS_NUM.containsMatchIn(text) ||
                        ACCT_TAIL.containsMatchIn(text)
                    if (!isJunkAmount) normalizeAmount(line.text, allowDecimalRestore = false)?.let {
                        amounts.add(it)
                        // 负号行（「-29.90」）与区块首个独立两位小数行（「15.40」，负号
                        // 可能被 OCR 丢）是账单详情页的金额形态，与货币符号同级强信号
                        if (isSignedAmount) currencyAmounts.add(it)
                        addCandidate(it, if (isSignedAmount) 35 else 0, line.text)
                    }
                    if (!firstDecimalAnchored && AMOUNT_STANDALONE.containsMatchIn(text)) {
                        normalizeAmount(text, allowDecimalRestore = false)?.let {
                            currencyAmounts.add(it)
                            addCandidate(it, 25, line.text)
                            firstDecimalAnchored = true
                        }
                    }
                }
            }
            prevText = line.text
        }
        val text = blocks.joinToString("\n") { block ->
            block.joinToString("\n") { cleanLineText(it.text) }
        }
        val effectiveDates = if (preferred.isNotEmpty()) preferred.toList() else dates.toList()
        val amountCandidates = candidateScores.entries
            .map { AmountCandidate(it.key, it.value.first, it.value.second) }
            .sortedWith(compareByDescending { it.score })
        return StructuredMaterial(
            blockText = text,
            normalizedDates = effectiveDates,
            amounts = amounts.toList(),
            currencyAmounts = currencyAmounts.toList(),
            amountCandidates = amountCandidates,
        )
    }

    /**
     * 金额锚定兜底（真机 51.6→20 事故）：极简支付成功页无「实付款」字样，0.6B 从
     * 「20:34 8」时间行抄走 20，唯一的「¥51.60」被弃。货币符号行是金额的最强信号：
     * 区块内恰有一个货币金额、模型输出不等于它、且模型输出也没有任何候选金额背书时，
     * 确定性替换。「实付款 20」这类无符号纯数字行仍是合法候选（有背书不干预），
     * 多个货币金额（原价/实付并存）时也不干预——有标签时模型通常选得对。
     */
    fun anchorAmount(draft: Draft, material: StructuredMaterial): Draft {
        if (material.currencyAmounts.size != 1) return draft
        val only = material.currencyAmounts.single()
        if (kotlin.math.abs(draft.amountPaid - only) < 0.005) return draft
        if (material.amounts.any { kotlin.math.abs(it - draft.amountPaid) < 0.005 }) return draft
        return draft.copy(amountPaid = only)
    }

    /**
     * 票 28-B 后置交叉验证：模型输出与确定性候选冲突 → 低置信，确认页提示重点核对
     * （复核机制本身已存在——所有草稿须确认才入账，本标志只做可视化引导）。
     * - 金额不在候选集（±0.005）：模型从噪声里抄了个候选之外的数（order_12 14.5→14.0 类）
     * - 日期候选存在却输出空串：文法允许空选择，但清单非空时选空高度可疑
     * - 输出日期不在候选清单：withDateAlternatives 理论上已拦截，此处兜底防御
     */
    fun needsReview(draft: Draft, material: StructuredMaterial, dateCandidates: List<String>): Boolean {
        if (dateCandidates.isNotEmpty() && draft.datePaid.take(10) !in dateCandidates) return true
        if (material.amounts.none { kotlin.math.abs(it - draft.amountPaid) < 0.005 }) return true
        return false
    }

    /**
     * 从 OCR 行猜测截图年份：年份必须紧跟日期分隔符（「2026-09-…」「2026年4月…」）。
     * 裸数字串不认——订单号「0260917201616034176」含子串「2016」，会把上下文年骗成 2016，
     * 连带年份缺位修复的后缀检查失效（真机收银支付页案例）。
     */
    fun guessContextYear(lines: List<OcrLine>): Int? {
        val y = Regex("""(20\d{2})[-/.年]""")
        for (line in lines) {
            y.find(line.text)?.let { return it.groupValues[1].toInt() }
        }
        return null
    }

    // ---------- 行文本清理（给模型看的 blockText） ----------

    /**
     * 界面痕迹清理：
     * - 行尾「>」「＞」（详情页箭头 UI 符号）剥掉——0.6B 会原样抄进 merchant
     * - 行中被「····」截断且截断后无数字时取前段（列表页店铺名「沪上阿姨····苏」→「沪上阿姨」）；
     *   截断后有数字（可能是金额行「商品····￥10」）不动
     * 仅用于 blockText 呈现；日期/金额归一仍用原文，互不影响。
     */
    fun cleanLineText(raw: String): String {
        val s = raw.trim().trimEnd('>', '＞')
        return s.replace(Regex("[·.]{4,}[^0-9]*$"), "").trim()
    }
}

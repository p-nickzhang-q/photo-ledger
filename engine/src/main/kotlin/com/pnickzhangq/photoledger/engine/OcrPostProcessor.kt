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

    // ---------- 日期规范化 ----------

    private val FULL_DT = Regex("""(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?\s*(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    private val DATE_ONLY = Regex("""(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?""")
    private val SHORT_DOT = Regex("""^(\d{1,2})\.(\d{1,2})(?!\.)""")   // 「09.10」角标形态（可粘连后续文字，如「09.09丨共4件」）
    private val SHORT_DASH = Regex("""^(\d{1,2})-(\d{1,2})(?![-\d])""")   // 「09-10」形态（不吞日期段后续）
    private val MD_HM = Regex("""^(\d{1,2})[-/.](\d{1,2})\s+(\d{1,2}):(\d{2})$""") // 「09-10 11:19」缺年

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

    /**
     * 从文本片段提取实付款金额。规则：
     * - 剥 ¥/￥ 前缀、全角转半角、千分位逗号去除
     * - 4 位纯整数且无小数点 → 视为小数点丢失（OCR 把 10.10 认成 1010），按两位小数解读；
     *   更长整数段（≥5 位）或带小数点的不动
     * - 找不到数字返回 null
     */
    fun normalizeAmount(raw: String): Double? {
        val m = NUM.find(raw) ?: return null
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
        // 纯 4 位整数无小数点：订单实付常见区间 (<1000) 下 4 位整数极罕见，小数点丢失更可能
        if (!s.contains('.') && s.length == 4 && parsed >= 1000) {
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
        val paidIdx = ordered.indices.filter { containsPaidKeyword(ordered[it].text) }
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

    private fun containsPaidKeyword(text: String): Boolean =
        listOf("实付款", "实付", "付款金额", "支付金额").any { it in text.replace(" ", "") }

    // ---------- 端到端素材 ----------

    /**
     * 结构化 prompt 素材：区块文本（按阅读顺序）+ 规范化日期集合 + 金额集合。
     * 供 PromptBuilder 生成「给模型看见干净数据」的 prompt。
     */
    data class StructuredMaterial(
        val blockText: String,
        val normalizedDates: List<String>,
        val amounts: List<Double>,
    )

    fun buildStructuredMaterial(lines: List<OcrLine>, contextYear: Int? = null): StructuredMaterial {
        val blocks = splitOrderBlocks(lines)
        val year = contextYear ?: guessContextYear(lines)
        val dates = linkedSetOf<String>()
        val amounts = linkedSetOf<Double>()
        for (line in lines) {
            normalizeDate(line.text, year)?.let { dates.add(it) }
            normalizeAmount(line.text)?.let { amounts.add(it) }
        }
        val text = blocks.joinToString("\n") { block ->
            block.joinToString("\n") { it.text }
        }
        return StructuredMaterial(blockText = text, normalizedDates = dates.toList(), amounts = amounts.toList())
    }

    /** 从 OCR 行猜测截图年份：找任何完整年份作上下文（角标碎片用它补全）。 */
    fun guessContextYear(lines: List<OcrLine>): Int? {
        val y = Regex("""(20\d{2})""")
        for (line in lines) {
            y.find(line.text)?.let { return it.groupValues[1].toInt() }
        }
        return null
    }
}

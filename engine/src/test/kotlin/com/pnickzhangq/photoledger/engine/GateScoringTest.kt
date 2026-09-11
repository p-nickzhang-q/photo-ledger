package com.pnickzhangq.photoledger.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GateScoringTest {

    private fun draft(amount: Double, date: String, merchant: String = "店A", category: String = "餐饮") =
        Draft(merchant, amount, "CNY", date, DateSource.ORDER_TIME, "已完成", category)

    @Test
    fun `日期取日粒度比较`() {
        assertTrue(GateScoring.dateMatches("2026-08-31", "2026-08-31 11:19:00"))
        assertFalse(GateScoring.dateMatches("2026-08-31", "2026-09-01 00:00:00"))
    }

    @Test
    fun `金额 0_005 容差`() {
        assertTrue(GateScoring.amountMatches(46.7, 46.7))
        assertTrue(GateScoring.amountMatches(46.70, 46.699))
        assertFalse(GateScoring.amountMatches(46.7, 46.8))
    }

    @Test
    fun `多单图中命中任一全对条目即算对`() {
        val s = GateScoring.scoreImage(
            file = "a.png", annotationAmount = 17.1, annotationDate = "2026-09-10",
            annotationMerchant = null, annotationCategory = null,
            drafts = listOf(draft(13.6, "2026-09-10 11:19:00"), draft(17.1, "2026-09-10 00:00:00")),
        )
        assertTrue(s.amountCorrect == true && s.dateCorrect == true && s.hasMatch)
    }

    @Test
    fun `无全对时取最近条目做归因`() {
        val s = GateScoring.scoreImage(
            file = "a.png", annotationAmount = 20.0, annotationDate = "2026-09-10",
            annotationMerchant = "店B", annotationCategory = "购物",
            drafts = listOf(draft(19.0, "2026-09-09", merchant = "店B", category = "购物")),
        )
        assertEquals(true, s.amountCorrect != true || true) // 金额 19 ≠ 20 → false
        assertEquals(false, s.amountCorrect)
        assertEquals(false, s.dateCorrect)
        assertEquals(true, s.merchantCorrect)
        assertFalse(s.hasMatch)
    }

    @Test
    fun `空 drafts 全错`() {
        val s = GateScoring.scoreImage(
            file = "a.png", annotationAmount = 1.0, annotationDate = "2026-09-10",
            annotationMerchant = null, annotationCategory = null, drafts = emptyList(),
        )
        assertEquals(false, s.amountCorrect)
        assertEquals(false, s.dateCorrect)
        assertFalse(s.hasMatch)
    }

    @Test
    fun `闸门结论 硬不过即 FAIL`() {
        val scores = List(19) { i ->
            GateScoring.scoreImage(
                "f$i.png", 10.0, "2026-09-10", null, null,
                listOf(draft(10.0, "2026-09-10")),
            )
        } + listOf(
            GateScoring.scoreImage("bad.png", 10.0, "2026-09-10", null, null, listOf(draft(99.0, "2026-09-10"))),
        )
        val summary = GateScoring.summarize(scores)
        // 金额 19/20 = 95% 达标；日期 20/20；软字段 100%
        assertTrue(summary.pass)
        val failSummary = GateScoring.summarize(
            scores + GateScoring.scoreImage("bad2.png", 10.0, "2026-09-10", null, null, listOf(draft(99.0, "2026-09-10"))),
        )
        // 金额 19/21 ≈ 90.5% < 95%（20 图时 19 对恰好压线 95%，故加到 21 图 2 错）
        assertFalse(failSummary.pass)
    }
}

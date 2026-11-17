package com.gkdbrain.app.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StrategyLearnerTest {

    @Before
    fun setUp() {
        StrategyLearner.clear()
    }

    @Test
    fun `样本不足时无策略`() {
        StrategyLearner.record(ClickEvent("a", "s", 0, appearToClickDelayMs = 1000))
        assertTrue(StrategyLearner.suggest("a", "s").isEmpty)
    }

    @Test
    fun `稳定延时学习出delay`() {
        StrategyLearner.record(ClickEvent("a", "s", 0, appearToClickDelayMs = 1000))
        StrategyLearner.record(ClickEvent("a", "s", 10_000, appearToClickDelayMs = 1000))
        StrategyLearner.record(ClickEvent("a", "s", 20_000, appearToClickDelayMs = 1000))
        val s = StrategyLearner.suggest("a", "s")
        assertEquals(1000L, s.delayMs)
        assertNull(s.actionMaximum)
    }

    @Test
    fun `延时低于阈值不学习`() {
        StrategyLearner.record(ClickEvent("a", "s", 0, appearToClickDelayMs = 100))
        StrategyLearner.record(ClickEvent("a", "s", 10_000, appearToClickDelayMs = 100))
        val s = StrategyLearner.suggest("a", "s")
        assertNull(s.delayMs)
    }

    @Test
    fun `连续点击学习出actionMaximum`() {
        StrategyLearner.record(ClickEvent("a", "s", 0))
        StrategyLearner.record(ClickEvent("a", "s", 500))
        StrategyLearner.record(ClickEvent("a", "s", 1000))
        val s = StrategyLearner.suggest("a", "s")
        assertEquals(3, s.actionMaximum)
        assertNull(s.delayMs)
    }

    @Test
    fun `间隔较大不视为连点`() {
        StrategyLearner.record(ClickEvent("a", "s", 0))
        StrategyLearner.record(ClickEvent("a", "s", 10_000))
        val s = StrategyLearner.suggest("a", "s")
        assertNull(s.actionMaximum)
    }

    @Test
    fun `延时与连点可同时学习`() {
        StrategyLearner.record(ClickEvent("a", "s", 0, appearToClickDelayMs = 800))
        StrategyLearner.record(ClickEvent("a", "s", 500, appearToClickDelayMs = 800))
        StrategyLearner.record(ClickEvent("a", "s", 1000, appearToClickDelayMs = 800))
        val s = StrategyLearner.suggest("a", "s")
        assertEquals(800L, s.delayMs)
        assertEquals(3, s.actionMaximum)
    }

    @Test
    fun `不同特征互不干扰`() {
        StrategyLearner.record(ClickEvent("a", "s1", 0, appearToClickDelayMs = 1000))
        StrategyLearner.record(ClickEvent("a", "s1", 10_000, appearToClickDelayMs = 1000))
        StrategyLearner.record(ClickEvent("b", "s2", 0))
        StrategyLearner.record(ClickEvent("b", "s2", 500))

        val s1 = StrategyLearner.suggest("a", "s1")
        assertEquals(1000L, s1.delayMs)
        assertNull(s1.actionMaximum)

        val s2 = StrategyLearner.suggest("b", "s2")
        assertEquals(2, s2.actionMaximum)
    }
}

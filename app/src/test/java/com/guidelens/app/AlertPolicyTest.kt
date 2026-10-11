package com.guidelens.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the alert tier logic — the same rules that decide
 * what a visually impaired user hears on the street. All times are injected.
 */
class AlertPolicyTest {

    private fun AlertPolicy.speak(d: Float, name: String = "person", side: String = "ahead", now: Long) =
        evaluate(name, side, d, now).phrase

    // ---------- tier classification ----------

    @Test
    fun `out of range produces silence`() {
        val p = AlertPolicy()
        assertEquals(AlertPolicy.Tier.NONE, p.classify(7.1f))
        assertEquals(AlertPolicy.Tier.NONE, p.classify(100f))
        assertNull(p.speak(8f, now = 1000L))
    }

    @Test
    fun `negative distance never triggers a stop alert`() {
        val p = AlertPolicy()
        assertEquals(AlertPolicy.Tier.NONE, p.classify(-0.5f))
        assertNull(p.speak(-1f, now = 1000L))
    }

    @Test
    fun `tier boundaries at normal sensitivity`() {
        val p = AlertPolicy()
        assertEquals(AlertPolicy.Tier.NOTICE, p.classify(6.9f))
        assertEquals(AlertPolicy.Tier.CLOSE, p.classify(5.0f))
        assertEquals(AlertPolicy.Tier.STOP, p.classify(3.0f))
    }

    @Test
    fun `near sensitivity shrinks all tiers`() {
        val p = AlertPolicy(sensitivity = "near") // mult 0.8 -> tiers at 5.6 / 4.0 / 2.4
        assertEquals(AlertPolicy.Tier.NONE, p.classify(5.7f))
        assertEquals(AlertPolicy.Tier.NOTICE, p.classify(5.5f))
        assertEquals(AlertPolicy.Tier.CLOSE, p.classify(4.0f))
        // note: 2.4f sits exactly ON the 3m*0.8 boundary in float math — use 2.3
        assertEquals(AlertPolicy.Tier.STOP, p.classify(2.3f))
    }

    @Test
    fun `far sensitivity widens all tiers`() {
        val p = AlertPolicy(sensitivity = "far") // mult 1.3 -> tiers at 9.1 / 6.5 / 3.9
        assertEquals(AlertPolicy.Tier.NONE, p.classify(9.2f))
        assertEquals(AlertPolicy.Tier.NOTICE, p.classify(9.0f))
        // keep clear of float boundaries: 6.5f/3.9f can round across the tier line
        assertEquals(AlertPolicy.Tier.CLOSE, p.classify(6.4f))
        assertEquals(AlertPolicy.Tier.STOP, p.classify(3.8f))
    }

    // ---------- notice tier (<=7m, max twice, 4.5s window) ----------

    @Test
    fun `notice fires with phrase and short buzz`() {
        val p = AlertPolicy()
        val d = p.evaluate("person", "on your left", 6f, now = 10_000L)
        assertEquals(AlertPolicy.Tier.NOTICE, d.tier)
        assertEquals("person on your left, about 6 meters.", d.phrase)
        assertTrue(d.buzz.contentEquals(longArrayOf(60)))
    }

    @Test
    fun `notice is throttled inside the 4_5s window`() {
        val p = AlertPolicy()
        assertTrue(p.speak(6f, now = 0L) != null)
        assertNull(p.speak(6f, now = 4_000L))
        assertTrue(p.speak(6f, now = 4_600L) != null)   // second (and last) allowed notice
        assertNull(p.speak(6f, now = 20_000L))           // quota of 2 exhausted
    }

    @Test
    fun `state expires after 20s so notice quota resets`() {
        val p = AlertPolicy()
        p.evaluate("person", "ahead", 6f, now = 0L)
        p.evaluate("person", "ahead", 6f, now = 5_000L)  // 2 notices used
        val fresh = p.speak(6f, now = 26_000L)           // >20s idle -> entry cleaned
        assertTrue(fresh != null)
    }

    @Test
    fun `different object and side get independent quotas`() {
        val p = AlertPolicy()
        p.evaluate("person", "ahead", 6f, now = 0L)
        p.evaluate("person", "ahead", 6f, now = 5_000L)
        assertTrue(p.speak(6f, name = "chair", now = 5_000L) != null)
        assertTrue(p.speak(6f, side = "on your right", now = 5_000L) != null)
    }

    // ---------- close tier (<=5m, every 5s, suppressed in minimal mode) ----------

    @Test
    fun `close repeats every 5 seconds`() {
        val p = AlertPolicy()
        assertTrue(p.speak(4.5f, now = 0L) != null)
        assertNull(p.speak(4.5f, now = 4_000L))
        assertTrue(p.speak(4.5f, now = 5_100L) != null)
    }

    @Test
    fun `minimal verbosity silences notice and close but never stop`() {
        val p = AlertPolicy(verbosity = "minimal")
        assertNull(p.speak(6f, now = 0L))
        assertNull(p.speak(4f, now = 0L))
        assertTrue(p.speak(2f, now = 0L) != null)
    }

    // ---------- stop tier (<=3m, priority, every 2.5s, triple buzz) ----------

    @Test
    fun `stop fires with priority phrase and triple buzz`() {
        val p = AlertPolicy()
        val d = p.evaluate("chair", "ahead", 2.5f, now = 0L)
        assertEquals(AlertPolicy.Tier.STOP, d.tier)
        assertEquals("Stop! chair ahead.", d.phrase)
        assertTrue(d.buzz.contentEquals(longArrayOf(120, 80, 120, 80, 120)))
    }

    @Test
    fun `stop repeats every 2_5 seconds and wins over lower tiers`() {
        val p = AlertPolicy()
        assertTrue(p.speak(2.5f, now = 0L) != null)
        assertNull(p.speak(2.5f, now = 2_000L))
        assertTrue(p.speak(2.5f, now = 2_600L) != null)
        // dropping to 5m and back to 3m must not reset the stop throttle:
        p.evaluate("person", "ahead", 4.9f, now = 2_700L)
        assertNull(p.speak(2.5f, now = 3_500L))
        assertTrue(p.speak(2.5f, now = 5_200L) != null)
    }
}

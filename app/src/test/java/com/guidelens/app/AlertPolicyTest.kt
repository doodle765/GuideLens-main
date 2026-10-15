package com.guidelens.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the alert decision engine — the same rules that
 * decide what a blind user hears on the street. All clocks are injected.
 */
class AlertPolicyTest {

    private fun AlertPolicy.texts(d: Float, name: String = "person", side: String = "ahead", now: Long) =
        evaluate(name, side, d, now).map { it.text }

    // ---------- tier classification ----------

    @Test
    fun `out of range and negative distance are silent`() {
        val p = AlertPolicy()
        assertEquals(AlertPolicy.Tier.NONE, p.classify(7.1f))
        assertEquals(AlertPolicy.Tier.NONE, p.classify(100f))
        assertEquals(AlertPolicy.Tier.NONE, p.classify(-0.5f))
        assertTrue(p.texts(8f, now = 1000L).isEmpty())
        assertTrue(p.texts(-1f, now = 1000L).isEmpty())
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
        assertEquals(AlertPolicy.Tier.STOP, p.classify(2.3f))   // clear of float boundary
    }

    @Test
    fun `far sensitivity widens all tiers`() {
        val p = AlertPolicy(sensitivity = "far") // mult 1.3 -> tiers at 9.1 / 6.5 / 3.9
        assertEquals(AlertPolicy.Tier.NONE, p.classify(9.2f))
        assertEquals(AlertPolicy.Tier.NOTICE, p.classify(9.0f))
        assertEquals(AlertPolicy.Tier.CLOSE, p.classify(6.4f))  // clear of float boundary
        assertEquals(AlertPolicy.Tier.STOP, p.classify(3.8f))   // clear of float boundary
    }

    // ---------- notice tier: ahead only, throttled, capped ----------

    @Test
    fun `notice only fires for objects ahead`() {
        val p = AlertPolicy()
        assertTrue(p.texts(6f, side = "on your right", now = 0L).isEmpty())
        assertTrue(p.texts(6f, side = "on your left", now = 0L).isEmpty())
        assertEquals(1, p.texts(6f, side = "ahead", now = 0L).size)
    }

    @Test
    fun `notice is throttled inside the 4_5s window and capped at two`() {
        val p = AlertPolicy()
        assertEquals(1, p.texts(6f, now = 0L).size)
        assertTrue(p.texts(6f, now = 4_000L).isEmpty())
        assertEquals(1, p.texts(6f, now = 4_600L).size)
        assertTrue(p.texts(6f, now = 20_000L).isEmpty())   // quota of 2 used
    }

    // ---------- stop tier ----------

    @Test
    fun `stop fires immediately with priority phrase and triple buzz`() {
        val p = AlertPolicy()
        val d = p.evaluate("chair", "on your left", 2.5f, now = 0L).single()
        assertTrue(d.priority)
        assertEquals("Stop! chair on your left.", d.text)
        assertTrue(d.buzz!!.contentEquals(longArrayOf(120, 80, 120, 80, 120)))
    }

    @Test
    fun `stop repeats every 2_5 seconds`() {
        val p = AlertPolicy()
        assertEquals(1, p.texts(2.5f, now = 0L).size)
        assertTrue(p.texts(2.5f, now = 2_000L).isEmpty())
        assertEquals(1, p.texts(2.5f, now = 2_600L).size)
    }

    // ---------- anti-spam: moving away, approaching, static ----------

    @Test
    fun `object moving away goes quiet`() {
        val p = AlertPolicy()
        assertEquals(1, p.texts(4.5f, now = 0L).size)           // "coming close"
        assertTrue(p.texts(5.3f, now = 1_200L).isEmpty())       // receding -> silent
        assertTrue(p.texts(5.3f, now = 4_000L).isEmpty())       // still in 8s grace
        assertEquals(1, p.texts(5.3f, now = 9_500L).size)       // grace over -> notice
    }

    @Test
    fun `approaching object announced once per episode`() {
        val p = AlertPolicy()
        assertTrue(p.texts(6.5f, side = "on your right", now = 0L).isEmpty())
        val r2 = p.texts(5.4f, side = "on your right", now = 1_100L)
        assertTrue(r2.contains("person approaching."))
        val r3 = p.texts(4.9f, side = "on your right", now = 2_200L)
        assertTrue(!r3.contains("person approaching."))          // once per episode
    }

    @Test
    fun `static close object says still at most every 15 seconds`() {
        val p = AlertPolicy()
        val first = p.texts(4.0f, now = 0L)
        assertTrue(first.single().startsWith("person coming close"))
        assertTrue(p.texts(4.0f, now = 10_000L).isEmpty())
        val still = p.texts(4.05f, now = 16_000L)
        assertTrue(still.single().startsWith("person still ahead"))
        assertTrue(p.texts(4.0f, now = 20_000L).isEmpty())      // not again before 15s
    }

    @Test
    fun `getting closer again re-announces coming close`() {
        val p = AlertPolicy()
        p.texts(4.4f, now = 0L)                                  // baseline 4.4
        p.texts(4.4f, now = 10_000L)                             // static -> silent
        val closer = p.texts(3.8f, now = 16_000L)                // 0.6m closer
        assertTrue(closer.any { it.startsWith("person coming close") })
    }

    // ---------- path clear ----------

    @Test
    fun `clear announced when a close object vanishes`() {
        val p = AlertPolicy()
        p.texts(4f, now = 0L)
        assertEquals(listOf("Path clear."), p.tick(2_000L).map { it.text })
        assertTrue(p.tick(3_000L).isEmpty())
    }

    @Test
    fun `clear for a side object names the side`() {
        val p = AlertPolicy()
        p.texts(4f, name = "chair", side = "on your left", now = 0L)
        assertEquals(listOf("Clear on your left."), p.tick(2_000L).map { it.text })
    }

    @Test
    fun `clear announcements throttled to one per six seconds`() {
        val p = AlertPolicy()
        p.texts(4f, now = 0L)
        p.texts(3.5f, name = "chair", side = "on your left", now = 100L)
        assertEquals(1, p.tick(2_000L).size)                     // second one swallowed
    }

    // ---------- verbosity & sonar ----------

    @Test
    fun `minimal verbosity keeps stop but silences notice and close`() {
        val p = AlertPolicy(verbosity = "minimal")
        assertTrue(p.texts(6f, now = 0L).isEmpty())
        assertTrue(p.texts(4f, now = 0L).isEmpty())
        assertEquals(1, p.texts(2f, now = 0L).size)
    }

    @Test
    fun `urgency ramps from zero at 7m to one at 3m`() {
        val p = AlertPolicy()
        assertEquals(0f, p.urgency(7.1f), 0.001f)
        assertEquals(1f, p.urgency(3.0f), 0.001f)
        assertEquals(0.5f, p.urgency(5.0f), 0.05f)
        assertEquals(0f, p.urgency(-1f), 0.001f)
    }
}

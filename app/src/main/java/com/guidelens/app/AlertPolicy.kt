package com.guidelens.app

/**
 * Pure decision logic for the distance-tiered alerts — no Android imports,
 * so every rule is unit-testable on the JVM (see AlertPolicyTest).
 *
 * Mirrors the original thresholds from the web app:
 *   <= 7 m : notice  (max twice, 4.5 s window)
 *   <= 5 m : "coming close" (repeats every 5 s)
 *   <= 3 m : "Stop!" (priority, repeats every 2.5 s)
 */
class AlertPolicy(
    var sensitivity: String = "normal",   // "near" | "normal" | "far"
    var verbosity: String = "full"        // "minimal" suppresses notice/close tiers
) {
    enum class Tier { NONE, NOTICE, CLOSE, STOP }

    /**
     * Outcome of one evaluation: which [tier] applied, the phrase to speak
     * (null = stay silent this frame) and the buzz pattern to play.
     */
    data class Decision(val tier: Tier, val phrase: String?, val buzz: LongArray?)

    /**
     * Per-object+side throttling state. The `fired*` flags distinguish
     * "never alerted" from "alerted at time 0" — a 0-sentinel would suppress
     * the very first alert whenever [evaluate]'s clock starts near zero.
     */
    private class Stamp(
        var tNotice: Long = 0, var cNotice: Int = 0,
        var tClose: Long = 0, var tStop: Long = 0,
        var firedNotice: Boolean = false,
        var firedClose: Boolean = false,
        var firedStop: Boolean = false
    )

    private val state = HashMap<String, Stamp>()

    private fun multiplier(): Float = when (sensitivity) {
        "near" -> 0.8f
        "far" -> 1.3f
        else -> 1.0f
    }

    /** Which tier a raw distance falls into (NONE = out of range). */
    fun classify(dist: Float): Tier {
        if (dist < 0f) return Tier.NONE
        val m = multiplier()
        return when {
            dist > 7f * m -> Tier.NONE
            dist <= 3f * m -> Tier.STOP
            dist <= 5f * m -> Tier.CLOSE
            else -> Tier.NOTICE
        }
    }

    /** Decide whether to alert now. [now] is injected so tests are deterministic. */
    fun evaluate(name: String, side: String, dist: Float, now: Long): Decision {
        if (classify(dist) == Tier.NONE) return Decision(Tier.NONE, null, null)
        clean(now)
        val st = state.getOrPut("$name|$side") { Stamp() }
        val m = multiplier()

        return when {
            dist <= 3f * m ->
                if (!st.firedStop || now - st.tStop > 2500) {
                    st.firedStop = true
                    st.tStop = now
                    Decision(Tier.STOP, "Stop! $name ahead.", longArrayOf(120, 80, 120, 80, 120))
                } else Decision(Tier.STOP, null, null)

            dist <= 5f * m ->
                if (verbosity != "minimal" && (!st.firedClose || now - st.tClose > 5000)) {
                    st.firedClose = true
                    st.tClose = now
                    Decision(Tier.CLOSE, "$name coming close, ${distWord(dist)}.", longArrayOf(100, 60, 100))
                } else Decision(Tier.CLOSE, null, null)

            else ->
                if (verbosity != "minimal" && (!st.firedNotice || now - st.tNotice > 4500) && st.cNotice < 2) {
                    st.firedNotice = true
                    st.tNotice = now
                    st.cNotice++
                    Decision(Tier.NOTICE, "$name $side, ${distWord(dist)}.", longArrayOf(60))
                } else Decision(Tier.NOTICE, null, null)
        }
    }

    private fun clean(now: Long) {
        state.entries.removeIf {
            val st = it.value
            (st.firedNotice || st.firedClose || st.firedStop) &&
                now - maxOf(st.tNotice, st.tClose, st.tStop) > 20000
        }
    }
}

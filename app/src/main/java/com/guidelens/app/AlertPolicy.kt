package com.guidelens.app

/**
 * Pure decision logic for distance-tiered alerts — no Android imports, fully
 * unit-testable (AlertPolicyTest). Decides WHAT to say and WHEN; AlertCenter
 * only executes (TTS / vibration / sonar).
 *
 * Anti-spam rules, designed for blind users:
 *  - NOTICE tier only for objects roughly AHEAD — a person passing on the
 *    right does not get a running commentary.
 *  - objects MOVING AWAY are silent for 8 s (no "coming close" for someone
 *    already leaving).
 *  - fast-approaching objects get one "X approaching" announcement per episode.
 *  - a static close object repeats at most every 15 s, phrased "still ...".
 *  - when a close object disappears, one "Path clear" / "Clear on your left".
 *
 * Tiers (mirroring the web app, scaled by sensitivity multiplier):
 *   <= 7 m notice (max 2x, 4.5 s window) | <= 5 m close | <= 3 m STOP
 */
class AlertPolicy(
    var sensitivity: String = "normal",   // "near" | "normal" | "far"
    var verbosity: String = "full"        // "minimal" suppresses notice/close tiers
) {
    enum class Tier { NONE, NOTICE, CLOSE, STOP }

    data class Say(val text: String, val priority: Boolean, val buzz: LongArray?)

    private class Stamp(
        var dist: Float = 99f,
        var t: Long = 0,
        var seen: Long = 0,
        var firedNotice: Boolean = false, var tNotice: Long = 0, var cNotice: Int = 0,
        var firedClose: Boolean = false, var tClose: Long = 0, var closeBaseline: Float = 99f,
        var firedStop: Boolean = false, var tStop: Long = 0,
        var risingUntil: Long = 0,
        var approachAnnounced: Boolean = false, var approachFire: Boolean = false,
        var lastApproachDist: Float = 99f,
        var wasClose: Boolean = false, var clearAnnounced: Boolean = true
    )

    private val state = HashMap<String, Stamp>()
    // Sentinel that never suppresses the first announcement: MIN_VALUE/2 so that
    // `now - lastClearSayAt` cannot overflow for any realistic clock (real or test).
    private var lastClearSayAt = Long.MIN_VALUE / 2

    /** Forget all tracked obstacles (camera covered / pocketed / restarted). */
    fun reset() {
        state.clear()
        lastClearSayAt = Long.MIN_VALUE / 2
    }

    internal fun multiplier(): Float = when (sensitivity) {
        "near" -> 0.8f
        "far" -> 1.3f
        else -> 1.0f
    }

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

    /** Sonar urgency for a distance: 0 at the 7 m edge, 1 at the 3 m edge. */
    fun urgency(dist: Float): Float {
        if (dist < 0f) return 0f
        val m = multiplier()
        val stopB = 3f * m
        val fullB = 7f * m
        if (dist >= fullB) return 0f
        return (1f - (dist - stopB) / (fullB - stopB)).coerceIn(0f, 1f)
    }

    /** Observe one detection; returns the announcements for this frame. */
    fun evaluate(name: String, side: String, dist: Float, now: Long): List<Say> {
        if (classify(dist) == Tier.NONE) return emptyList()
        clean(now)
        val key = "$name|$side"
        val existing = state[key]
        val m = multiplier()

        // ---- first sighting of this object+side ----
        if (existing == null) {
            val st = Stamp(dist = dist, t = now, seen = now, lastApproachDist = dist)
            state[key] = st
            if (dist <= 5f) { st.wasClose = true; st.clearAnnounced = false }
            return when {
                dist <= 3f * m -> {
                    st.firedStop = true; st.tStop = now
                    listOf(Say(stopPhrase(name, side), true, longArrayOf(120, 80, 120, 80, 120)))
                }
                dist <= 5f * m -> {
                    st.firedClose = true; st.tClose = now; st.closeBaseline = dist
                    listOf(Say("$name coming close, ${distWord(dist)}.", false, longArrayOf(100, 60, 100)))
                }
                else -> if (side == "ahead" && verbosity != "minimal") {
                    st.firedNotice = true; st.tNotice = now; st.cNotice = 1
                    listOf(Say("$name ahead, ${distWord(dist)}.", false, longArrayOf(60)))
                } else emptyList()
            }
        }

        // ---- tracked object: update motion state ----
        val st = existing
        val dt = now - st.t
        if (dt > 400) {
            val dd = dist - st.dist
            if (dd > 0.4f) {
                st.risingUntil = now + 8000          // receding -> stay quiet
                st.approachAnnounced = false
            } else if (!st.approachAnnounced && dist <= 6f &&
                st.lastApproachDist - dist >= 1.0f && now >= st.risingUntil) {
                st.approachAnnounced = true
                st.approachFire = true
            }
            st.lastApproachDist = dist
        }
        st.dist = dist; st.t = now; st.seen = now
        if (dist <= 5f) { st.wasClose = true; st.clearAnnounced = false }

        val out = mutableListOf<Say>()
        val suppressTierSay = st.approachFire
        if (st.approachFire) {
            st.approachFire = false
            out += Say("$name approaching.", false, null)
        }

        val tierSay: Say? = when {
            dist <= 3f * m ->
                if (!st.firedStop || now - st.tStop > 2500) {
                    st.firedStop = true; st.tStop = now
                    Say(stopPhrase(name, side), true, longArrayOf(120, 80, 120, 80, 120))
                } else null

            dist <= 5f * m ->
                if (verbosity == "minimal" || now < st.risingUntil) null
                else {
                    val improved = dist <= st.closeBaseline - 0.5f
                    val expired = now - st.tClose > 15000
                    val firstClose = !st.firedClose
                    if (firstClose || (improved && now - st.tClose > 5000) || (!improved && expired)) {
                        st.firedClose = true; st.tClose = now; st.closeBaseline = dist
                        val phrase = if (firstClose || improved)
                            "$name coming close, ${distWord(dist)}."
                        else
                            "$name still $side, ${distWord(dist)}."
                        Say(phrase, false, longArrayOf(100, 60, 100))
                    } else null
                }

            else ->
                if (side != "ahead" || verbosity == "minimal" || now < st.risingUntil) null
                else if ((!st.firedNotice || now - st.tNotice > 4500) && st.cNotice < 2) {
                    st.firedNotice = true; st.tNotice = now; st.cNotice++
                    Say("$name ahead, ${distWord(dist)}.", false, longArrayOf(60))
                } else null
        }
        if (!suppressTierSay && tierSay != null) out += tierSay
        return out
    }

    /** Call every frame: emits one path-clear announcement per vanished obstacle. */
    fun tick(now: Long): List<Say> {
        val out = mutableListOf<Say>()
        val it = state.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val st = e.value
            if (st.wasClose && !st.clearAnnounced && now - st.seen > 1500) {
                st.clearAnnounced = true
                if (now - lastClearSayAt > 6000) {
                    lastClearSayAt = now
                    val side = e.key.substringAfter('|')
                    out += Say(if (side == "ahead") "Path clear." else "Clear $side.", false, null)
                }
                it.remove()
            }
        }
        return out
    }

    private fun clean(now: Long) {
        state.entries.removeIf { now - it.value.seen > 20000 }
    }

    private fun stopPhrase(name: String, side: String): String =
        if (side == "ahead") "Stop! $name ahead." else "Stop! $name $side."
}

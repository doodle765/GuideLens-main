package com.guidelens.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The monocular distance math is safety-adjacent: it decides what the user
 * hears. These tests pin the geometry (frame 1080x1920, HFOV 62 deg) and the
 * out-of-range rejections.
 */
class DetectorMathTest {

    // fPx for 1920x1080 @ HFOV 62 deg is ~898 px; a person (1.7 m) subtending
    // 306 px is therefore at ~5.0 m.
    private val fh = 1920
    private val fw = 1080

    @Test
    fun `person at known apparent size lands in the 5m band`() {
        val d = Detector.estDistance("person", 306f, fh, fw)
        assertEquals(5.0f, d!!, 0.15f)
    }

    @Test
    fun `distance scales inversely with apparent height`() {
        val near = Detector.estDistance("person", 612f, fh, fw)!!  // twice as tall -> half as far
        val far = Detector.estDistance("person", 306f, fh, fw)!!
        assertEquals(2.5f, near, 0.1f)
        assertEquals(far / 2f, near, 0.1f)
    }

    @Test
    fun `classes without a known height return null`() {
        assertNull(Detector.estDistance("bottle", 306f, fh, fw))
        assertNull(Detector.estDistance("kite", 100f, fh, fw))
    }

    @Test
    fun `invalid inputs return null`() {
        assertNull(Detector.estDistance("person", 0f, fh, fw))
        assertNull(Detector.estDistance("person", -5f, fh, fw))
        assertNull(Detector.estDistance("person", 10f, fh, fw))      // ~150 m -> beyond 25 m clamp
        assertNull(Detector.estDistance("person", 20_000f, fh, fw))  // ~0.08 m -> below 0.2 m clamp
    }

    @Test
    fun `side classification uses the 0_35 threshold`() {
        assertEquals("on your left", Detector.sideOf(-0.36f))
        assertEquals("on your right", Detector.sideOf(0.36f))
        assertEquals("ahead", Detector.sideOf(0f))
        assertEquals("ahead", Detector.sideOf(0.34f))
        assertEquals("ahead", Detector.sideOf(-0.34f))
    }

    @Test
    fun `spoken distance phrasing`() {
        assertEquals("right in front of you", distWord(1.5f))
        assertEquals("right in front of you", distWord(0.4f))
        assertEquals("about 5 meters", distWord(5.4f))
        assertEquals("about 7 meters", distWord(6.6f))
    }
}

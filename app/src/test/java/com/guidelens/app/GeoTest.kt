package com.guidelens.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure geometry / phrasing tests for Geo — polyline decoding and haversine
 * are the foundation of "Where am I?" and turn-by-turn guidance.
 */
class GeoTest {

    // ---------- haversine ----------

    @Test
    fun `one degree of longitude at the equator is about 111_2 km`() {
        val d = Geo.hav(LatLng(0.0, 0.0), LatLng(0.0, 1.0))
        assertEquals(111_195.0, d, 100.0)
    }

    @Test
    fun `distance to self is zero`() {
        assertEquals(0.0, Geo.hav(LatLng(25.4, 68.4), LatLng(25.4, 68.4)), 0.001)
    }

    @Test
    fun `routeLen sums consecutive points`() {
        val pts = listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0), LatLng(0.0, 2.0))
        assertEquals(2 * 111_195.0, Geo.routeLen(pts), 200.0)
    }

    @Test
    fun `nearestDistToRoute finds closest vertex`() {
        val route = listOf(LatLng(0.0, 0.0), LatLng(0.0, 2.0))
        val d = Geo.nearestDistToRoute(LatLng(0.0, 1.0), route)
        assertEquals(111_195.0, d, 100.0)
    }

    // ---------- polyline decoding (Google/OSRM format) ----------

    @Test
    fun `decodes the canonical example polyline`() {
        // Google's classic example
        val pts = Geo.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-6)
        assertEquals(-120.2, pts[0].lng, 1e-6)
        assertEquals(40.7, pts[1].lat, 1e-6)
        assertEquals(-120.95, pts[1].lng, 1e-6)
        assertEquals(43.252, pts[2].lat, 1e-6)
        assertEquals(-126.453, pts[2].lng, 1e-6)
    }

    @Test
    fun `decodes an empty string to an empty route`() {
        assertTrue(Geo.decodePolyline("").isEmpty())
    }

    // ---------- maneuver phrasing ----------

    @Test
    fun `depart speaks a start instruction with street name`() {
        val s = Geo.Step("depart", "", "Main Street", 0.0, 0.0)
        assertEquals("Start walking on Main Street", Geo.maneuverText(s))
    }

    @Test
    fun `turn variants phrase correctly`() {
        fun step(type: String, mod: String, name: String = "") = Geo.Step(type, mod, name, 0.0, 0.0)
        assertEquals("Turn left on Main Street", Geo.maneuverText(step("turn", "left", "Main Street")))
        assertEquals("Turn right", Geo.maneuverText(step("turn", "right")))
        assertEquals("Bear slight left", Geo.maneuverText(step("turn", "slight left")))
        assertEquals("Turn sharp right", Geo.maneuverText(step("new name", "sharp right")))
        assertEquals("Make a U-turn", Geo.maneuverText(step("turn", "uturn")))
    }

    @Test
    fun `roundabout arrive and unknown types are handled`() {
        assertEquals("Take the roundabout", Geo.maneuverText(Geo.Step("roundabout_right", "", "", 0.0, 0.0)))
        assertEquals("You are arriving", Geo.maneuverText(Geo.Step("arrive", "", "", 0.0, 0.0)))
        assertEquals("Continue straight", Geo.maneuverText(Geo.Step("fork", "left", "", 0.0, 0.0)))
        assertEquals("Continue straight on High Road", Geo.maneuverText(Geo.Step("fork", "left", "High Road", 0.0, 0.0)))
    }
}

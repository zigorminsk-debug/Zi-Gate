package by.zakharevich.zigate.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePollingTest {

    @Test
    fun nearBands() {
        assertEquals(1000L, AdaptivePolling.intervalMs(40f, 5f))
        assertEquals(1000L, AdaptivePolling.intervalMs(120f, 5f))
        assertEquals(2000L, AdaptivePolling.intervalMs(180f, 5f))
        assertEquals(3000L, AdaptivePolling.intervalMs(250f, 5f))
        assertEquals(10_000L, AdaptivePolling.intervalMs(600f, 5f))
    }

    @Test
    fun citySpeedDoesNotSkipFiveMetreZone() {
        // 50 km/h ≈ 14 m/s, 55 m from a 5 m zone → beyond 50 m, ETA ~3.6 s
        val p = AdaptivePolling.intervalMs(55f, 5f, radialSpeedMs = -14f)
        assertTrue("period=$p must be ≤ 2 s at 55 m / 50 km/h", p <= 2000L)
    }

    @Test
    fun highwayLookahead() {
        val p = AdaptivePolling.intervalMs(200f, 5f, radialSpeedMs = -22f)
        assertTrue("period=$p", p <= 3000L)
    }

    @Test
    fun fiveHundredMetresMustNotSlowToThirty() {
        val p = AdaptivePolling.intervalMs(500f, 5f, radialSpeedMs = -12f)
        assertTrue("period=$p at 500 m closing", p <= 5_000L)
        val sit = AdaptivePolling.intervalMs(500f, 5f, stationary = true)
        assertTrue("period=$sit sitting at 500 m", sit <= 5_000L)
    }

    @Test
    fun farStationarySavesBattery() {
        assertEquals(60_000L, AdaptivePolling.intervalMs(2000f, 5f, stationary = true))
    }

    @Test
    fun chargingIsOneSecond() {
        assertEquals(1000L, AdaptivePolling.intervalMs(5000f, 5f, charging = true))
    }
}

package com.smartview.glassai.services

import org.junit.Assert.*
import org.junit.Test

class LocationFixPolicyTest {
    @Test fun recentApproximateLocationIsEnoughForWeather() {
        assertTrue(LocationFixPolicy.usable(30000, 3000f))
    }
    @Test fun oldAndFutureFixesAreRejected() {
        assertFalse(LocationFixPolicy.usable(120001, 10f))
        assertFalse(LocationFixPolicy.usable(-1, 10f))
    }
    @Test fun invalidAccuracyIsRejected() {
        assertFalse(LocationFixPolicy.usable(1000, Float.NaN))
        assertFalse(LocationFixPolicy.usable(1000, -1f))
        assertFalse(LocationFixPolicy.usable(1000, 10001f))
    }
}

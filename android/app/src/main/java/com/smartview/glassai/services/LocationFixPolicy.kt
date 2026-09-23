package com.smartview.glassai.services

internal object LocationFixPolicy {
    fun usable(ageMs: Long, accuracyMeters: Float): Boolean =
        ageMs in 0..120000 && accuracyMeters.isFinite() && accuracyMeters in 0f..10000f
}

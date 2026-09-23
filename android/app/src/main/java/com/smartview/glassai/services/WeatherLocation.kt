package com.smartview.glassai.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.resume

/** One request per local-weather question; no continuous tracking or disk storage. */
object WeatherLocation {
    fun permitted(context: Context) = ContextCompat.checkSelfPermission(context,
        Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun read(context: Context): Location = coroutineScope {
        check(permitted(context)) { "Allow Location in TurboMeta Settings, then turn the voice assistant off and on. Or name a city." }
        val manager = context.getSystemService(LocationManager::class.java)
        check(manager.isLocationEnabled) { "Turn on your phone's Location setting, or ask for weather in a named city." }
        val providers = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { manager.hasProvider(it) && manager.isProviderEnabled(it) }
        check(providers.isNotEmpty()) { "No location provider is available. Please name a city." }
        val results = Channel<Location?>(providers.size)
        val jobs = providers.map { provider -> launch {
            val location = try {
                withTimeoutOrNull(12000) {
                    suspendCancellableCoroutine<Location?> { continuation ->
                        val signal = CancellationSignal()
                        continuation.invokeOnCancellation { signal.cancel() }
                        manager.getCurrentLocation(provider, signal, context.mainExecutor) { fix ->
                            if (continuation.isActive) continuation.resume(fix)
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            results.send(location)
        } }
        try {
            repeat(providers.size) {
                val fix = results.receive()
                if (fix != null && fix.hasAccuracy() && LocationFixPolicy.usable(
                        (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1000000,
                        fix.accuracy)) return@coroutineScope fix
            }
            error("I couldn't get a recent phone location. Try near a window, or name a city.")
        } finally { jobs.forEach { it.cancel() }; results.close() }
    }
}

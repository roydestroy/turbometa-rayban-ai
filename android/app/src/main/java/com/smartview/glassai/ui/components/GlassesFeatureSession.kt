package com.smartview.glassai.ui.components

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import com.smartview.glassai.services.GlassesAudioGate
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/** Never mount an auto-starting camera/audio screen before the wake microphone is closed. */
@Composable
fun GlassesFeatureSession(content: @Composable () -> Unit) {
    var lease by remember { mutableStateOf<GlassesAudioGate.Lease?>(null) }
    LaunchedEffect(Unit) {
        val acquired = GlassesAudioGate.acquireFeature()
        lease = acquired
        try { awaitCancellation() } finally {
            // Let child DisposableEffects stop their streams/TTS before waking the microphone.
            withContext(NonCancellable) { delay(300); acquired.close() }
        }
    }
    if (lease != null) content() else Text("Preparing glasses…")
}

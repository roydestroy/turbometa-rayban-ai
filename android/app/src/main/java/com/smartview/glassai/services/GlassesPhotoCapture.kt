package com.smartview.glassai.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Caller owns GlassesAudioGate. Use the SDK shutter, just like the working screen. */
object GlassesPhotoCapture {
    suspend fun capture(context: Context): Bitmap = coroutineScope {
        check(Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull() == PermissionStatus.Granted) {
            "Camera permission is missing. Open TurboMeta and run Quick Vision once."
        }
        val selector = AutoDeviceSelector()
        withTimeoutOrNull(8000) { selector.activeDevice(Wearables.devices).first { it != null } }
            ?: error("Glasses are not ready. Check they are connected and being worn.")
        // SCO teardown is asynchronous; let the glasses leave their microphone session first.
        delay(800)
        val session = Wearables.startStreamSession(context, selector,
            StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24))
        val frames = launch { session.videoStream.collect { /* Drain the stream while capturing. */ } }
        try {
            withTimeoutOrNull(15000) { session.state.first { it == StreamSessionState.STREAMING } }
                ?: error("The glasses camera did not become ready. Try again with TurboMeta open.")
            delay(500)
            val photo = withTimeoutOrNull(8000) { session.capturePhoto() }
                ?: error("The glasses did not return a photo. Please try again.")
            when (val data = photo.getOrThrow()) {
                is PhotoData.Bitmap -> data.bitmap
                is PhotoData.HEIC -> {
                    val bytes = ByteArray(data.data.remaining())
                    data.data.get(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: error("The captured photo could not be decoded.")
                }
            }
        } finally {
            frames.cancel()
            session.close()
        }
    }
}

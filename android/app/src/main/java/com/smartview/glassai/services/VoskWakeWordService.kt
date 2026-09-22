package com.smartview.glassai.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.os.IBinder
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.smartview.glassai.MainActivity
import com.smartview.glassai.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.Locale

/**
 * One worker owns download, model, recorder and audio route. Stop cancels it;
 * only its finally block releases resources. Features obtain the same gate.
 */
class VoskWakeWordService : Service() {
    companion object {
        const val ACTION_START = "com.smartview.glassai.START_WAKE_WORD"
        const val ACTION_STOP = "com.smartview.glassai.STOP_WAKE_WORD"
        private const val CHANNEL = "vosk_wake_word_channel"
        private const val NOTIFICATION = 1001
        private const val RATE = 16000
        private val mutableEnabled = MutableStateFlow(false)
        val enabled = mutableEnabled.asStateFlow()
        private val mutableStatus = MutableStateFlow("Wake phrase is off")
        val status = mutableStatus.asStateFlow()
        private val phrases = setOf("hey vision", "hey vision please")
        private const val GRAMMAR = "[\"hey vision\", \"hey vision please\", \"[unk]\"]"

        @SuppressLint("MissingPermission")
        fun availableMicrophones(context: Context): List<AudioDeviceInfo> {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return emptyList()
            return context.getSystemService(AudioManager::class.java).availableCommunicationDevices.filter {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        }
        private fun deviceKey(device: AudioDeviceInfo) = device.address.ifBlank { "${device.type}:${device.productName}" }
        fun selectMicrophone(context: Context, device: AudioDeviceInfo) {
            context.getSharedPreferences("wake_phrase", Context.MODE_PRIVATE).edit()
                .putString("device", deviceKey(device)).apply()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null
    @Volatile private var wanted = false
    @Volatile private var destroyed = false
    private lateinit var audio: AudioManager
    private var lastTrigger = -10000L

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Wake phrase", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            wanted = false
            mutableEnabled.value = false
            mutableStatus.value = "Wake phrase is off"
            worker?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (intent?.action == ACTION_START) {
            // Foreground immediately, before download, device lookup or routing.
            try {
                startForeground(NOTIFICATION, notification("Preparing offline wake phrase…"))
                require(hasPermissions()) { "Grant microphone and Nearby devices permissions, then enable again." }
                wanted = true
                mutableEnabled.value = true
                startWorker()
            } catch (error: Exception) { fail(error.message ?: "Cannot start microphone service") }
        } else {
            // Never silently reopen a microphone following a killed process.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun hasPermissions() = listOf(
        Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT
    ).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun report(message: String) {
        mutableStatus.value = message
        if (!destroyed && wanted) getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION, notification(message))
    }

    private fun fail(message: String) {
        wanted = false
        mutableEnabled.value = false
        mutableStatus.value = message
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startWorker() {
        if (worker != null) return
        worker = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    report("Downloading or checking offline model…")
                    val path = VoskModelInstaller.ensureInstalled(this@VoskWakeWordService)
                    ensureActive()
                    Model(path.absolutePath).use { model ->
                        while (isActive) {
                            if (audio.mode == AudioManager.MODE_IN_CALL) {
                                report("Paused during a phone call")
                                delay(1000)
                                continue
                            }
                            if (GlassesAudioGate.featureRequests.value > 0) {
                                report("Paused while another glasses feature is active")
                                delay(250)
                                continue
                            }
                            check(hasPermissions()) { "Microphone or Nearby devices permission was removed." }
                            val device = selectGlasses()
                            if (device == null) {
                                report("Connect your Meta glasses; disconnect other Bluetooth headsets if ambiguous")
                                delay(1500)
                                continue
                            }
                            val lease = GlassesAudioGate.tryAcquireWake()
                            if (lease == null) { delay(100); continue }
                            val triggered = try { record(model, device) } finally { lease.close() }
                            ensureActive()
                            if (triggered) {
                                // The recorder and route have already been closed.
                                withContext(Dispatchers.Main) {
                                    startForegroundService(Intent(this@VoskWakeWordService, QuickVisionService::class.java)
                                        .setAction(QuickVisionService.ACTION_CAPTURE_AND_ANALYZE))
                                }
                                // Wait for the Quick Vision service to request its lease.
                                delay(500)
                            } else delay(1000)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error.message ?: "Wake phrase failed; switch off/on to retry")
            } finally {
                worker = null
                // A rapid off/on may arrive while the previous recorder is closing.
                if (wanted && !destroyed) startWorker()
                if (!wanted && mutableStatus.value.startsWith("Listening")) mutableStatus.value = "Wake phrase is off"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun selectGlasses(): AudioDeviceInfo? {
        val devices = availableMicrophones(this)
        val chosen = getSharedPreferences("wake_phrase", MODE_PRIVATE).getString("device", null)
        if (chosen != null) return devices.singleOrNull { deviceKey(it) == chosen }
        val likelyGlasses = devices.filter {
            val name = it.productName.toString().lowercase(Locale.ROOT)
            listOf("ray-ban", "ray ban", "rayban", "oakley", "meta").any(name::contains)
        }
        // Do not silently record a phone mic or an arbitrary second headset.
        return likelyGlasses.singleOrNull()
    }

    @SuppressLint("MissingPermission")
    private suspend fun record(model: Model, device: AudioDeviceInfo): Boolean {
        val previousMode = audio.mode
        val previousDevice = audio.communicationDevice
        var routeOwned = false
        var modeChanged = false
        var recorder: AudioRecord? = null
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    mutableStatus.value = "Wake phrase stopped for another audio app; enable again when ready"
                    wanted = false
                    mutableEnabled.value = false
                    worker?.cancel()
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper())).build()
        var focusOwned = false
        try {
            focusOwned = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!focusOwned) { report("Waiting for microphone audio focus"); return false }
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            modeChanged = true
            check(audio.setCommunicationDevice(device)) { "Could not select the glasses microphone" }
            routeOwned = true
            // Routing is asynchronous. Confirm it before accepting any speech.
            withTimeout(5000) {
                while (audio.communicationDevice?.id != device.id) delay(50)
            }
            val size = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(size > 0) { "Unsupported microphone format" }
            val capture = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(size * 2, 6400)).build()
            recorder = capture
            check(capture.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize the glasses microphone" }
            // Input/output device IDs may differ. Match address/type after recording starts.
            capture.startRecording()
            withTimeout(5000) {
                while (!isGlassesRoute(capture.routedDevice, device)) delay(50)
            }
            report("Listening on ${device.productName}: say Hey Vision")
            Recognizer(model, RATE.toFloat(), GRAMMAR).use { recognizer ->
                val samples = ShortArray(1600)
                while (currentCoroutineContext().isActive && GlassesAudioGate.featureRequests.value == 0) {
                    if (!isGlassesRoute(capture.routedDevice, device) ||
                        audio.communicationDevice?.id != device.id) return false
                    val count = capture.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0) { "Glasses microphone disconnected (audio error $count)" }
                    if (count == 0) { delay(20); continue }
                    val complete = recognizer.acceptWaveForm(samples, count)
                    val result = JSONObject(if (complete) recognizer.result else recognizer.partialResult)
                    val text = result.optString("text").ifBlank { result.optString("partial") }
                        .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
                    val now = SystemClock.elapsedRealtime()
                    if (text in phrases && now - lastTrigger >= 10000) {
                        lastTrigger = now
                        return true
                    }
                    if (complete) recognizer.reset()
                }
            }
            return false
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            report("Glasses audio route is not ready; retrying…")
            return false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (permission: SecurityException) {
            throw permission
        } catch (error: Exception) {
            report("Microphone unavailable; reconnect glasses. Retrying…")
            return false
        } finally {
            recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }
            runCatching {
                val stillOwnsRoute = routeOwned && audio.communicationDevice?.id == device.id
                if (stillOwnsRoute) {
                    if (previousDevice != null && audio.availableCommunicationDevices.any { it.id == previousDevice.id })
                        audio.setCommunicationDevice(previousDevice)
                    else audio.clearCommunicationDevice()
                }
                if (modeChanged && (stillOwnsRoute || !routeOwned) && audio.mode == AudioManager.MODE_IN_COMMUNICATION)
                    audio.mode = previousMode
            }
            if (focusOwned) runCatching { audio.abandonAudioFocusRequest(focus) }
        }
    }

    private fun isGlassesRoute(input: AudioDeviceInfo?, selected: AudioDeviceInfo): Boolean =
        input != null && input.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET) &&
            (input.id == selected.id ||
                (selected.address.isNotBlank() && input.address == selected.address))

    override fun onDestroy() {
        destroyed = true
        wanted = false
        mutableEnabled.value = false
        worker?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(message: String): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, VoskWakeWordService::class.java)
            .setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("TurboMeta wake phrase").setContentText(message)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "Stop listening", stop).setOngoing(true).setOnlyAlertOnce(true).build()
    }
}

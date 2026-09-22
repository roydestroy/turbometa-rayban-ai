package com.smartview.glassai.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.smartview.glassai.MainActivity
import com.smartview.glassai.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * No-key, on-device wake phrase service.
 *
 * Vosk listens only for a small English grammar while the glasses' Bluetooth HFP
 * microphone is selected. Saying "hey vision" starts TurboMeta Quick Vision.
 */
class VoskWakeWordService : Service() {
    companion object {
        private const val TAG = "VoskWakeWordService"
        private const val CHANNEL_ID = "vosk_wake_word_channel"
        private const val NOTIFICATION_ID = 1001
        private const val SAMPLE_RATE = 16_000
        private const val DEBOUNCE_MS = 10_000L

        const val ACTION_START = "com.smartview.glassai.START_WAKE_WORD"
        const val ACTION_STOP = "com.smartview.glassai.STOP_WAKE_WORD"
        const val ACTION_WAKE_WORD_DETECTED = "com.smartview.glassai.WAKE_WORD_DETECTED"

        private val wakePhrases = setOf("hey vision", "hey vision please")
        private const val grammar = "[\"hey vision\", \"hey vision please\", \"[unk]\"]"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listeningJob: Job? = null
    private var activeRecorder: AudioRecord? = null
    private var selectedDevice: AudioDeviceInfo? = null
    private lateinit var audioManager: AudioManager

    @Volatile private var isProcessing = false
    @Volatile private var lastTriggerTime = 0L

    private val quickVisionStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(QuickVisionService.EXTRA_STATUS)) {
                "finished", "error" -> isProcessing = false
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        createNotificationChannel()
        val filter = IntentFilter(QuickVisionService.ACTION_QUICK_VISION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(quickVisionStatusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(quickVisionStatusReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopListening()
            else -> startListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening(stopService = false)
        runCatching { unregisterReceiver(quickVisionStatusReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    private fun startListening() {
        if (listeningJob != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Microphone permission not granted")
            stopSelf()
            return
        }
        val device = audioManager.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        } ?: run {
            Log.e(TAG, "No Bluetooth communication microphone available")
            stopSelf()
            return
        }
        if (!audioManager.setCommunicationDevice(device)) {
            Log.e(TAG, "Could not select glasses microphone")
            stopSelf()
            return
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        selectedDevice = device
        startForeground(NOTIFICATION_ID, createNotification())
        listeningJob = scope.launch { listen(device) }
    }

    private fun listen(device: AudioDeviceInfo) {
        val modelDirectory = try {
            VoskModelInstaller.ensureInstalled(this)
        } catch (error: Exception) {
            Log.e(TAG, "Could not prepare Vosk model", error)
            stopListening()
            return
        }
        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimumBuffer <= 0) {
            stopListening()
            return
        }
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build())
            .setBufferSizeInBytes(minimumBuffer * 2)
            .build()
        recorder.preferredDevice = device
        activeRecorder = recorder
        try {
            Model(modelDirectory.absolutePath).use { model ->
                Recognizer(model, SAMPLE_RATE.toFloat(), grammar).use { recognizer ->
                    val samples = ShortArray(minimumBuffer / 2)
                    recorder.startRecording()
                    Log.i(TAG, "Listening for 'hey vision' via ${device.productName}")
                    while (listeningJob != null) {
                        val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                        if (count <= 0) continue
                        val completed = recognizer.acceptWaveForm(samples, count)
                        val result = JSONObject(if (completed) recognizer.result else recognizer.partialResult)
                        val text = result.optString("text").ifBlank { result.optString("partial") }
                        if (normalize(text) in wakePhrases) triggerQuickVision()
                        if (completed) recognizer.reset()
                    }
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Wake phrase listener failed", error)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            activeRecorder = null
            releaseAudioRoute()
            listeningJob = null
        }
    }

    private fun triggerQuickVision() {
        val now = System.currentTimeMillis()
        if (isProcessing || now - lastTriggerTime < DEBOUNCE_MS) return
        lastTriggerTime = now
        isProcessing = true
        sendBroadcast(Intent(ACTION_WAKE_WORD_DETECTED).setPackage(packageName))
        val intent = Intent(this, QuickVisionService::class.java).apply {
            action = QuickVisionService.ACTION_CAPTURE_AND_ANALYZE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopListening(stopService: Boolean = true) {
        listeningJob?.cancel()
        listeningJob = null
        activeRecorder?.let { recorder ->
            runCatching { recorder.stop() }
            recorder.release()
        }
        activeRecorder = null
        releaseAudioRoute()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (stopService) stopSelf()
    }

    private fun releaseAudioRoute() {
        selectedDevice?.let { audioManager.clearCommunicationDevice() }
        selectedDevice = null
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Quick Vision wake phrase", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Quick Vision is listening")
        .setContentText("Say 'Hey Vision' to describe what you see")
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true)
        .build()

    private fun normalize(value: String) = value.lowercase().trim().replace(Regex("\\s+"), " ")
}

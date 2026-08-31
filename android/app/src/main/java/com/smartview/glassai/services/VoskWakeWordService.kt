package com.smartview.glassai.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.smartview.glassai.MainActivity
import com.smartview.glassai.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Vosk Wake Word Detection Service
 * Foreground service for continuous, fully offline wake word detection ("Jarvis")
 * using the open-source Vosk recognizer - no account or API key required.
 * Triggers Quick Vision when the wake word is detected.
 */
class VoskWakeWordService : Service(), RecognitionListener {

    companion object {
        private const val TAG = "VoskWakeWordService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "vosk_wake_word_channel"
        private const val SAMPLE_RATE = 16000.0f
        private const val WAKE_WORD = "jarvis"

        // Action for wake word detected broadcast
        const val ACTION_WAKE_WORD_DETECTED = "com.smartview.glassai.WAKE_WORD_DETECTED"

        // Service control actions
        const val ACTION_START = "com.smartview.glassai.START_WAKE_WORD"
        const val ACTION_STOP = "com.smartview.glassai.STOP_WAKE_WORD"

        // Debounce: prevent multiple triggers within this time window
        private const val DEBOUNCE_MS = 10000L // 10 seconds
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var isListening = false

    // Debounce: track last trigger time to prevent multiple rapid triggers
    @Volatile
    private var lastTriggerTime = 0L
    @Volatile
    private var isProcessing = false

    // Broadcast receiver to listen for QuickVisionService completion
    private val quickVisionStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(QuickVisionService.EXTRA_STATUS)
            Log.d(TAG, "QuickVision status received: $status")
            if (status == "finished" || status == "error") {
                isProcessing = false
                Log.d(TAG, "Reset isProcessing to false")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Register receiver to listen for QuickVisionService status
        val filter = IntentFilter(QuickVisionService.ACTION_QUICK_VISION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(quickVisionStatusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(quickVisionStatusReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startWakeWordDetection()
            ACTION_STOP -> stopWakeWordDetection()
            else -> startWakeWordDetection()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(quickVisionStatusReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }
        stopWakeWordDetection()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startWakeWordDetection() {
        if (isListening) {
            Log.d(TAG, "Already listening for wake word")
            return
        }

        // Check microphone permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Microphone permission not granted")
            stopSelf()
            return
        }

        // The model must already be downloaded - Settings triggers the download
        // before ever starting this service.
        if (!VoskModelManager.isModelReady(this)) {
            Log.e(TAG, "Vosk model not downloaded yet")
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, createNotification())

        // Loading the model touches disk and can take a moment, so keep it off the main thread.
        serviceScope.launch {
            try {
                val loadedModel = Model(VoskModelManager.getModelDir(this@VoskWakeWordService).absolutePath)
                // Restrict recognition to the wake word (plus catch-all) for accuracy and low CPU usage.
                val loadedRecognizer = Recognizer(loadedModel, SAMPLE_RATE, "[\"$WAKE_WORD\", \"[unk]\"]")
                model = loadedModel
                recognizer = loadedRecognizer

                val service = SpeechService(loadedRecognizer, SAMPLE_RATE)
                speechService = service
                service.startListening(this@VoskWakeWordService)
                isListening = true

                Log.d(TAG, "Wake word detection started - listening for \"$WAKE_WORD\"")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Vosk: ${e.message}", e)
                stopSelf()
            }
        }
    }

    private fun stopWakeWordDetection() {
        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
            recognizer?.close()
            recognizer = null
            model?.close()
            model = null
            isListening = false
            Log.d(TAG, "Wake word detection stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Vosk: ${e.message}")
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleHypothesis(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return

        val text = try {
            JSONObject(hypothesis).optString("text")
        } catch (e: Exception) {
            ""
        }
        if (!text.contains(WAKE_WORD, ignoreCase = true)) return

        val currentTime = System.currentTimeMillis()

        // Debounce: ignore if already processing or triggered recently
        if (isProcessing) {
            Log.d(TAG, "Wake word detected but already processing, ignoring")
            return
        }
        if (currentTime - lastTriggerTime < DEBOUNCE_MS) {
            Log.d(TAG, "Wake word detected but within debounce window (${currentTime - lastTriggerTime}ms), ignoring")
            return
        }

        Log.d(TAG, "Wake word detected!")
        lastTriggerTime = currentTime
        isProcessing = true

        // Broadcast wake word detection
        val intent = Intent(ACTION_WAKE_WORD_DETECTED).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)

        // Trigger Quick Vision
        triggerQuickVision()
    }

    override fun onPartialResult(hypothesis: String?) = handleHypothesis(hypothesis)

    override fun onResult(hypothesis: String?) = handleHypothesis(hypothesis)

    override fun onFinalResult(hypothesis: String?) = handleHypothesis(hypothesis)

    override fun onError(exception: Exception?) {
        Log.e(TAG, "Vosk recognition error: ${exception?.message}")
    }

    override fun onTimeout() {
        // Vosk's recognizer stops itself after its internal silence timeout - restart it
        // immediately so wake word detection stays continuous.
        speechService?.startListening(this)
    }

    private fun triggerQuickVision() {
        Log.d(TAG, "Triggering Quick Vision...")

        // Start QuickVisionService to capture and analyze image
        val quickVisionIntent = Intent(this, QuickVisionService::class.java).apply {
            action = QuickVisionService.ACTION_CAPTURE_AND_ANALYZE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(quickVisionIntent)
        } else {
            startService(quickVisionIntent)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Quick Vision Wake Word",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Listening for wake word to trigger Quick Vision"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, VoskWakeWordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Quick Vision Active")
            .setContentText("Say \"Jarvis\" to identify what you see")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}

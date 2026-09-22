package com.smartview.glassai.services

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.smartview.glassai.MainActivity
import com.smartview.glassai.R
import com.smartview.glassai.data.QuickVisionStorage
import com.smartview.glassai.managers.APIProviderManager
import com.smartview.glassai.managers.QuickVisionModeManager
import com.smartview.glassai.utils.APIKeyManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/** Owns the feature lease through capture, network work and completed speech. */
class QuickVisionService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_CAPTURE_AND_ANALYZE = "com.smartview.glassai.CAPTURE_AND_ANALYZE"
        const val ACTION_ASSISTANT = "com.smartview.glassai.ASSISTANT"
        const val EXTRA_QUESTION = "question"
        const val ACTION_STOP = "com.smartview.glassai.STOP_QUICK_VISION"
        const val ACTION_ANALYSIS_COMPLETE = "com.smartview.glassai.ANALYSIS_COMPLETE"
        const val ACTION_QUICK_VISION_STATUS = "com.smartview.glassai.QUICK_VISION_STATUS"
        const val EXTRA_RESULT = "analysis_result"
        const val EXTRA_ERROR = "analysis_error"
        const val EXTRA_STATUS = "status"
        private const val CHANNEL = "quick_vision_channel"
        private const val NOTIFICATION = 1002
        private val mutableStatus = MutableStateFlow("No assistant request yet")
        val status = mutableStatus.asStateFlow()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var tts: TextToSpeech? = null
    private val ready = CompletableDeferred<Boolean>()
    private lateinit var keys: APIKeyManager

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        keys = APIKeyManager.getInstance(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Glasses assistant", NotificationManager.IMPORTANCE_LOW))
        tts = TextToSpeech(this, this)
    }
    override fun onInit(status: Int) {
        val language = tts?.setLanguage(Locale.forLanguageTag(keys.getOutputLanguage()))
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED)
            tts?.setLanguage(Locale.US)
        tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        ready.complete(status == TextToSpeech.SUCCESS)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { job?.cancel(); stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION, notification("Preparing assistant…"))
        if (job?.isActive == true) return START_NOT_STICKY
        val question = intent?.getStringExtra(EXTRA_QUESTION)?.trim()?.take(2000)
        val assistant = intent?.action == ACTION_ASSISTANT
        if (assistant && question.isNullOrBlank()) { stopSelf(); return START_NOT_STICKY }
        job = scope.launch {
            var lease: GlassesAudioGate.Lease? = null
            try {
                lease = GlassesAudioGate.acquireFeature()
                report(if (assistant) "Heard: $question" else "Opening glasses camera…")
                val providers = APIProviderManager.getInstance(this@QuickVisionService)
                val vision = VisionAPIService(keys, providers, this@QuickVisionService)
                val result = if (assistant) {
                    AssistantEngine(this@QuickVisionService, ::report).answer(requireNotNull(question))
                } else {
                    val image = GlassesPhotoCapture.capture(this@QuickVisionService)
                    report("Photo captured. Analyzing…")
                    val description = vision.quickVision(image, keys.getOutputLanguage()).getOrThrow()
                    val modes = QuickVisionModeManager.getInstance(this@QuickVisionService)
                    QuickVisionStorage.getInstance(this@QuickVisionService).saveRecord(
                        bitmap = image, prompt = modes.getPrompt(), result = description,
                        mode = modes.currentMode.value, visionModel = providers.selectedModel.value)
                    description
                }
                report(result)
                sendBroadcast(Intent(ACTION_ANALYSIS_COMPLETE).setPackage(packageName).putExtra(EXTRA_RESULT, result))
                speakAndWait(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = error.message?.take(350) ?: "The request failed. Check your AI provider settings."
                report(message)
                sendBroadcast(Intent(ACTION_ANALYSIS_COMPLETE).setPackage(packageName).putExtra(EXTRA_ERROR, message))
                speakAndWait(message)
            } finally {
                tts?.stop()
                lease?.close()
                sendBroadcast(Intent(ACTION_QUICK_VISION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, "finished"))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun report(message: String) {
        mutableStatus.value = message
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message))
    }
    private suspend fun speakAndWait(text: String) {
        if (withTimeoutOrNull(8000) { ready.await() } != true) return
        val utterance = UUID.randomUUID().toString()
        val finished = CompletableDeferred<Unit>()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (id == utterance) finished.complete(Unit) }
            override fun onError(id: String?) { if (id == utterance) finished.complete(Unit) }
            override fun onStop(id: String?, interrupted: Boolean) { if (id == utterance) finished.complete(Unit) }
        })
        try {
            if (tts?.speak(text.take(3900), TextToSpeech.QUEUE_FLUSH, null, utterance) == TextToSpeech.SUCCESS)
                withTimeoutOrNull(120000) { finished.await() }
            delay(250)
        } finally { tts?.stop() }
    }
    private fun notification(message: String): Notification {
        val stop = PendingIntent.getService(this, 2, Intent(this, QuickVisionService::class.java)
            .setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("TurboMeta assistant").setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "Stop", stop).setOnlyAlertOnce(true).setOngoing(true).build()
    }
    override fun onDestroy() {
        scope.cancel()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

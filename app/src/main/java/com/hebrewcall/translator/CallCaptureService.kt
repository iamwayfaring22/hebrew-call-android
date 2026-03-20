package com.hebrewcall.translator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.Binder
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

class CallCaptureService : Service() {

    companion object {
        private const val TAG = "CallCaptureService"
        private const val CHANNEL_ID = "call_translator_channel"
        private const val NOTIF_ID = 1
    }

    inner class LocalBinder : Binder() {
        fun getService(): CallCaptureService = this@CallCaptureService
    }

    private val binder = LocalBinder()
    private var speechRecognizer: SpeechRecognizer? = null
    private var sourceLang = "he-IL"
    private var targetLang = "ru"
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isListening = false

    var onTranslation: ((String, String) -> Unit)? = null

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sourceLang = intent?.getStringExtra("lang") ?: "he-IL"
        targetLang = intent?.getStringExtra("targetLang") ?: "ru"
        startForeground(NOTIF_ID, buildNotification())
        startListening()
        return START_STICKY
    }

    private fun startListening() {
        if (isListening) return
        isListening = true
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}

            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: return
                Log.d(TAG, "Recognized: $text")
                serviceScope.launch {
                    try {
                        val from = sourceLang.take(2)
                        val translated = translate(text, from, targetLang)
                        onTranslation?.invoke(text, translated)
                    } catch (e: Exception) {
                        Log.e(TAG, "Translation error", e)
                        onTranslation?.invoke(text, "[translation error]")
                    }
                    if (isListening) {
                        delay(200)
                        startListeningSession()
                    }
                }
            }

            override fun onError(error: Int) {
                Log.w(TAG, "Speech error: $error")
                serviceScope.launch {
                    if (isListening) {
                        delay(500)
                        startListeningSession()
                    }
                }
            }
        })
        startListeningSession()
    }

    private fun startListeningSession() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, sourceLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening error", e)
        }
    }

    private suspend fun translate(text: String, from: String, to: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(text, "UTF-8")
                val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$from&tl=$to&dt=t&q=$encoded")
                val conn = url.openConnection() as HttpsURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val arr = JSONArray(response)
                val parts = arr.getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    val chunk = parts.getJSONArray(i)
                    if (!chunk.isNull(0)) sb.append(chunk.getString(0))
                }
                sb.toString()
            } catch (e: Exception) {
                "[error: ${e.message}]"
            }
        }
    }

    private fun stopListening() {
        isListening = false
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        serviceScope.cancel()
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "מתרגם שיחות",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("מתרגם שיחות")
            .setContentText("מאזין ומתרגם...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

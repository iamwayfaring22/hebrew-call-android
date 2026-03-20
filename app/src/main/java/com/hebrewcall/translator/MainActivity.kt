package com.hebrewcall.translator

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQ_MEDIA_PROJECTION = 1001
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var speechRecognizer: SpeechRecognizer? = null
    private var isRunning = false
    private var direction = "he->ru"

    // Live message views updated in real time
    private var currentContainer: LinearLayout? = null
    private var currentTvOrig: TextView? = null
    private var currentTvTrans: TextView? = null
    private var currentOrigText = ""

    // UI
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnHe: Button
    private lateinit var btnRu: Button
    private lateinit var tvStatus: TextView
    private lateinit var llMessages: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var btnClear: Button

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        btnStart   = findViewById(R.id.btnStart)
        btnStop    = findViewById(R.id.btnStop)
        btnHe      = findViewById(R.id.btnHe)
        btnRu      = findViewById(R.id.btnRu)
        tvStatus   = findViewById(R.id.tvStatus)
        llMessages = findViewById(R.id.llMessages)
        scrollView = findViewById(R.id.scrollView)
        btnClear   = findViewById(R.id.btnClear)

        btnHe.isSelected = true
        btnHe.setOnClickListener { if (!isRunning) { direction = "he->ru"; btnHe.isSelected = true;  btnRu.isSelected = false } }
        btnRu.setOnClickListener { if (!isRunning) { direction = "ru->he"; btnRu.isSelected = true;  btnHe.isSelected = false } }
        btnStart.setOnClickListener  { requestMediaProjection() }
        btnStop.setOnClickListener   { stopListening() }
        btnClear.setOnClickListener  { llMessages.removeAllViews() }
    }

    private fun requestMediaProjection() {
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQ_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                startListening()
            } else {
                setStatus("Отказано разрешение захвата аудио")
            }
        }
    }

    private fun startListening() {
        isRunning = true
        btnStart.visibility = View.GONE
        btnStop.visibility  = View.VISIBLE
        setStatus("Слушаю...")
        startSpeechRecognition()
    }

    private fun startSpeechRecognition() {
        if (!isRunning) return
        val lang = if (direction == "he->ru") "iw-IL" else "ru-RU"

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { setStatus("Слушаю...") }
            override fun onBeginningOfSpeech() { setStatus("Речь обнаружена") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { setStatus("Обработка...") }

            override fun onPartialResults(partialResults: Bundle?) {
                // Show partial text live in the current message row
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                if (partial.isBlank()) return
                runOnUiThread {
                    if (currentContainer == null) createLiveRow()
                    currentTvOrig?.text = partial
                    currentOrigText = partial
                    scrollToBottom()
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: return
                if (text.isBlank()) {
                    if (isRunning) startSpeechRecognition()
                    return
                }
                setStatus("Перевожу...")
                scope.launch {
                    val (from, to) = if (direction == "he->ru") "iw" to "ru" else "ru" to "iw"
                    // Update orig text to final recognized text
                    runOnUiThread {
                        if (currentContainer == null) createLiveRow()
                        currentTvOrig?.text = text
                        currentOrigText = text
                    }
                    val translated = translate(text, from, to)
                    runOnUiThread {
                        currentTvTrans?.text = translated
                        scrollToBottom()
                        // Seal the current row — next speech gets a new row
                        currentContainer = null
                        currentTvOrig    = null
                        currentTvTrans   = null
                        currentOrigText  = ""
                    }
                    setStatus("Слушаю...")
                    if (isRunning) startSpeechRecognition()
                }
            }

            override fun onError(error: Int) {
                // Seal any in-progress row silently
                runOnUiThread {
                    currentContainer = null
                    currentTvOrig    = null
                    currentTvTrans   = null
                    currentOrigText  = ""
                }
                if (isRunning) {
                    scope.launch {
                        delay(300)
                        startSpeechRecognition()
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Wait longer for silence before ending a phrase
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
        }
        speechRecognizer?.startListening(intent)
    }

    /**
     * Create a new live message row at the bottom of the list.
     * The original text and translation will be updated in-place.
     */
    private fun createLiveRow() {
        val isHeRu = direction == "he->ru"
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(0xFF0D0D0D.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 8) }
        }
        val tvOrig = TextView(this).apply {
            text = ""
            textSize = 15f
            setTextColor(if (isHeRu) 0xFFC8F565.toInt() else 0xFF93C5FD.toInt())
            if (isHeRu) textAlignment = View.TEXT_ALIGNMENT_TEXT_END
        }
        val tvTrans = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(if (isHeRu) 0xFFE0E0E0.toInt() else 0xFF93C5FD.toInt())
            setPadding(0, 8, 0, 0)
        }
        container.addView(tvOrig)
        container.addView(tvTrans)
        llMessages.addView(container)

        currentContainer = container
        currentTvOrig    = tvOrig
        currentTvTrans   = tvTrans
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun stopListening() {
        isRunning = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        currentContainer = null
        currentTvOrig    = null
        currentTvTrans   = null
        currentOrigText  = ""
        btnStart.visibility = View.VISIBLE
        btnStop.visibility  = View.GONE
        setStatus("Остановлено")
    }

    private fun setStatus(text: String) {
        runOnUiThread { tvStatus.text = text }
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
                "[ошибка: ${e.message}]"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        scope.cancel()
    }
}

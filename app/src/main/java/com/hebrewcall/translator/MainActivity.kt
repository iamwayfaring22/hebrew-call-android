package com.hebrewcall.translator

import android.content.Intent
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
        btnStart.setOnClickListener  { startListening() }
        btnStop.setOnClickListener   { stopListening() }
        btnClear.setOnClickListener  { llMessages.removeAllViews() }
    }

    private fun startListening() {
        isRunning = true
        btnStart.visibility = View.GONE
        btnStop.visibility  = View.VISIBLE
        setStatus("Слушаю...", 0xFFFFFFFF.toInt())
        startSpeechRecognition()
    }

    private fun startSpeechRecognition() {
        if (!isRunning) return
        val lang = if (direction == "he->ru") "iw-IL" else "ru-RU"

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { setStatus("Слушаю...", 0xFFFFFFFF.toInt()) }
            override fun onBeginningOfSpeech() { setStatus("Речь обнаружена", 0xFF4CAF50.toInt()) }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { setStatus("Обработка...", 0xFFFFFFFF.toInt()) }

            override fun onPartialResults(partialResults: Bundle?) {
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
                setStatus("Перевожу...", 0xFFFFFFFF.toInt())
                scope.launch {
                    val (from, to) = if (direction == "he->ru") "iw" to "ru" else "ru" to "iw"
                    runOnUiThread {
                        if (currentContainer == null) createLiveRow()
                        currentTvOrig?.text = text
                        currentOrigText = text
                    }
                    val translated = translate(text, from, to)
                    runOnUiThread {
                        currentTvTrans?.text = translated
                        scrollToBottom()
                        currentContainer = null
                        currentTvOrig    = null
                        currentTvTrans   = null
                        currentOrigText  = ""
                    }
                    setStatus("Слушаю...", 0xFFFFFFFF.toInt())
                    if (isRunning) startSpeechRecognition()
                }
            }

            override fun onError(error: Int) {
                val errorMsg = when(error) {
                    1 -> "ERROR_NETWORK (1)"
                    2 -> "ERROR_SERVER (2)"
                    3 -> "ERROR_AUDIO (3)"
                    4 -> "ERROR_CLIENT (4)"
                    5 -> "ERROR_SPEECH_TIMEOUT (5) - слишком тихо"
                    6 -> "ERROR_NO_MATCH (6) - не распознал"
                    7 -> "ERROR_NO_MATCH (7) - не распознал"
                    8 -> "ERROR_RECOGNIZER_BUSY (8)"
                    9 -> "ERROR_INSUFFICIENT_PERMISSIONS (9) - нет разрешения"
                    else -> "ERROR ($error)"
                }
                setStatus(errorMsg, 0xFFFF5252.toInt())
                runOnUiThread {
                    currentContainer = null
                    currentTvOrig    = null
                    currentTvTrans   = null
                    currentOrigText  = ""
                }
                if (isRunning && error !in listOf(8, 9)) {
                    scope.launch {
                        delay(if (error in 6..7) 500 else 1000)
                        if (isRunning) startSpeechRecognition()
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
        }
        speechRecognizer?.startListening(intent)
    }

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
        setStatus("Остановлено", 0xFFFFFFFF.toInt())
    }

    private fun setStatus(text: String, color: Int) {
        runOnUiThread { 
            tvStatus.text = text
            tvStatus.setTextColor(color)
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

package com.hebrewcall.translator

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hebrewcall.translator.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var captureService: CallCaptureService? = null
    private var isBound = false
    private var isRunning = false
    private var direction = Direction.HE_TO_RU

    enum class Direction { HE_TO_RU, RU_TO_HE }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as CallCaptureService.LocalBinder
            captureService = binder.getService()
            isBound = true
            captureService?.onTranslation = { original, translated ->
                runOnUiThread {
                    appendTranslation(original, translated)
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
            captureService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        updateDirectionUI()

        binding.btnHeRu.setOnClickListener {
            if (!isRunning) {
                direction = Direction.HE_TO_RU
                updateDirectionUI()
            }
        }
        binding.btnRuHe.setOnClickListener {
            if (!isRunning) {
                direction = Direction.RU_TO_HE
                updateDirectionUI()
            }
        }

        binding.btnStartStop.setOnClickListener {
            if (isRunning) stopTranslation() else startTranslation()
        }

        binding.btnClear.setOnClickListener {
            binding.tvTranslations.text = ""
        }
    }

    private fun updateDirectionUI() {
        val heRuActive = direction == Direction.HE_TO_RU
        binding.btnHeRu.alpha = if (heRuActive) 1.0f else 0.4f
        binding.btnRuHe.alpha = if (!heRuActive) 1.0f else 0.4f
        binding.tvDirection.text = if (heRuActive) "ג'רסה - עברית ► רוסית" else "ג'רסה - רוסית ► עברית"
    }

    private fun startTranslation() {
        if (!hasPermissions()) {
            checkPermissions()
            return
        }
        isRunning = true
        binding.btnStartStop.text = "עצור / STOP"
        binding.btnStartStop.setBackgroundColor(getColor(R.color.stop_red))
        binding.btnHeRu.isEnabled = false
        binding.btnRuHe.isEnabled = false
        binding.tvStatus.text = "מאזין..."

        val lang = if (direction == Direction.HE_TO_RU) "he-IL" else "ru-RU"
        val targetLang = if (direction == Direction.HE_TO_RU) "ru" else "he"

        Intent(this, CallCaptureService::class.java).also { intent ->
            intent.putExtra("lang", lang)
            intent.putExtra("targetLang", targetLang)
            ContextCompat.startForegroundService(this, intent)
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun stopTranslation() {
        isRunning = false
        binding.btnStartStop.text = "התחל / START"
        binding.btnStartStop.setBackgroundColor(getColor(R.color.start_green))
        binding.btnHeRu.isEnabled = true
        binding.btnRuHe.isEnabled = true
        binding.tvStatus.text = "עצור"

        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        stopService(Intent(this, CallCaptureService::class.java))
    }

    private fun appendTranslation(original: String, translated: String) {
        val isHeRu = direction == Direction.HE_TO_RU
        val text = binding.tvTranslations.text.toString()
        val newEntry = if (isHeRu) {
            "🔵 $original\n➡️ $translated\n\n"
        } else {
            "🟢 $original\n➡️ $translated\n\n"
        }
        binding.tvTranslations.text = newEntry + text
        binding.tvStatus.text = "פעיל"
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun checkPermissions() {
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "אנא אפשר גישה למיקרופון", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}

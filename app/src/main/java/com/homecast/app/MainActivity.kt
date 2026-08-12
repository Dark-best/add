package com.homecast.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var serverInput: EditText

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startMirrorService(result.resultCode, result.data!!)
        } else {
            statusText.text = "permission refusée"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("homecast", Context.MODE_PRIVATE)
        statusText = findViewById(R.id.statusText)
        serverInput = findViewById(R.id.serverInput)
        val mirrorButton = findViewById<Button>(R.id.mirrorButton)

        serverInput.setText(prefs.getString("server_ip", ""))

        mirrorButton.setOnClickListener {
            vibrate()

            val serverIp = serverInput.text.toString().trim()
            if (serverIp.isEmpty()) {
                statusText.text = "entre l'IP du serveur d'abord"
                return@setOnClickListener
            }
            prefs.edit().putString("server_ip", serverIp).apply()

            statusText.text = "demande de permission écran..."
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    private fun startMirrorService(resultCode: Int, data: Intent) {
        statusText.text = "diffusion en cours"
        val serverIp = prefs.getString("server_ip", "") ?: ""

        val serviceIntent = Intent(this, MirrorService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
            putExtra("serverIp", serverIp)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(40)
        }
    }
}

package com.example.breakreminder.presentation

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private var mediaPlayer: MediaPlayer? = null

    override fun onPause() {
        super.onPause()
        mediaPlayer?.pause()
    }

    override fun onResume() {
        super.onResume()
        mediaPlayer?.start()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.BODY_SENSORS] == true) {
            Toast.makeText(this, "Heart rate is now being tracked", Toast.LENGTH_SHORT).show()
        } else {
            // Permission denied, handle accordingly
        }
    }

    private fun Color.toArgb(): Int {
        return ((alpha * 255).toInt() shl 24) or
                ((red * 255).toInt() shl 16) or
                ((green * 255).toInt() shl 8) or
                (blue * 255).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request BODY_SENSORS permission if not granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.BODY_SENSORS))
        }

        // SharedPreferences laden
        val prefs = getSharedPreferences("EISPreferences", Context.MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean("isDarkMode", false)
        val buttonColorStr = prefs.getInt("buttonColor", Color(0xFFFFC0CB).toArgb())
        val buttonTextColorStr = prefs.getInt("buttonTextColor", Color(0xFF4D2324).toArgb())
        val buttonColor = Color(buttonColorStr)
        val buttonTextColor = Color(buttonTextColorStr)
        val screenSelection = prefs.getString("ScreenSelection", "Grid") ?: "Grid"
        val firstStart = isFirstRun(prefs)

        setContent {
            EISApp(
                firstStart = firstStart,
                isDarkMode = isDarkMode,
                buttonColor = buttonColor,
                buttonTextColor = buttonTextColor,
                screenSelection = screenSelection
            )
        }
    }

    private fun isFirstRun(prefs: SharedPreferences): Boolean {
        val key = "firstRun"
        val firstRun = prefs.getBoolean(key, true)
        if (firstRun) {
            prefs.edit().putBoolean(key, false).apply()
        }
        return firstRun
    }
}
package com.example.breakreminder

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.breakreminder.background.AppForegroundState
import com.example.breakreminder.background.BreakMonitoringService
import com.example.breakreminder.background.BreakNotificationHelper
import com.example.breakreminder.sync.AppSettingsViewModel
import com.example.breakreminder.sync.AppSettingsViewModelFactory

class MainActivity : ComponentActivity(
) {
    private var mediaPlayer: MediaPlayer? = null
    private val appSettingsViewModel: AppSettingsViewModel by viewModels {
        AppSettingsViewModelFactory(application)
    }
    private var openBreakStartOnLaunch by mutableStateOf(false)

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
        }
        startBackgroundMonitoring()
    }

    override fun onStart() {
        super.onStart()
        AppForegroundState.isForeground = true
    }

    override fun onStop() {
        AppForegroundState.isForeground = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openBreakStartOnLaunch = shouldOpenBreakStart(intent)

        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest += Manifest.permission.BODY_SENSORS
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest += Manifest.permission.POST_NOTIFICATIONS
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            startBackgroundMonitoring()
        }

        setContent {
            EISApp(
                viewModel = appSettingsViewModel,
                openBreakStartOnLaunch = openBreakStartOnLaunch,
                onBreakStartLaunchConsumed = {
                    openBreakStartOnLaunch = false
                }
            )
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openBreakStartOnLaunch = shouldOpenBreakStart(intent)
    }

    private fun startBackgroundMonitoring() {
        try {
            BreakMonitoringService.start(this)
        } catch (_: Exception) {
        }
    }

    private fun shouldOpenBreakStart(intent: android.content.Intent?): Boolean {
        if (intent == null) return false
        return intent.action == BreakNotificationHelper.ACTION_OPEN_BREAK_START ||
            intent.getBooleanExtra(BreakNotificationHelper.EXTRA_OPEN_BREAK_START, false)
    }
}

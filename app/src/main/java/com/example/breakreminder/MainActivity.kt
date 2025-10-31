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
import androidx.core.content.ContextCompat
import com.example.breakreminder.sync.AppSettingsViewModel
import com.example.breakreminder.sync.AppSettingsViewModelFactory

class MainActivity : ComponentActivity(
) {
    private var mediaPlayer: MediaPlayer? = null
    private val appSettingsViewModel: AppSettingsViewModel by viewModels {
        AppSettingsViewModelFactory(application)
    }

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.BODY_SENSORS))
        }

        setContent {

            EISApp(viewModel = appSettingsViewModel)
        }
    }
}

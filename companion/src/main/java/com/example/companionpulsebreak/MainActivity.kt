package com.example.companionpulsebreak

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.example.companionpulsebreak.sync.CompanionSettingsViewModel
import com.example.companionpulsebreak.sync.CompanionSettingsViewModelFactory

class MainActivity : ComponentActivity(
) {
    private val appSettingsViewModel: CompanionSettingsViewModel by viewModels {
        CompanionSettingsViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CompanionApp(viewModel = appSettingsViewModel)
        }
    }
}

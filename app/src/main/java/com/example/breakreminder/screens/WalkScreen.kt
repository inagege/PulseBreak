package com.example.breakreminder.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.example.breakreminder.R
import com.example.breakreminder.sync.AppSettingsViewModel
import com.skydoves.landscapist.glide.GlideImage
import kotlinx.coroutines.delay

@Composable
fun WalkStartScreen(
    viewModel: AppSettingsViewModel,
    onStartWalk: () -> Unit = {}  // Navigation callback
) {
    val settings by viewModel.settings

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .offset(y = 10.dp)
                    .background(Color(settings.buttonColor), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsWalk,
                    contentDescription = "Walk Icon",
                    tint = Color(settings.buttonTextColor),
                    modifier = Modifier.size(15.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Ready to take a 5-minute walk?",
                fontSize = 18.sp,
                color = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(1.dp))

            Icon(
                imageVector = Icons.Default.DirectionsWalk,
                contentDescription = "Walking Icon",
                tint = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor),
                modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.height(3.dp))

            ElevatedButton(
                onClick = { onStartWalk() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(settings.buttonColor),
                    contentColor = Color(settings.buttonTextColor)
                ),
                modifier = Modifier
                    .width(170.dp)
                    .height(50.dp)
            ) {
                Text(text = "Start Walk", fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(20.dp)) // Keeps the bottom padding consistent
        }
    }
}

@Composable
fun WalkScreen(
    viewModel: AppSettingsViewModel,
    onBackToHome: () -> Unit = {}  // Callback when walk ends
) {
    val settings by viewModel.settings

    var remainingTime by remember { mutableStateOf(5 * 60) } // 5-minute countdown
    var isFinished by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (remainingTime > 0) {
            delay(1000)
            remainingTime--
        }
        isFinished = true
    }

    val minutes = remainingTime / 60
    val seconds = remainingTime % 60

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .offset(y = 10.dp)
                    .background(Color(settings.buttonColor), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsWalk,
                    contentDescription = "Walk Icon",
                    tint = Color(settings.buttonTextColor),
                    modifier = Modifier.size(15.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Time left: ${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}",
                fontSize = 18.sp,
                color = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(1.dp))

            GlideImage(
                imageModel = { "android.resource://com.example.breakreminder/${R.raw.walking_animation}" },
                modifier = Modifier
                    .width(70.dp)
                    .aspectRatio(200f / 175f)
            )

            Spacer(modifier = Modifier.height(5.dp))

            ElevatedButton(
                onClick = { onBackToHome() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(settings.buttonColor),
                    contentColor = Color(settings.buttonTextColor)
                ),
                modifier = Modifier
                    .width(170.dp)
                    .height(50.dp)
            ) {
                Text(text = "Back to Home", fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(20.dp)) // Keeps spacing consistent
        }
    }
}
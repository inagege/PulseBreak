package com.example.breakreminder.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.example.breakreminder.R
import com.skydoves.landscapist.glide.GlideImage
import kotlinx.coroutines.delay
import com.example.breakreminder.sync.AppSettingsViewModel

/**
 * 1) CoffeePromptScreen
 *   - Zeigt Kreis + Icon oben
 *   - Text "How about a coffee break?"
 *   - Button "Start machine!" -> navigiert zu CoffeeVideoScreen
 */
@Composable
fun CoffeePromptScreen(
    viewModel: AppSettingsViewModel,
    onStartMachine: () -> Unit = {}
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
            // Icon-Kreis oben
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .offset(y = 10.dp)
                    .background(Color(settings.buttonColor), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Coffee,
                    contentDescription = "Coffee Icon",
                    tint = Color(settings.buttonTextColor),
                    modifier = Modifier.size(15.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Text
            Text(
                text = "How about a coffee break?",
                fontSize = 24.sp,
                color = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Button "Start machine!"
            ElevatedButton(
                onClick = { onStartMachine() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(settings.buttonColor),
                    contentColor = Color(settings.buttonTextColor)
                ),
                modifier = Modifier
                    .width(170.dp)
                    .height(50.dp)
            ) {
                Text(text = "Start machine!", fontSize = 18.sp)
            }
        }
    }
}

/**
 * 2) CoffeeVideoScreen
 *   - Zeigt oben Icon
 *   - Spielt coffee.gif (ca. 8s) + 2 Durchläufe => 16s
 *   - Danach -> CoffeeScreen
 */
@Composable
fun CoffeeVideoScreen(
    viewModel: AppSettingsViewModel,
    onFinishCoffeeVideo: () -> Unit = {}
) {
    val settings by viewModel.settings
    var playCount by remember { mutableStateOf(0) }
    var elapsedTime by remember { mutableStateOf(0L) }
    val totalDuration = 16000L // 16 seconds
    val context = LocalContext.current
    val packageName = context.packageName
    val gifUri = "android.resource://$packageName/${R.raw.coffee}"

    LaunchedEffect(Unit) {
        val startTime = System.currentTimeMillis()

        while (elapsedTime < totalDuration) {
            delay(100) // Update every 100ms for smooth animation
            elapsedTime = System.currentTimeMillis() - startTime
        }

        delay(1000)
        onFinishCoffeeVideo()
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Icon-Kreis oben
            Box(
                modifier = Modifier
                    .offset(y = 10.dp)
                    .size(20.dp)
                    .background(Color(settings.buttonColor), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Coffee,
                    contentDescription = "Coffee Icon",
                    tint = Color(settings.buttonTextColor),
                    modifier = Modifier.size(15.dp)
                )
            }

            Spacer(modifier = Modifier.height(35.dp))

            // Das animierte GIF via GlideImage (Landscapist)
            GlideImage(
                imageModel = { "android.resource://com.example.breakreminder/${R.raw.coffee}" },
                modifier = Modifier.size(110.dp)
            )
        }
    }
}

/**
 * 3) CoffeeScreen
 *   - Zeigt "Your coffee is ready!"
 *   - Button "Back to Home"
 */
@Composable
fun CoffeeScreen(
    viewModel: AppSettingsViewModel,
    onBackToHome: () -> Unit = {}
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
            // Icon-Kreis oben
            Box(
                modifier = Modifier
                    .offset(y = 10.dp)
                    .size(20.dp)
                    .background(Color(settings.buttonColor), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Coffee,
                    contentDescription = "Coffee Icon",
                    tint = Color(settings.buttonTextColor),
                    modifier = Modifier.size(15.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "Your coffee is ready!",
                fontSize = 24.sp,
                color = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

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
        }
    }
}

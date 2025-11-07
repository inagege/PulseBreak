package com.example.breakreminder.screens

import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.breakreminder.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import com.example.breakreminder.sync.AppSettingsViewModel
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle


data class YogaScreenData(
    val title: String,
    val description: String? = null,
    val buttonText: String,
    val imageRes: Int? = null,
    val soundRes: Int? = null, // New field for announcement sound
    val imageWidthDp: Float = 100f,
    val imageHeightDp: Float = 50f
)


val yogaScreensList = listOf(
    YogaScreenData("5 exercises each\n60 seconds!", buttonText = "Start Session!"),
    YogaScreenData("Yoga 1/5", buttonText = "Next", imageRes = R.drawable.yoga1, soundRes = R.raw.announce1),
    YogaScreenData("Yoga 2/5", buttonText = "Next", imageRes = R.drawable.yoga2, soundRes = R.raw.announce2),
    YogaScreenData("Yoga 3/5", buttonText = "Next", imageRes = R.drawable.yoga3, soundRes = R.raw.announce3),
    YogaScreenData("Yoga 4/5", buttonText = "Next", imageRes = R.drawable.yoga4, soundRes = R.raw.announce4),
    YogaScreenData("Yoga 5/5", buttonText = "Next", imageRes = R.drawable.yoga5, soundRes = R.raw.announce5),
    YogaScreenData("Yoga session\ncompleted", buttonText = "Back to Home")
)

/**
 * YogaScreens mit einer Logik:
 * - Button immer etwa an selber Höhe (mit Spacer)
 * - Wenn ein Bild da ist: Timer, Bild, dann Text
 * - Wenn kein Bild da ist: Text mittig zwischen Icon und Button
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun YogaScreens(
    viewModel: AppSettingsViewModel,
    onBackToHome: () -> Unit = {}
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var currentIndex by remember { mutableStateOf(0) }
    var remainingTime by remember { mutableStateOf(60) }
    val screens = yogaScreensList
    val screen = screens[currentIndex]

    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var announcementPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    val context = LocalContext.current

    // Background music
    DisposableEffect(Unit) {
        val player = MediaPlayer.create(context, R.raw.yoga).apply {
            isLooping = true
            setVolume(0.7f, 0.7f)
            start()
        }
        mediaPlayer = player

        onDispose {
            player.stop()
            player.release()
            mediaPlayer = null
        }
    }

    // Handle announcements and timer
    LaunchedEffect(key1 = currentIndex) {
        // Stop any ongoing announcement
        announcementPlayer?.let {
            it.stop()
            it.release()
            mediaPlayer?.setVolume(0.7f, 0.7f) // Restore volume
        }

        val screen = screens[currentIndex]

        // Play announcement if available
        screen.soundRes?.let { resId ->
            // Lower background volume
            mediaPlayer?.setVolume(0.4f, 0.4f)

            MediaPlayer.create(context, resId).apply {
                announcementPlayer = this
                start()
                suspendCancellableCoroutine<Unit> { cont ->
                    setOnCompletionListener {
                        cont.resume(Unit, null)
                    }
                }
                release()
                announcementPlayer = null
                // Restore background volume
                mediaPlayer?.setVolume(0.7f, 0.7f)
            }
        }

        // Existing timer logic
        if (currentIndex in 1..5 && screen.imageRes != null) {
            remainingTime = 60
            while (remainingTime > 0) {
                delay(1000)
                remainingTime--
            }
            moveToNextScreen(currentIndex, screens.size) { currentIndex = it }
        }
    }

    Scaffold { innerPadding ->
        // Column, in der wir den Button unten oder "fast unten" platzieren.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1) Kleiner Icon-Kreis oben, wie SelectionScreen
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .offset(y = 10.dp)
                    .background(Color(settings.buttonColor), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // Yoga-Icon
                Image(
                    painter = painterResource(id = R.drawable.yogaicon),
                    contentDescription = "Yoga Icon",
                    modifier = Modifier.size(15.dp)
                )
            }

            // Kurzer Spacer, damit etwas Abstand zum Icon ist
            Spacer(modifier = Modifier.height(20.dp))

            if (screen.imageRes != null) {
                // 2) Wenn ein Bild da ist:
                //    Timer + Bild + Titel + evtl. description
                //    Danach Spacer.weight(...) => Button
                // Timer (index 1..5)
                if (currentIndex in 1..5) {
                    Text(
                        text = "$remainingTime seconds",
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                } else {
                    // Falls wir "Screen 0" oder "Screen 6" haben,
                    // kommt ggf. kein Timer
                }

                // Bild
                Image(
                    painter = painterResource(id = screen.imageRes),
                    contentDescription = screen.title,
                    modifier = Modifier.size(
                        width = screen.imageWidthDp.dp,
                        height = screen.imageHeightDp.dp
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Optional description
                if (!screen.description.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = screen.description,
                        fontSize = 20.sp,
                        color = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor),
                        textAlign = TextAlign.Center
                    )
                }

                // Spacer mit weight => Button rutscht nach unten
                Spacer(modifier = Modifier.weight(0.5f))

            } else {
                // 3) Wenn KEIN Bild => Text soll mittig zwischen Icon und Button sein
                //    -> Wir nutzen Spacer.weight(1f) vor dem Text und Spacer.weight(1f) nach dem Text
                Spacer(modifier = Modifier.weight(0.5f))

                // Titel
                Text(
                    text = screen.title,
                    fontSize = 24.sp,
                    color = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor),
                    textAlign = TextAlign.Center
                )

                // Optional description
                if (!screen.description.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = screen.description,
                        fontSize = 20.sp,
                        color = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.weight(0.5f))
            }

            // 4) Button (ganz unten)
            Button(
                onClick = {
                    if (currentIndex < screens.size - 1) {
                        currentIndex++
                    } else {
                        onBackToHome()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(settings.buttonColor),
                    contentColor = Color(settings.buttonTextColor)
                ),
                modifier = Modifier
                    .width(170.dp)
                    .height(50.dp)
            ) {
                Text(text = screen.buttonText, fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

private fun moveToNextScreen(
    currentIndex: Int,
    totalScreens: Int,
    onIndexChange: (Int) -> Unit
) {
    if (currentIndex < totalScreens - 1) {
        onIndexChange(currentIndex + 1)
    }
}

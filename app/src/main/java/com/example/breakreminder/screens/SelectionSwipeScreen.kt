package com.example.breakreminder.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.pager.HorizontalPager
import com.google.accompanist.pager.rememberPagerState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.pointerInput
import com.example.breakreminder.sync.AppSettingsViewModel
import com.google.accompanist.pager.ExperimentalPagerApi

@OptIn(ExperimentalPagerApi::class)
@Composable
fun SelectionSwipeScreen(
    viewModel: AppSettingsViewModel,
    onNavigateToYoga: () -> Unit = {},
    onNavigateToWalk: () -> Unit = {},
    onNavigateToNap: () -> Unit = {},
    onNavigateToVent: () -> Unit = {},
    onNavigateToCoffee: () -> Unit = {},
    onNavigateToClean: () -> Unit = {}
) {
    val settings by viewModel.settings
    
    val activities = listOf(
        ActivityData("Yoga", Icons.Default.SelfImprovement),
        ActivityData("Walk", Icons.Default.DirectionsWalk),
        ActivityData("Nap", Icons.Default.Bed),
        ActivityData("Vent", Icons.Default.Air),
        ActivityData("Coffee", Icons.Default.LocalCafe),
        ActivityData("Clean", Icons.Default.CleaningServices)
    )

    val config = LocalConfiguration.current
    val screenWidth = config.screenWidthDp.dp
    val screenHeight = config.screenHeightDp.dp

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .offset(y = 10.dp)
                    .align(Alignment.CenterHorizontally)
                    .background(Color(settings.buttonColor), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.HourglassBottom,
                    contentDescription = "Hourglass",
                    tint = Color(settings.buttonTextColor),
                    modifier = Modifier.size(15.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            HorizontalPager(
                count = activities.size,
                state = rememberPagerState(),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                val activity = activities[page]
                Box(
                    modifier = Modifier
                        .width(screenWidth)
                        .height(screenHeight - 70.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    when (activity.name) {
                                        "Yoga" -> onNavigateToYoga()
                                        "Walk" -> onNavigateToWalk()
                                        "Nap" -> onNavigateToNap()
                                        "Vent" -> onNavigateToVent()
                                        "Coffee" -> onNavigateToCoffee()
                                        "Clean" -> onNavigateToClean()
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = activity.icon,
                            contentDescription = activity.name,
                            tint = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor),
                            modifier = Modifier.size(100.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = activity.name,
                            fontSize = 20.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                            color = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor)
                        )
                    }
                }
            }
        }
    }
}

data class ActivityData(
    val name: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

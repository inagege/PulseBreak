// filepath: companion/src/main/java/com/example/companionpulsebreak/screens/HueColorWidgets.kt
package com.example.companionpulsebreak.screens

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import com.example.companionpulsebreak.sync.HueLight
import com.example.companionpulsebreak.sync.HueGroup
import com.example.commonlibrary.HueAutomationData

@Composable
fun HueColorWheel(
    colorArgb: Int,
    onColorChanged: (Int) -> Unit,
    size: Dp = 280.dp
) {
    var px by remember { mutableStateOf(1f) }
    val radius = px / 2f

    val hsv = remember(colorArgb) {
        FloatArray(3).also { AndroidColor.colorToHSV(colorArgb, it) }
    }
    var hue by remember(colorArgb) { mutableStateOf(hsv[0]) }
    var sat by remember(colorArgb) { mutableStateOf(hsv[1]) }

    fun updateFromTouch(x: Float, y: Float) {
        val cx = radius
        val cy = radius
        val dx = x - cx
        val dy = y - cy
        val r = min(sqrt(dx * dx + dy * dy), radius)
        val angle = ((atan2(dy, dx) * 180f / Math.PI.toFloat()) + 360f) % 360f

        hue = angle
        sat = (r / radius).coerceIn(0f, 1f)

        val argb = AndroidColor.HSVToColor(floatArrayOf(hue, sat, 1f))
        onColorChanged(argb)
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(size)
                .onGloballyPositioned { px = it.size.width.toFloat() }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o -> updateFromTouch(o.x, o.y) },
                        onDrag = { change, _ -> updateFromTouch(change.position.x, change.position.y) }
                    )
                }
        ) {
            val sweep = Brush.sweepGradient(
                listOf(
                    Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                )
            )
            drawCircle(brush = sweep, radius = radius)

            // white center (sat->0)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    radius = radius
                ),
                radius = radius
            )

            val thumbR = sat * radius
            val theta = hue * (Math.PI.toFloat() / 180f)
            val tx = radius + thumbR * cos(theta)
            val ty = radius + thumbR * sin(theta)

            drawCircle(Color.Black.copy(alpha = 0.25f), radius = 18f, center = Offset(tx, ty))
            drawCircle(Color.White, radius = 14f, center = Offset(tx, ty))
        }
    }
}

@Composable
fun WhiteTempWheel(
    ctMired: Int,
    onCtChanged: (Int) -> Unit,
    size: Dp = 280.dp,
    minMired: Int = 153,
    maxMired: Int = 500
) {
    var px by remember { mutableStateOf(1f) }
    val radius = px / 2f

    fun yToMired(y: Float): Int {
        val t = (y / (radius * 2f)).coerceIn(0f, 1f)
        return (minMired + (maxMired - minMired) * t).toInt()
    }

    fun miredToY(m: Int): Float {
        val t = ((m - minMired).toFloat() / (maxMired - minMired).toFloat()).coerceIn(0f, 1f)
        return (t * radius * 2f)
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(size)
                .onGloballyPositioned { px = it.size.width.toFloat() }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o -> onCtChanged(yToMired(o.y)) },
                        onDrag = { change, _ -> onCtChanged(yToMired(change.position.y)) }
                    )
                }
        ) {
            val warm = Color(0xFFFFD7A1)
            val cool = Color(0xFFCFE6FF)

            drawCircle(
                brush = Brush.verticalGradient(listOf(warm, Color.White, cool)),
                radius = radius
            )

            // subtle edge vignette
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f)),
                    radius = radius
                ),
                radius = radius
            )

            val ty = miredToY(ctMired).coerceIn(0f, radius * 2f)
            val tx = radius

            drawCircle(Color.Black.copy(alpha = 0.25f), radius = 18f, center = Offset(tx, ty))
            drawCircle(Color.White, radius = 14f, center = Offset(tx, ty))
        }
    }
}

fun selectedTargetsHaveColor(
    data: HueAutomationData,
    allLights: List<HueLight>,
    allGroups: List<HueGroup>
): Boolean {
    val groupMemberIds = allGroups
        .filter { data.groupIds.contains(it.id) }
        .flatMap { it.lightIds }

    val targetIds = (data.lightIds + groupMemberIds).toSet()
    val affected = if (targetIds.isEmpty()) allLights else allLights.filter { it.id in targetIds }

    // You must have HueLight.supportsColor for this to work:
    return affected.any { it.supportsColor }
}

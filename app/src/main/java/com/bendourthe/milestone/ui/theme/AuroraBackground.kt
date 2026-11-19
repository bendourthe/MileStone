package com.bendourthe.milestone.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * A deep teal gradient with three soft aurora glows drifting over a minute-long loop. The glows hold still when the
 * phone's animations are turned off. The phase is read only while drawing, so the drift never recomposes the screen.
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    val animate = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
    val phase: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "aurora").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "aurora phase",
        )
    } else {
        remember { mutableFloatStateOf(0.15f) }
    }
    val renderer = remember { AuroraRenderer() }
    Box(modifier) {
        Box(Modifier.matchParentSize().graphicsLayer { }.drawBehind { renderer.draw(this, phase.value) })
        content()
    }
}

private const val LOOP_MS = 60_000

// Rasterize the complete gradient at a bounded resolution, then draw one opaque image.
// Each background owns its mutable image; resizing replaces it and a stable phase reuses it.
internal class AuroraRenderer {
    private var image: ImageBitmap? = null
    private val raster = CanvasDrawScope()
    private var previousPhase = Float.NaN

    fun draw(scope: DrawScope, phase: Float) = with(scope) {
        if (size.width <= 0f || size.height <= 0f) return@with
        val scale = minOf(1f, 256f / maxOf(size.width, size.height))
        val width = (size.width * scale).roundToInt().coerceAtLeast(1)
        val height = (size.height * scale).roundToInt().coerceAtLeast(1)
        var texture = image
        if (texture == null || texture.width != width || texture.height != height) {
            texture = ImageBitmap(width, height, ImageBitmapConfig.F16)
            image = texture
            previousPhase = Float.NaN
        }
        if (phase != previousPhase) {
            raster.draw(Density(1f), LayoutDirection.Ltr, Canvas(texture), Size(width.toFloat(), height.toFloat())) {
                drawOriginalAurora(phase)
            }
            previousPhase = phase
        }
        drawImage(texture, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
    }
}

private fun DrawScope.drawOriginalAurora(phase: Float) {
    var brush = Brush.verticalGradient(listOf(MidnightTeal, AbyssTeal, VoidTeal))
    val t = phase * 2 * PI.toFloat()
    fun glow(color: Color, alpha: Float, x: Float, y: Float, radius: Float): Brush =
        Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent),
            Offset(size.width * x, size.height * y), maxOf(size.width, size.height) * radius)
    brush = Brush.composite(brush, glow(AuroraGreen, 0.12f, 0.30f + 0.18f * sin(t), 0.22f + 0.08f * cos(2 * t), 0.75f), BlendMode.SrcOver)
    brush = Brush.composite(brush, glow(AuroraCyan, 0.09f, 0.75f + 0.15f * cos(t + 1.3f), 0.48f + 0.10f * sin(t + 0.7f), 0.70f), BlendMode.SrcOver)
    brush = Brush.composite(brush, glow(AuroraViolet, 0.07f, 0.40f + 0.20f * sin(t + 2.6f), 0.82f + 0.06f * cos(t + 2.1f), 0.65f), BlendMode.SrcOver)
    drawRect(brush)
}

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
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
    Box(modifier) {
        Box(Modifier.matchParentSize().graphicsLayer { }.drawBehind { drawAurora(phase.value) })
        content()
    }
}

private const val LOOP_MS = 60_000

// Immutable textures keep the radial-gradient math off the full-resolution render path.
private const val GLOW_SIZE = 256
private val greenGlow by lazy { glowTexture(AuroraGreen, 0.12f) }
private val cyanGlow by lazy { glowTexture(AuroraCyan, 0.09f) }
private val violetGlow by lazy { glowTexture(AuroraViolet, 0.07f) }

private fun glowTexture(color: Color, alpha: Float): ImageBitmap {
    val image = ImageBitmap(GLOW_SIZE, GLOW_SIZE, ImageBitmapConfig.F16)
    val extent = GLOW_SIZE.toFloat()
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(extent, extent)) {
        drawRect(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent),
            Offset(extent / 2, extent / 2), extent / 2))
    }
    return image
}

internal fun DrawScope.drawAurora(phase: Float) {
    if (size.width <= 0f || size.height <= 0f) return
    drawRect(Brush.verticalGradient(listOf(MidnightTeal, AbyssTeal, VoidTeal)))
    val t = phase * 2 * PI.toFloat()
    glow(greenGlow, x = 0.30f + 0.18f * sin(t), y = 0.22f + 0.08f * cos(2 * t), radius = 0.75f)
    glow(cyanGlow, x = 0.75f + 0.15f * cos(t + 1.3f), y = 0.48f + 0.10f * sin(t + 0.7f), radius = 0.70f)
    glow(violetGlow, x = 0.40f + 0.20f * sin(t + 2.6f), y = 0.82f + 0.06f * cos(t + 2.1f), radius = 0.65f)
}

private fun DrawScope.glow(image: ImageBitmap, x: Float, y: Float, radius: Float) {
    val r = maxOf(size.width, size.height) * radius
    withTransform({
        translate(size.width * x - r, size.height * y - r)
        scale(2 * r / GLOW_SIZE, 2 * r / GLOW_SIZE, pivot = Offset.Zero)
    }) {
        drawImage(image, srcOffset = IntOffset.Zero, dstSize = IntSize(GLOW_SIZE, GLOW_SIZE), filterQuality = FilterQuality.Low)
    }
}

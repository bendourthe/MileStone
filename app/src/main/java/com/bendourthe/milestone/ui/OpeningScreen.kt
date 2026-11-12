package com.bendourthe.milestone.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bendourthe.milestone.R
import com.bendourthe.milestone.ui.theme.AuroraBackground
import kotlinx.coroutines.delay

private val MARK_HEIGHT = 168.dp

/** The MileStone artwork building itself and the name typing out, over the drifting aurora, when MileStone opens. */
@Composable
fun OpeningScreen(modifier: Modifier = Modifier) {
    AuroraBackground(modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedMark(MARK_HEIGHT)
            Spacer(Modifier.height(20.dp))
            TypedName("MileStone", startMs = NAME_START_MS)
        }
    }
}

/** Whether system animations are on; with them off, every opening animation shows its last frame at once. */
@Composable
private fun animationsOn(): Boolean {
    val context = LocalContext.current
    return remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
}

/** Where [t] (0 to 1 over the whole animation) is inside the step from [start] to [end], clamped to 0 to 1. */
private fun step(t: Float, start: Float, end: Float): Float = ((t - start) / (end - start)).coerceIn(0f, 1f)

/** Eases out past 1 and settles back, for a pop. */
private fun easeOutBack(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val y = x - 1f
    return 1f + c3 * y * y * y + c1 * y * y
}

private fun easeOut(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)

/**
 * The mark built in order: the stone, the coupon ribbon crossing it, the house and the palm tree popping up, then the
 * plane flying in. The layers come from `branding/split_mark.py`; stacked, they are the static mark, except the thin
 * gaps beside the ribbon, which are filled in the stone's own layer while it shows alone and fade out as the rest lands.
 */
@Composable
private fun AnimatedMark(height: Dp) {
    val progress = remember { Animatable(0f) }
    val animate = animationsOn()
    LaunchedEffect(Unit) {
        if (animate) progress.animateTo(1f, tween(MARK_MS, easing = LinearEasing)) else progress.snapTo(1f)
    }
    val t = progress.value
    val painter = painterResource(R.drawable.milestone_mark_stone)
    val box = Modifier.requiredSize(width = height * (painter.intrinsicSize.width / painter.intrinsicSize.height), height = height)

    val stone = easeOut(step(t, 0f, 0.25f))
    val ribbon = step(t, 0.21f, 0.54f)
    val house = step(t, 0.50f, 0.75f)
    val tree = step(t, 0.56f, 0.80f)
    val plane = easeOut(step(t, 0.72f, 1f))
    val gaps = 1f - step(t, 0.80f, 1f)

    // Decorative: the typed name below already reads "MileStone".
    Box(modifier = Modifier.clearAndSetSemantics {}) {
        Image(painter, null, box.graphicsLayer { alpha = stone; scaleX = 0.92f + 0.08f * stone; scaleY = scaleX })
        Image(painterResource(R.drawable.milestone_mark_stone_gaps), null, box.graphicsLayer { alpha = stone * gaps; scaleX = 0.92f + 0.08f * stone; scaleY = scaleX })
        // The ribbon is drawn in from the left, the way it runs across the stone.
        Image(
            painterResource(R.drawable.milestone_mark_ribbon), null,
            box.drawWithContent { clipRect(right = size.width * ribbon) { this@drawWithContent.drawContent() } },
        )
        // The house and the tree grow from where they stand.
        Image(painterResource(R.drawable.milestone_mark_house), null, box.graphicsLayer {
            val s = easeOutBack(house); scaleX = s; scaleY = s; alpha = house.coerceAtMost(1f); transformOrigin = TransformOrigin(0.28f, 0.65f)
        })
        Image(painterResource(R.drawable.milestone_mark_tree), null, box.graphicsLayer {
            val s = easeOutBack(tree); scaleX = s; scaleY = s; alpha = tree; transformOrigin = TransformOrigin(0.17f, 0.43f)
        })
        // The plane flies in from the lower left along its heading.
        Image(painterResource(R.drawable.milestone_mark_plane), null, box.graphicsLayer {
            translationX = -(1f - plane) * this.size.width * 0.45f
            translationY = (1f - plane) * this.size.height * 0.30f
            alpha = plane
        })
    }
}

/**
 * [name] typed out one letter at a time from [startMs]. The letters not typed yet are drawn transparent, so the line
 * keeps its full width and stays centered as it fills. With system animations off, the whole name shows at once.
 */
@Composable
internal fun TypedName(name: String, startMs: Long = TYPE_START_MS) {
    val animate = animationsOn()
    var typed by remember { mutableIntStateOf(if (animate) 0 else name.length) }
    LaunchedEffect(name) {
        if (!animate) return@LaunchedEffect
        delay(startMs)
        while (typed < name.length) {
            typed++
            delay(TYPE_LETTER_MS)
        }
    }
    val color = MaterialTheme.colorScheme.onSurface
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = color)) { append(name.take(typed)) }
            withStyle(SpanStyle(color = Color.Transparent)) { append(name.drop(typed)) }
        },
        style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.semantics { contentDescription = name },
    )
}

/** The mark builds in 1.4 s; the name starts typing as the house and tree land and ends about 1.6 s in. */
private const val MARK_MS = 1_400
private const val NAME_START_MS = 900L
private const val TYPE_START_MS = 100L
private const val TYPE_LETTER_MS = 80L

/**
 * The MileStone artwork without its teal square, [height] tall, for the top bar. The phone's launcher keeps the teal
 * icon. `milestone_mark.png` is the launcher foreground with the square cut out.
 */
@Composable
fun MileStoneMark(height: Dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val painter = painterResource(R.drawable.milestone_mark)
    // A height alone leaves the width at the PNG's own pixels, which caps the drawing at its pixel size; both sides scale it.
    val aspect = painter.intrinsicSize.width / painter.intrinsicSize.height
    Image(painter, contentDescription = contentDescription, modifier = modifier.requiredSize(width = height * aspect, height = height))
}

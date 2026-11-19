package com.bendourthe.milestone.ui.theme

import android.os.Build
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class AuroraRenderingTest {
    @Test fun texturedDrawPreservesOriginalGradientsAcrossMotionAndViewportChanges() {
        assertTrue("Synthetic rendering fixture is emulator-only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val renderer = AuroraRenderer()
        val measurements = JSONArray()
        var rasterMismatch = 0
        var preciseMismatch = 0
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.getExternalFilesDir(null), "aurora-rendering").apply { mkdirs() }
        for (config in listOf(ImageBitmapConfig.Argb8888, ImageBitmapConfig.F16)) {
        for ((width, height) in listOf(96 to 208, 208 to 96, 360 to 780, 1080 to 2340)) {
            // Repeated phase covers cache hits; matching boundary phases cover fixed-phase viewport changes.
            for (phase in listOf(0.15f, 0.15f, 0f, 0.25f, 0.5f, 0.75f, 1f, 0.15f)) {
                fun pixels(name: String, draw: DrawScope.() -> Unit): IntArray {
                    val image = ImageBitmap(width, height, config)
                    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat()), draw)
                    if (config == ImageBitmapConfig.Argb8888 && width == 96 && phase == 0f) {
                        File(directory, "$name.png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    return IntArray(width * height).also { image.readPixels(it) }
                }
                val expected = pixels("reference") {
                    drawRect(Brush.verticalGradient(listOf(MidnightTeal, AbyssTeal, VoidTeal)))
                    val t = phase * 2 * PI.toFloat()
                    fun glow(color: Color, alpha: Float, x: Float, y: Float, radius: Float) {
                        drawRect(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent),
                            Offset(size.width * x, size.height * y), maxOf(size.width, size.height) * radius))
                    }
                    glow(AuroraGreen, 0.12f, 0.30f + 0.18f * sin(t), 0.22f + 0.08f * cos(2 * t), 0.75f)
                    glow(AuroraCyan, 0.09f, 0.75f + 0.15f * cos(t + 1.3f), 0.48f + 0.10f * sin(t + 0.7f), 0.70f)
                    glow(AuroraViolet, 0.07f, 0.40f + 0.20f * sin(t + 2.6f), 0.82f + 0.06f * cos(t + 2.1f), 0.65f)
                }
                val actual = pixels("actual") { renderer.draw(this, phase) }
                var largestDelta = 0
                var totalDelta = 0L
                for (index in expected.indices) for (shift in 0..24 step 8) {
                    val delta = abs(((expected[index] ushr shift) and 255) - ((actual[index] ushr shift) and 255))
                    largestDelta = maxOf(largestDelta, delta)
                    totalDelta += delta
                }
                measurements.put(JSONObject().put("width", width).put("height", height).put("phase", phase)
                    .put("format", if (config == ImageBitmapConfig.F16) "F16" else "ARGB8888")
                    .put("maximum_channel_delta", largestDelta).put("mean_channel_delta", totalDelta.toDouble() / (width * height * 4)))
                if (config == ImageBitmapConfig.F16) preciseMismatch = maxOf(preciseMismatch, largestDelta)
                else rasterMismatch = maxOf(rasterMismatch, largestDelta)
            }
        }
        }
        File(directory, "metrics.json").writeText(measurements.toString(2))
        for (name in listOf("reference.png", "actual.png", "metrics.json")) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "cp ${File(directory, name).absolutePath} /sdcard/Download/milestone-aurora-$name").use {
                java.io.FileInputStream(it.fileDescriptor).readBytes()
            }
        }
        assertTrue("High-precision gradient mismatch: $preciseMismatch", preciseMismatch <= 1)
        // Each of the three original overlays can introduce an extra 8-bit rounding step.
        // F16 above independently checks the underlying colors rather than those intermediate quantizations.
        assertTrue("8-bit overlay rounding exceeded three channel levels: $rasterMismatch", rasterMismatch <= 3)
    }
}

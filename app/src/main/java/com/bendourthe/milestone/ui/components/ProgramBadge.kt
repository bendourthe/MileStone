package com.bendourthe.milestone.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bendourthe.milestone.R

/** Bundled logo drawables keyed by `Program.logoKey`; logos are never fetched at runtime. */
object ProgramLogos {
    private val byKey: Map<String, Int> = mapOf(
        "logo_amex" to R.drawable.logo_amex,
        "logo_chase" to R.drawable.logo_chase,
        "logo_capitalone" to R.drawable.logo_capitalone,
        "logo_citi" to R.drawable.logo_citi,
        "logo_delta" to R.drawable.logo_delta,
        "logo_united" to R.drawable.logo_united,
        "logo_alaska" to R.drawable.logo_alaska,
        "logo_aeroplan" to R.drawable.logo_aeroplan,
        "logo_aadvantage" to R.drawable.logo_aadvantage,
        "logo_marriott" to R.drawable.logo_marriott,
        "logo_hyatt" to R.drawable.logo_hyatt,
        "logo_hilton" to R.drawable.logo_hilton,
    )

    /** Logos drawn on their own opaque background, shown edge to edge instead of on white. */
    private val fullBleed = setOf("logo_amex", "logo_alaska")

    @DrawableRes
    fun resId(logoKey: String?): Int? = logoKey?.let(byKey::get)

    fun isFullBleed(logoKey: String?): Boolean = logoKey in fullBleed
}

@Composable
fun ProgramBadge(monogram: String, logoKey: String?, brandColorArgb: Long, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val logo = ProgramLogos.resId(logoKey)
    val shape = RoundedCornerShape(size * 0.22f)
    if (logo != null) {
        val fullBleed = ProgramLogos.isFullBleed(logoKey)
        Box(modifier.size(size).clip(shape).background(Color.White), contentAlignment = Alignment.Center) {
            Image(
                painterResource(logo),
                contentDescription = null,
                contentScale = if (fullBleed) ContentScale.Crop else ContentScale.Fit,
                modifier = if (fullBleed) Modifier.matchParentSize() else Modifier.padding(size / 10),
            )
        }
    } else {
        // Decorative like the logo: the program's name is read beside it, so the monogram is not a stop of its own.
        Box(modifier.size(size).clip(shape).background(Color(brandColorArgb)).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Text(
                monogram,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.36f).sp,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

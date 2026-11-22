package com.bendourthe.milestone.ui.benefits

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import com.bendourthe.milestone.R
import java.util.Locale

/** Corner radius as a percent of the card's height; a payment card's own corner is about 6% of its height. */
private const val ARTWORK_CORNER_PERCENT = 9

/** How much larger the image is drawn than its frame, so a 1 to 2 pixel edge in the image file is cut off. */
private const val ARTWORK_ZOOM = 1.02f

/** The bundled artwork for a card: the first reviewed Amex images, then the public product list's image. */
internal fun cardArtworkResource(programId: String, name: String): Int? =
    reviewedAmexArtwork(programId, name)
        ?: com.bendourthe.milestone.domain.CardProducts.match(programId, name)?.art?.let(::productArtwork)

private fun reviewedAmexArtwork(programId: String, name: String): Int? {
    if (programId != "amex") return null
    val product = name.lowercase(Locale.ROOT)
    return when {
        "delta" in product -> if ("gold" in product) R.drawable.benefits_amex_delta_gold else null
        "hilton" in product -> if ("surpass" in product) R.drawable.benefits_amex_hilton_surpass else null
        "business" in product && "platinum" in product -> R.drawable.benefits_amex_business_platinum
        "platinum" in product -> R.drawable.benefits_amex_platinum
        "hilton" in product && "surpass" in product -> R.drawable.benefits_amex_hilton_surpass
        "delta" in product && "gold" in product -> R.drawable.benefits_amex_delta_gold
        else -> null
    }
}

/**
 * Decorative product artwork; the neighboring card name supplies its accessible identity. A card without bundled
 * artwork gets a drawn card face in the issuer's [brandColorArgb] with its [lastDigits]; with no color, an outline icon.
 */
@Composable
internal fun CardArtwork(programId: String, name: String, modifier: Modifier = Modifier, brandColorArgb: Long? = null, lastDigits: String? = null) {
    val resource = cardArtworkResource(programId, name)
    val shape = modifier.aspectRatio(480f / 304f).testTag("cardArtwork")
    when {
        // Product images paint a background color into their rounded corners (about 6 to 7.5% of their height) and
        // some carry a thin light edge. The clip scales with the card so those corners never show at any size, and
        // the image is drawn slightly larger inside it so the edge falls outside.
        resource != null -> Image(
            painterResource(resource),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = shape.clip(RoundedCornerShape(percent = ARTWORK_CORNER_PERCENT)).graphicsLayer {
                scaleX = ARTWORK_ZOOM
                scaleY = ARTWORK_ZOOM
            },
        )
        brandColorArgb != null -> CardFace(Color(brandColorArgb), lastDigits, shape)
        else -> Icon(Icons.Outlined.CreditCard, contentDescription = null, modifier = shape)
    }
}

/** A plain card shape: the issuer's color in a soft diagonal shade, a chip, and the ending. */
@Composable
private fun CardFace(color: Color, lastDigits: String?, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.linearGradient(listOf(color, lerp(color, Color.Black, 0.45f)))),
    ) {
        Box(
            Modifier.padding(start = 8.dp, top = 10.dp).size(width = 12.dp, height = 9.dp)
                .clip(RoundedCornerShape(2.dp)).background(Color(0xFFE6C77A)),
        )
        if (lastDigits != null) {
            Text(
                "•• $lastDigits",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 9.sp,
                maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 6.dp),
            )
        }
    }
}

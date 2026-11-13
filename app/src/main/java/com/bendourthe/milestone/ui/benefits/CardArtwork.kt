package com.bendourthe.milestone.ui.benefits

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import com.bendourthe.milestone.R
import java.util.Locale

internal fun cardArtworkResource(programId: String, name: String): Int? {
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

/** Decorative product artwork; the neighboring card name supplies its accessible identity. */
@Composable
internal fun CardArtwork(programId: String, name: String, modifier: Modifier = Modifier) {
    val resource = cardArtworkResource(programId, name)
    val shape = modifier.aspectRatio(480f / 304f).testTag("cardArtwork")
    if (resource == null) Icon(Icons.Outlined.CreditCard, contentDescription = null, modifier = shape)
    else Image(painterResource(resource), contentDescription = null, modifier = shape)
}

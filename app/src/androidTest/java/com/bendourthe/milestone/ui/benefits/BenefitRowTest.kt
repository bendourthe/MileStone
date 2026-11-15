package com.bendourthe.milestone.ui.benefits

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.bendourthe.milestone.domain.*
import com.bendourthe.milestone.ui.theme.AuroraBackground
import com.bendourthe.milestone.ui.theme.MileStoneTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BenefitRowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun completedAndUnfinishedCreditRowsKeepAmountsAndEnrollmentInline() = exercise(1f, "normal")
    @Test fun creditRowsRemainReadableWithLargeText() = exercise(1.8f, "large")

    private fun exercise(scale: Float, capture: String) {
        val key = CardKey("amex", "1234")
        val completed = assignBenefitKeys(key, listOf(BenefitDraft(BenefitSection.COMPLETED,
            "\$200 Example Hotel Statement Credit Benefit", null, null, null, "Current period", true,
            enrollment = Enrollment.ENROLLED))).single()
        val active = completed.copy(stableKey = "active", section = BenefitSection.CREDIT_EARNED,
            completed = false, earnedCents = 5_000, remainingCents = 15_000, targetCents = 20_000)
        val current = mutableStateOf(BenefitsViewModel.item(completed, Locale.US))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                MileStoneTheme {
                    AuroraBackground(Modifier.fillMaxSize()) {
                        androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) { BenefitRow(current.value) }
                    }
                }
            }
        }
        checkCredit(current.value, 1f, "\$200/\$200", true)
        save("$capture-completed")
        compose.runOnIdle { current.value = BenefitsViewModel.item(active, Locale.US) }
        checkCredit(current.value, .25f, "\$50/\$200", false)
        save("$capture-unfinished")
        compose.runOnIdle { current.value = BenefitsViewModel.item(completed.copy(stableKey = "listed", section = BenefitSection.LISTED,
            title = "Example Protection", completed = false), Locale.US) }
        val title = node("benefitTitle", current.value).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val enrollment = node("benefitEnrollment", current.value).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(enrollment.left >= title.right)
        node("benefitProgress", current.value).assertDoesNotExist()
    }

    private fun node(tag: String, item: BenefitUi) = compose.onNodeWithTag("$tag:${item.key}", useUnmergedTree = true)

    private fun checkCredit(item: BenefitUi, progress: Float, amounts: String, completed: Boolean) {
        val title = node("benefitTitle", item).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val enrollment = node("benefitEnrollment", item).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(enrollment.left >= title.right)
        assertTrue(kotlin.math.abs(title.center.y - enrollment.center.y) < 2f)
        val indicator = node("benefitProgress", item).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(progress, 0f..1f)))
            .fetchSemanticsNode().boundsInRoot
        val label = node("benefitAmounts", item).assertIsDisplayed().assertTextEquals(amounts).fetchSemanticsNode().boundsInRoot
        assertTrue(label.left >= indicator.right)
        assertTrue(kotlin.math.abs(indicator.center.y - label.center.y) < 2f)
        assertTrue(indicator.top >= title.bottom)
        if (completed) {
            val check = node("benefitCompleted", item).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(check.left >= enrollment.right)
        } else node("benefitCompleted", item).assertDoesNotExist()
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(context.getExternalFilesDir(null), "credit-tile-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

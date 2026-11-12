package com.bendourthe.milestone.ui.auth

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bendourthe.milestone.domain.testProgram
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoSignInDialogTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun thePasswordStaysHiddenUntilTheOwnerAsksToSeeIt() {
        rule.setContent {
            AutoSignInDialog(testProgram("hyatt", "World of Hyatt"), status = null, onSave = { _, _, _, _ -> }, onDismiss = {}, onSignInManually = {})
        }
        val field = rule.onNodeWithText("Password")
        field.performTextInput("not-a-real-password")
        assertEquals("\u2022".repeat(19), shown(field))

        rule.onNodeWithContentDescription("Show password").performClick()
        assertEquals("not-a-real-password", shown(field))

        rule.onNodeWithContentDescription("Hide password").performClick()
        assertEquals("\u2022".repeat(19), shown(field))
    }

    /** The text drawn in the field, after any masking. */
    private fun shown(field: SemanticsNodeInteraction) = field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
}

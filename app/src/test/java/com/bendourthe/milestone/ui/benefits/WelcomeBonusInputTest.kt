package com.bendourthe.milestone.ui.benefits

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bendourthe.milestone.domain.WelcomeBonusSetup
import com.bendourthe.milestone.ui.theme.MileStoneTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class WelcomeBonusInputTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun groupedPasteAndCalendarSelectionSaveExactValues() {
        var saved: WelcomeBonusSetup? = null
        rule.setContent { MileStoneTheme { WelcomeBonusDialog(null, onSave = { saved = it }, onDismiss = {}) } }
        rule.onNodeWithTag("welcomeSpent").performTextInput("1,234.50")
        rule.onNodeWithTag("welcomeTarget").performTextInput("5000")
        rule.onNodeWithTag("welcomeDeadline").performTextInput("20270101")
        rule.onNodeWithTag("welcomeCalendar").performScrollTo().performClick()
        rule.onNodeWithText("Use date").assertExists()
        rule.onNodeWithText("Friday, January 15, 2027").performClick()
        rule.onNodeWithText("Use date").performClick()
        rule.onNodeWithText("Save").performClick()
        assertEquals(123450L, saved!!.spentCents)
        assertEquals(500000L, saved!!.targetCents)
        assertEquals(LocalDate.of(2027, 1, 15), saved!!.deadline)
    }
    @Test fun reopeningAndSavingAnAcceptedDateKeepsItsDayAndExactCents() {
        val initial = WelcomeBonusSetup(123450L, 500000L, LocalDate.of(2027, 1, 15), "Reward")
        var saved: WelcomeBonusSetup? = null
        rule.setContent { MileStoneTheme { WelcomeBonusDialog(initial, onSave = { saved = it }, onDismiss = {}) } }
        rule.onNodeWithText("Save").performClick()
        assertEquals(initial, saved)
    }
}

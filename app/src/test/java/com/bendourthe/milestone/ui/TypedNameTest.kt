package com.bendourthe.milestone.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TypedNameTest {
    @get:Rule
    val rule = createComposeRule()

    /** Letters drawn visible; the rest of the name is present but transparent, so the line keeps its width. */
    private fun visibleLetters(): Int {
        val text = rule.onNodeWithContentDescription("MileStone", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals("MileStone", text.text)
        return text.length - text.spanStyles.filter { it.item.color == Color.Transparent }.sumOf { it.end - it.start }
    }

    @Test
    fun theNameTypesOutOneLetterAtATimeAtFullWidth() {
        rule.mainClock.autoAdvance = false
        rule.setContent { TypedName("MileStone") }
        rule.mainClock.advanceTimeBy(50)
        assertEquals(0, visibleLetters())
        rule.mainClock.advanceTimeBy(100)
        assertEquals(1, visibleLetters())
        rule.mainClock.advanceTimeBy(160)
        assertEquals(3, visibleLetters())
        rule.mainClock.advanceTimeBy(600)
        assertEquals(9, visibleLetters())
    }
}

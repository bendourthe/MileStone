package com.bendourthe.milestone.ui.benefits

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import org.junit.Assert.*
import org.junit.Test

class BenefitsInputFormattingTest {
    private fun checkMapping(format: VisualTransformation, raw: String, expected: String) {
        val rendered = format.filter(AnnotatedString(raw))
        assertEquals(expected, rendered.text.text)
        val map = rendered.offsetMapping
        (0..raw.length).forEach { offset ->
            val target = map.originalToTransformed(offset)
            assertTrue(target in 0..expected.length)
            assertEquals(offset, map.transformedToOriginal(target))
        }
        assertEquals((0..expected.length).map(map::transformedToOriginal).sorted(), (0..expected.length).map(map::transformedToOriginal))
    }

    @Test fun groupedAmountsKeepCentsAndCursorMappings() {
        checkMapping(AmountInputTransformation, "1234.50", "1,234.50")
        checkMapping(AmountInputTransformation, "1234567", "1,234,567")
        checkMapping(AmountInputTransformation, "92233720368547758.07", "92,233,720,368,547,758.07")
        checkMapping(AmountInputTransformation, ".50", ".50")
        checkMapping(AmountInputTransformation, "", "")
        val pasted = normalizeAmountEdit(TextFieldValue("1,234.50", TextRange(8)))
        assertEquals("1234.50", pasted.text)
        assertEquals(TextRange(7), pasted.selection)
        assertEquals("1,2", normalizeAmountEdit(TextFieldValue("1,2")).text)
    }

    @Test fun typedDatesInsertHyphensWithoutChangingDigits() {
        checkMapping(DateInputTransformation, "2027", "2027")
        checkMapping(DateInputTransformation, "202701", "2027-01")
        checkMapping(DateInputTransformation, "20270115", "2027-01-15")
        val pasted = normalizeDateEdit(TextFieldValue("2027-01-15", TextRange(10)))
        assertEquals("20270115", pasted.text)
        assertEquals(TextRange(8), pasted.selection)
        assertEquals("202701", normalizeDateEdit(TextFieldValue("202701-")).text)
        assertEquals("2027-1-1", normalizeDateEdit(TextFieldValue("2027-1-1")).text)
        assertEquals("2027-01-15", dateInputText(pasted.text))
    }
    @Test fun signedAndExpandedYearsAreRejectedWithoutChangingTheirSign() {
        listOf("-0001-01-01", "+10000-01-01").forEach { iso ->
            assertEquals(iso, normalizeDateEdit(TextFieldValue(iso)).text)
            assertNull(welcomeBonusInput("1", "10", iso, ""))
            assertTrue("deadline" in welcomeBonusErrors("1", "10", iso, ""))
        }
    }
    @Test fun wholeDollarAmountsDoNotAcquireDecimalsAndRewardsKeepWordsAndCodes() {
        assertEquals("5000", amountInputText(500000))
        assertEquals("1234.50", amountInputText(123450))
        checkMapping(RewardInputTransformation, "80000 points", "80,000 points")
        checkMapping(RewardInputTransformation, "12000.50 cash", "12,000.50 cash")
        checkMapping(RewardInputTransformation, "80,000 points", "80,000 points")
        checkMapping(RewardInputTransformation, "Offer ABC12345 until 2027-01-15", "Offer ABC12345 until 2027-01-15")
    }
}
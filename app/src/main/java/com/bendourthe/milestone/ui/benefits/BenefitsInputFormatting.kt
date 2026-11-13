package com.bendourthe.milestone.ui.benefits

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Insert display-only separators; the stored input and cursor offsets keep their original digits. */
private fun separated(text: AnnotatedString, separator: (Int) -> Char?): TransformedText {
    val forward = IntArray(text.length + 1)
    val reverse = mutableListOf(0)
    val output = StringBuilder()
    text.text.forEachIndexed { index, char ->
        separator(index)?.let { output.append(it); reverse += index }
        forward[index] = output.length
        output.append(char)
        reverse += index + 1
    }
    forward[text.length] = output.length
    return TransformedText(AnnotatedString(output.toString()), object : OffsetMapping {
        override fun originalToTransformed(offset: Int) = forward[offset.coerceIn(0, text.length)]
        override fun transformedToOriginal(offset: Int) = reverse[offset.coerceIn(0, reverse.lastIndex)]
    })
}

internal object AmountInputTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.length > 20 || !Regex("[0-9]*(?:\\.[0-9]{0,2})?").matches(text.text)) return TransformedText(text, OffsetMapping.Identity)
        val integerEnd = text.text.indexOf('.').takeIf { it >= 0 } ?: text.length
        return separated(text) { index -> if (index > 0 && index < integerEnd && (integerEnd - index) % 3 == 0) ',' else null }
    }
}

internal object DateInputTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.length > 8 || text.text.any { it !in '0'..'9' }) return TransformedText(text, OffsetMapping.Identity)
        return separated(text) { index -> if (index == 4 || index == 6) '-' else null }
    }
}

private fun removingSeparators(value: TextFieldValue, separator: Char): TextFieldValue {
    fun mapped(offset: Int) = value.text.take(offset).count { it != separator }
    return value.copy(text = value.text.replace(separator.toString(), ""),
        selection = TextRange(mapped(value.selection.start), mapped(value.selection.end)), composition = null)
}

internal fun normalizeAmountEdit(value: TextFieldValue): TextFieldValue {
    val text = value.text
    return if (text.length <= 32 && ',' in text && Regex("[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{0,2})?").matches(text)) removingSeparators(value, ',') else value
}

internal fun normalizeDateEdit(value: TextFieldValue): TextFieldValue {
    val text = value.text
    if (text.length > 10 || '-' !in text) return value
    var digits = 0
    var previousSeparator = false
    text.forEach { char ->
        when {
            char in '0'..'9' -> { digits++; previousSeparator = false }
            char == '-' && digits in setOf(4, 6) && !previousSeparator -> previousSeparator = true
            else -> return value
        }
    }
    return if (digits <= 8) removingSeparators(value, '-') else value
}

internal fun dateInputText(text: String): String = DateInputTransformation.filter(AnnotatedString(text)).text.text

internal fun amountInputText(cents: Long): String = if (cents % 100L == 0L) (cents / 100L).toString()
    else java.math.BigDecimal.valueOf(cents, 2).toPlainString()

/** Group standalone numeric rewards without changing words, codes, already grouped values, or dates. */
internal object RewardInputTransformation : VisualTransformation {
    private val number = Regex("(?<![\\p{L}\\p{N}_,/.-])[0-9]+(?:\\.[0-9]+)?(?![\\p{L}\\p{N}_,/.-])")
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.length > 120) return TransformedText(text, OffsetMapping.Identity)
        val commas = mutableSetOf<Int>()
        number.findAll(text.text).forEach { match ->
            val end = match.value.indexOf('.').takeIf { it >= 0 }?.plus(match.range.first) ?: (match.range.last + 1)
            for (index in end - 3 downTo match.range.first + 1 step 3) commas += index
        }
        return separated(text) { if (it in commas) ',' else null }
    }
}

internal fun rewardDisplayText(text: String): String = RewardInputTransformation.filter(AnnotatedString(text)).text.text

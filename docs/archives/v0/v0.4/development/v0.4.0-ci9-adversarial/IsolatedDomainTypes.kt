package com.bendourthe.milestone.domain

data class CardKey(val programId: String, val lastDigits: String) {
    init {
        require(isValidLastDigits(lastDigits)) { "$programId: last digits must be 4 or 5 digits" }
    }

    companion object {
        private val LAST_DIGITS = Regex("^[0-9]{4,5}$")

        fun isValidLastDigits(value: String): Boolean = LAST_DIGITS.matches(value)
    }
}

enum class BenefitsCategory(val title: String) {
    WELCOME_BONUS("Welcome bonus"), CREDITS("Credits"), SPEND_TRACKERS("Spend trackers"), OTHER("Other")
}

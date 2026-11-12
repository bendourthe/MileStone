package com.bendourthe.milestone.sync.extract

import com.bendourthe.milestone.domain.ExtractionSpec
import com.bendourthe.milestone.domain.SelectorStrategy
import com.bendourthe.milestone.domain.SyncCapability
import com.bendourthe.milestone.domain.testProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class PageStateClassifierTest {
    private val program = testProgram(
        "hilton",
        capability = SyncCapability.Automatic(ExtractionSpec.Selector(SelectorStrategy.CSS, "#points", Regex("/account"), Regex("/login"))),
    ).copy(identityProviderHosts = setOf("auth.example-idp.com"))

    private val noPassword = PageSignals(hasVisiblePasswordField = false)
    private val password = PageSignals(hasVisiblePasswordField = true)

    @Test
    fun table() {
        listOf(
            Triple("https://www.hilton.com/account", noPassword, PageState.DASHBOARD),
            Triple("https://www.hilton.com/account", password, PageState.LOGIN),
            Triple("https://www.hilton.com/login", noPassword, PageState.LOGIN),
            Triple("https://auth.example-idp.com/authorize", noPassword, PageState.LOGIN),
            Triple("https://www.hilton.com/offers", noPassword, PageState.OTHER),
            Triple("https://www.hilton.com.evil.com/account", noPassword, PageState.OTHER),
            Triple("http://www.hilton.com/account", noPassword, PageState.OTHER),
            Triple("https://www.hilton.com:8443/account", noPassword, PageState.OTHER),
        ).forEach { (url, signals, expected) -> assertEquals(url, expected, PageStateClassifier.classify(url, signals, program)) }
    }

    @Test
    fun manualOnlyProgramNeverReportsDashboard() {
        val manual = testProgram("hyatt")
        assertEquals(PageState.OTHER, PageStateClassifier.classify("https://www.hyatt.com/account", noPassword, manual))
        assertEquals(PageState.LOGIN, PageStateClassifier.classify("https://www.hyatt.com/account", password, manual))
    }
}

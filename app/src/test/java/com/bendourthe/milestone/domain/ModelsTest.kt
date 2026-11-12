package com.bendourthe.milestone.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test
    fun constructsEveryType() {
        val spec = ExtractionSpec.Selector(SelectorStrategy.CSS, "#points", Regex("/account"), Regex("/login"))
        val program = testProgram("hilton", capability = SyncCapability.Automatic(spec))
        assertTrue(program.canSync)
        assertFalse(testProgram("hyatt").canSync)
        val account = LoyaltyAccount("hilton", 82_410, BalanceOrigin.SCRAPED, 1, null, 0, 0, AccountStatus.UNSUPPORTED, null)
        assertEquals(82_410L, account.balance)
        listOf(SyncResult.Success(1), SyncResult.NeedsReconnect, SyncResult.Failure(FailureReason.Timeout), SyncResult.Unsupported)
            .forEach { assertTrue(it is SyncResult) }
        assertEquals(SortMode.ALPHABETICAL, SortMode.valueOf("ALPHABETICAL"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankId() {
        testProgram(" ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHttpLoginUrl() {
        testProgram("hilton").copy(loginUrl = "http://www.hilton.com/login")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyAllowedHosts() {
        testProgram("hilton").copy(allowedHosts = emptySet())
    }

    @Test
    fun failureReasonCodesRoundTrip() {
        val reasons = listOf(
            FailureReason.Timeout, FailureReason.NeedsAttention, FailureReason.Network(503), FailureReason.Tls,
            FailureReason.Unparseable, FailureReason.SelectorAmbiguous, FailureReason.Implausible,
            FailureReason.RendererGone, FailureReason.Unexpected,
        )
        assertEquals(reasons.size, reasons.map { it.code }.toSet().size)
        reasons.forEach { assertEquals(it.code, FailureReason.fromCode(it.code)?.code) }
        assertEquals(FailureReason.Unexpected, FailureReason.fromCode("no_such_code"))
        assertNull(FailureReason.fromCode(null))
    }
}

package com.bendourthe.milestone.sync.mfa

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneTimeCodeInboxTest {
    private var now = 1_000_000L
    private val inbox = OneTimeCodeInbox { now }

    @Test
    fun parserFindsTheSingleCodeInVerificationWording() {
        assertEquals("482913", OneTimeCodeParser.extract("Chase: Your one-time code is 482913. Don't share it."))
        assertEquals("7712", OneTimeCodeParser.extract("American Express: verification code 7712"))
        assertNull(OneTimeCodeParser.extract("Your statement balance of 1234 is ready"))
        assertNull(OneTimeCodeParser.extract("Code 1234 or 5678"))
        assertNull(OneTimeCodeParser.extract("Security alert: \$2500.00 charge at 10:45"))
        assertNull(OneTimeCodeParser.extract("Not you? Call 1-800-432-3117 about this code"))
        assertEquals("90817", OneTimeCodeParser.extract("Capital One: Your verification code is 90817."))
    }

    @Test
    fun codeGoesOnlyToTheProgramTheTextNames() = runTest {
        inbox.offer("Chase: your code is 482913")
        assertNull(inbox.await(setOf("capital one"), sinceEpochMs = 0, timeoutMs = 10))
        assertEquals("482913", inbox.await(setOf("chase"), sinceEpochMs = 0, timeoutMs = 10))
    }

    @Test
    fun aKeywordInsideAnotherWordDoesNotClaimTheCode() = runTest {
        inbox.offer("Your purchase verification code is 482913")
        assertNull(inbox.await(setOf("chase"), sinceEpochMs = 0, timeoutMs = 10))
        assertEquals(true, OneTimeCodeInbox.namesKeyword("CHASE: code 1234", "chase"))
        assertEquals(false, OneTimeCodeInbox.namesKeyword("Reunited at last, code 1234", "united"))
    }

    @Test
    fun codeIsHandedOutOnce() = runTest {
        inbox.offer("Chase: your code is 482913")
        assertEquals("482913", inbox.await(setOf("chase"), 0, 10))
        assertNull(inbox.await(setOf("chase"), 0, 10))
    }

    @Test
    fun codesBeforeTheSignInOrPastTheirLifetimeAreIgnored() = runTest {
        inbox.offer("Chase: your code is 482913")
        assertNull(inbox.await(setOf("chase"), sinceEpochMs = now + 1, timeoutMs = 10))
        now += OneTimeCodeInbox.TTL_MS
        assertNull(inbox.await(setOf("chase"), sinceEpochMs = 0, timeoutMs = 10))
    }

    @Test
    fun waitingSignInReceivesALaterText() = runTest {
        val code = async { inbox.await(setOf("amex", "american express"), now, 60_000) }
        testScheduler.runCurrent()
        inbox.offer("AMEX: Your one-time verification code is 551200")
        assertEquals("551200", code.await())
    }

    @Test
    fun codeNeverAppearsInToString() {
        assertEquals("OneTimeCode(<redacted>)", OneTimeCode("482913", 0, "").toString())
    }
}

package com.bendourthe.milestone.ui.auth

import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.sync.extract.BridgeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureBuildTest {
    private val closingEvents = listOf(BridgeEvent.Extracted(1234), BridgeEvent.Failed(FailureReason.Timeout))
    private val otherEvents = listOf(BridgeEvent.LoginRequired, BridgeEvent.Unsupported)

    @Test
    fun releaseAndDebugBuildsForwardEveryEvent() {
        for (buildType in listOf("debug", "release")) {
            for (event in closingEvents + otherEvents) assertTrue("$buildType $event", forwardsInBuild(buildType, event))
        }
    }

    @Test
    fun captureBuildHoldsBackOnlyTheEventsThatCloseTheWindow() {
        for (event in closingEvents) assertFalse("$event", forwardsInBuild("capture", event))
        for (event in otherEvents) assertTrue("$event", forwardsInBuild("capture", event))
    }

    @Test
    fun pageLabelDropsQueryAndFragmentAndShortensIdentifierSegments() {
        assertEquals(
            "global.americanexpress.com/card-benefits/view-all",
            capturePageLabel("https://global.americanexpress.com/card-benefits/view-all?account_key=FAKE-ACCOUNT#top"),
        )
        assertEquals("example.com/account/#/summary", capturePageLabel("https://example.com/account/123456/summary"))
        assertEquals("example.com/card/#", capturePageLabel("https://example.com/card/ab12cd34"))
        assertEquals("example.com/k/#", capturePageLabel("https://example.com/k/ABCDEFGHI1"))
        assertEquals("example.com/offers/2024", capturePageLabel("https://example.com/offers/2024"))
        assertEquals("example.com", capturePageLabel("https://example.com"))
    }

    @Test
    fun pageLabelIsNullWithoutAUsableUrl() {
        assertNull(capturePageLabel(null))
        assertNull(capturePageLabel("about:blank"))
        assertNull(capturePageLabel("not a url"))
    }
}

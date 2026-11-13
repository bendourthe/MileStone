package com.bendourthe.milestone.sync

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.BenefitsSpec
import com.bendourthe.milestone.domain.Enrollment
import com.bendourthe.milestone.sync.extract.BridgeMessage
import com.bendourthe.milestone.sync.extract.BridgeProtocol
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real `extract.js` benefits mode (ADR-0008) against synthetic Amex-shaped pages: the card list, one card's
 * activity page reached through its own link, and a switch to a second card. Every message passes through
 * [BridgeProtocol.parse] with the page check, and no identifier decoy may appear in any raw message.
 */
@RunWith(AndroidJUnit4::class)
class BenefitsBridgeFixtureTest {
    private val testAssets = InstrumentationRegistry.getInstrumentation().context
    private val appAssets = InstrumentationRegistry.getInstrumentation().targetContext
    private val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(testAssets)).build()
    private val scenario = ActivityScenario.launch(ComponentActivity::class.java)
    private val raw = mutableListOf<String>()
    private val messages = Channel<Pair<String, String?>>(Channel.UNLIMITED)
    private lateinit var webView: WebView

    private val spec = BenefitsSpec(
        entryUrl = "https://$HOST/assets/fixtures/benefits-rewards.html",
        host = HOST,
        paths = setOf("/assets/fixtures"),
        selectors = StaticCatalogRepository.AMEX_BENEFITS.selectors + mapOf(
            "activityLink" to "a[href*='benefits-activity']",
            "viewAllLink" to "a[href*='benefits-view-all']",
            "activityPath" to "/assets/fixtures/benefits-activity",
            "rewardsPath" to "/assets/fixtures/benefits-",
            "viewAllPath" to "/assets/fixtures/benefits-view-all",
        ),
    )

    @Volatile private var config: String? = null

    @After
    fun tearDown() {
        scenario.onActivity { webView.destroy() }
        scenario.close()
    }

    private fun load(url: String = spec.entryUrl) {
        val script = appAssets.assets.open("extract.js").bufferedReader().use { it.readText() }
        scenario.onActivity { activity ->
            webView = WebView(activity).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        loader.shouldInterceptRequest(request.url)
                }
            }
            val origins = setOf("https://$HOST")
            WebViewCompat.addWebMessageListener(webView, BridgeProtocol.JS_OBJECT_NAME, origins) { view, message, _, isMainFrame, reply ->
                val data = message.data ?: return@addWebMessageListener
                if (!isMainFrame) return@addWebMessageListener
                raw += data
                if (data == """{"type":"ready"}""") config?.let { reply.postMessage(it) } else messages.trySend(data to view.url)
            }
            WebViewCompat.addDocumentStartJavaScript(webView, script, origins)
            activity.setContentView(webView)
            webView.loadUrl(url)
        }
    }

    private suspend fun next(): BridgeMessage {
        val (data, url) = withTimeout(30_000) { messages.receive() }
        return BridgeProtocol.parse(data, NONCE, pageUrl = url, benefits = spec) ?: error("rejected message")
    }

    @Test
    fun readsCardsThenEachCardsBenefitsWithoutIdentifiers() = runBlocking {
        config = BridgeProtocol.benefitsConfig(spec, NONCE, timeoutMs = 25_000, listCards = true)
        load()

        val cards = next() as BridgeMessage.Cards
        assertTrue(cards.list.complete)
        assertEquals(
            listOf("Fixture Platinum Card" to "71008", "Fixture Hotel Card" to "42005", "Fixture Basic Card" to "33006"),
            cards.list.cards.map { it.name to it.lastDigits },
        )

        config = BridgeProtocol.benefitsConfig(spec, NONCE, timeoutMs = 25_000, listCards = false)
        val first = next() as BridgeMessage.Benefits
        assertEquals("71008" to "activity", first.lastDigits to first.page)
        val tracked = first.drafts.filter { it.section != BenefitSection.LISTED }
        assertEquals(
            listOf(
                BenefitSection.SPEND_PROGRESS to "Lounge Complimentary Guest Access",
                BenefitSection.CREDIT_EARNED to "\$50 Shopping Credit",
                BenefitSection.CREDIT_EARNED to "\$15 Ride Credit",
                BenefitSection.COMPLETED to "\$20 Streaming Credit",
            ),
            tracked.map { it.section to it.title },
        )
        // Dollar amounts such as "$4,321.07" and "$150,000" survive the identifier filter.
        assertEquals(listOf(432_107L, 15_000_000L, 15_432_107L), tracked.first().let { listOf(it.earnedCents, it.remainingCents, it.targetCents) })
        assertEquals(listOf("Oct - Dec", "November", "Nov"), tracked.drop(1).map { it.periodLabel })
        val listed = first.drafts.filter { it.section == BenefitSection.LISTED }
        assertEquals(listOf("airline-fee-credit", "lounge-access", "purchase-protection"), listed.map { it.issuerKey })
        assertEquals(listOf(Enrollment.ENROLLMENT_REQUIRED, Enrollment.ENROLLED, null), listed.map { it.enrollment })

        // The Hotel and Basic cards have no activity page; with no second page allowed they post their Rewards trackers.
        config = BridgeProtocol.benefitsConfig(spec, NONCE, timeoutMs = 25_000, listCards = false, targetDigits = "42005", secondPage = false)
        webView.post { webView.reload() }
        val second = next() as BridgeMessage.Benefits
        assertEquals("42005" to "rewards", second.lastDigits to second.page)
        assertEquals(
            // A "spent" tracker that shows a period is spend progress, never a credit.
            listOf(BenefitSection.CREDIT_EARNED to "Jan - Mar", BenefitSection.SPEND_PROGRESS to "Jan - Mar", BenefitSection.COMPLETED to "Jul - Dec"),
            second.drafts.map { it.section to it.periodLabel },
        )

        // A card with no trackers is a valid empty read, not a failure.
        config = BridgeProtocol.benefitsConfig(spec, NONCE, timeoutMs = 25_000, listCards = false, targetDigits = "33006", secondPage = false)
        webView.post { webView.reload() }
        val third = next() as BridgeMessage.Benefits
        assertEquals("33006" to "rewards", third.lastDigits to third.page)
        assertEquals(emptyList<Any>(), third.drafts)

        for (decoy in DECOYS) assertFalse("decoy $decoy reached a message", raw.any { decoy in it })
    }

    @Test
    fun aRewardsPageWaitsForItsLateLowerSectionThenFollowsTheActivityLink() = runBlocking {
        // The lower section with the activity link renders late, and the activity link changes the address in place
        // while the Rewards content stays for 3 s: v0.2.0 posted the Rewards page's 2 trackers in both cases.
        config = BridgeProtocol.benefitsConfig(spec, NONCE, timeoutMs = 25_000, listCards = false)
        load("https://$HOST/assets/fixtures/benefits-rewards-lazy.html")
        val message = next() as BridgeMessage.Benefits
        assertEquals("71008" to "activity", message.lastDigits to message.page)
        assertEquals(
            listOf(BenefitSection.SPEND_PROGRESS, BenefitSection.CREDIT_EARNED, BenefitSection.CREDIT_EARNED, BenefitSection.COMPLETED),
            message.drafts.map { it.section },
        )
        assertEquals("activity" to "stable", message.ready?.kind to message.ready?.reason)
        assertEquals("the posted page still showed the Rewards content", false, message.ready?.activityLink)
    }

    @Test
    fun aRewardsPageFollowsAnActivityLinkThatArrivesAfterTheReadinessCap() = runBlocking {
        assertActivityAfterRewardsCap("late")
    }

    @Test
    fun aRewardsPageFollowsItsActivityLinkAtTheCapWhileItsHeightStillGrows() = runBlocking {
        assertActivityAfterRewardsCap("growing")
    }

    private suspend fun assertActivityAfterRewardsCap(mode: String) {
        config = BridgeProtocol.benefitsConfig(spec, NONCE, timeoutMs = 20_000, listCards = false)
        load("https://$HOST/assets/fixtures/benefits-rewards-cap.html#$mode")
        val message = next() as BridgeMessage.Benefits
        assertEquals("71008" to "activity", message.lastDigits to message.page)
        assertEquals(
            listOf(BenefitSection.SPEND_PROGRESS, BenefitSection.CREDIT_EARNED, BenefitSection.CREDIT_EARNED, BenefitSection.COMPLETED),
            message.drafts.map { it.section },
        )
        assertEquals(false, message.ready?.activityLink)
        assertFalse("an account key reached a message", raw.any { "account_key" in it })
    }

    @Test
    fun anInPlaceSwitchWaitsForTheNewCardsTrackersAcrossTheAddressChange() = runBlocking {
        // The switch changes the address in the same document, empties the trackers, and shows the new ones 7 s later.
        config = BridgeProtocol.benefitsConfig(spec, NONCE, timeoutMs = 25_000, listCards = false, targetDigits = "42005")
        load("https://$HOST/assets/fixtures/benefits-switch-late.html")
        val message = next() as BridgeMessage.Benefits
        assertEquals("42005" to "rewards", message.lastDigits to message.page)
        assertEquals(listOf("\$50 Fixture Hotel Credit", "Fixture Free Night Bonus"), message.drafts.map { it.title })
        assertEquals(true, message.ready?.switched)
        assertFalse("the old card's trackers reached a message", raw.any { "Fixture Dining Credit" in it })
    }

    @Test
    fun aCardWithoutAnActivityPagePostsItsTrackersThenItsViewAllList() = runBlocking {
        config = BridgeProtocol.benefitsConfig(spec, NONCE, timeoutMs = 25_000, listCards = false)
        load("https://$HOST/assets/fixtures/benefits-rewards-2.html")

        val rewards = next() as BridgeMessage.Benefits
        assertEquals(Triple("42005", "rewards", "viewAll"), Triple(rewards.lastDigits, rewards.page, rewards.next))
        assertEquals(3, rewards.drafts.size)

        val viewAll = next() as BridgeMessage.Benefits
        assertEquals("42005" to "viewAll", viewAll.lastDigits to viewAll.page)
        val listed = viewAll.drafts.filter { it.section == BenefitSection.LISTED }
        assertEquals(listOf("fixture-lounge", "fixture-status", "fixture-night", "fixture-protection"), listed.map { it.issuerKey })
        assertEquals(listOf(Enrollment.ENROLLED, Enrollment.ENROLLMENT_REQUIRED, null, null), listed.map { it.enrollment })
        assertEquals("Membership # earns synthetic status.", listed[1].description)
        assertFalse("the membership decoy reached a message", raw.any { "987654321" in it })
        assertFalse("an account key reached a message", raw.any { "account_key" in it })
    }

    private companion object {
        const val HOST = "appassets.androidplatform.net"
        const val NONCE = "benefits-fixture"
        val DECOYS = listOf("1234 5678 9012", "12345678", "123456789012", "ABCDEFGHIJ123KL", "ZYXWVUTSRQ987PO", "QWERTYUIOP456AS", "account_key")
    }
}

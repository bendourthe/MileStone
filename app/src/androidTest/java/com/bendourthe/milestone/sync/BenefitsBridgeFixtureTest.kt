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
            "activityPath" to "/assets/fixtures/benefits-activity",
        ),
    )

    @Volatile private var config: String? = null

    @After
    fun tearDown() {
        scenario.onActivity { webView.destroy() }
        scenario.close()
    }

    private fun load() {
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
            webView.loadUrl(spec.entryUrl)
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

        config = BridgeProtocol.benefitsConfig(spec, NONCE, timeoutMs = 25_000, listCards = false, targetDigits = "42005")
        webView.post { webView.reload() }
        val second = next() as BridgeMessage.Benefits
        assertEquals("42005" to "rewards", second.lastDigits to second.page)
        assertEquals(
            // A "spent" tracker that shows a period is spend progress, never a credit.
            listOf(BenefitSection.CREDIT_EARNED to "Jan - Mar", BenefitSection.SPEND_PROGRESS to "Jan - Mar", BenefitSection.COMPLETED to "Jul - Dec"),
            second.drafts.map { it.section to it.periodLabel },
        )

        // A card with no trackers is a valid empty read, not a failure.
        config = BridgeProtocol.benefitsConfig(spec, NONCE, timeoutMs = 25_000, listCards = false, targetDigits = "33006")
        webView.post { webView.reload() }
        val third = next() as BridgeMessage.Benefits
        assertEquals("33006" to "rewards", third.lastDigits to third.page)
        assertEquals(emptyList<Any>(), third.drafts)

        for (decoy in DECOYS) assertFalse("decoy $decoy reached a message", raw.any { decoy in it })
    }

    private companion object {
        const val HOST = "appassets.androidplatform.net"
        const val NONCE = "benefits-fixture"
        val DECOYS = listOf("1234 5678 9012", "12345678", "123456789012", "ABCDEFGHIJ123KL", "ZYXWVUTSRQ987PO", "QWERTYUIOP456AS", "account_key")
    }
}

package com.bendourthe.milestone.sync

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.data.session.TinkSessionStore
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.BenefitsAdapter
import com.bendourthe.milestone.domain.BenefitsSpec
import com.bendourthe.milestone.domain.CardSegment
import com.bendourthe.milestone.domain.Connection
import com.bendourthe.milestone.domain.Enrollment
import com.bendourthe.milestone.domain.ExtractionSpec
import com.bendourthe.milestone.domain.PeriodLength
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.domain.ProgramCategory
import com.bendourthe.milestone.domain.SelectorStrategy
import com.bendourthe.milestone.domain.SyncCapability
import com.bendourthe.milestone.sync.extract.BindingHasher
import com.bendourthe.milestone.sync.webview.ActivityWebViewHost
import com.bendourthe.milestone.sync.webview.ChaseBenefitsSource
import com.bendourthe.milestone.sync.webview.WebViewProfileBackend
import com.google.crypto.tink.Aead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real `extract.js` Chase mode and [ChaseBenefitsSource] (ADR-0009) on a synthetic Chase-shaped page: the
 * owner's path (card, More, Benefits), the card-selector sheet, card switches, rows, tags, values and a tracker.
 */
@RunWith(AndroidJUnit4::class)
class ChaseBenefitsFixtureTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val testAssets = InstrumentationRegistry.getInstrumentation().context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionFile = File(app.cacheDir, "chase-fixture-sessions.pb").apply { delete() }
    private val sessions = TinkSessionStore(sessionFile, PassThroughAead, scope) {}
    private val scenario = ActivityScenario.launch(ComponentActivity::class.java)
    private val hasher = BindingHasher(ByteArray(32) { 7 })
    private val variants = ArrayDeque<String>()

    private val program = Program(
        programId = "chase",
        displayName = "Synthetic Chase",
        category = ProgramCategory.CREDIT_CARD,
        monogram = "CH",
        logoKey = null,
        isPopular = false,
        brandColorArgb = 0xFF117ACA,
        unitLabel = "pts",
        loginUrl = "https://$HOST/assets/fixtures/chase-benefits.html",
        dashboardUrl = "https://$HOST/assets/fixtures/chase-benefits.html",
        allowedHosts = setOf(HOST),
        identityProviderHosts = emptySet(),
        syncCapability = SyncCapability.Automatic(ExtractionSpec.Selector(SelectorStrategy.CSS, ".none", loggedInUrlPattern = Regex("/fixtures/"), loginUrlPattern = Regex("/signin"))),
        benefits = BenefitsSpec(
            entryUrl = "https://$HOST/assets/fixtures/chase-benefits.html",
            host = HOST,
            paths = setOf("/assets/fixtures"),
            selectors = StaticCatalogRepository.CHASE_BENEFITS.selectors,
            adapter = BenefitsAdapter.CHASE,
        ),
    )

    @After
    fun tearDown() {
        scenario.close()
        scope.cancel()
        sessionFile.delete()
    }

    /** Serves the fixture with the next queued variant (or the last one) for each load of the page. */
    private fun intercept(path: String): WebResourceResponse? {
        if (!path.endsWith("/assets/fixtures/chase-benefits.html")) return null
        val variant = if (variants.size > 1) variants.removeFirst() else variants.first()
        val html = testAssets.assets.open("fixtures/chase-benefits.html").bufferedReader().use { it.readText() }.replace("__VARIANT__", variant)
        return WebResourceResponse("text/html", "utf-8", html.byteInputStream())
    }

    private fun source(connections: List<Connection>) = ChaseBenefitsSource(
        WebViewProfileBackend(), sessions, connections = { connections }, hasher = { hasher },
        interceptor = { request -> intercept(request.url.path.orEmpty()) },
    )

    private suspend fun read(connections: List<Connection>): BenefitsRead {
        connections.forEach { sessions.markConnected(it.connectionId, 1) }
        lateinit var host: WebViewHost
        scenario.onActivity { host = ActivityWebViewHost(it) }
        return source(connections).read(program, host, 120_000)
    }

    private val personal = Connection("chase", "chase", CardSegment.PERSONAL, 0, 1)
    private val business = Connection("chase_2", "chase", CardSegment.BUSINESS, 1, 2)

    @Test
    fun followsTheOwnerPathListsCardsAndReadsEachCard() = runBlocking {
        variants += "two"

        val read = read(listOf(personal))

        assertNull(read.stop)
        val inventory = read.inventories.single()
        assertTrue(inventory.complete)
        assertEquals(listOf("Synthetic Reserve", "Synthetic Freedom"), inventory.cards.map { it.name })
        assertEquals(listOf("1111", "2222"), inventory.cards.map { it.lastDigits })
        assertEquals(inventory.cards.map { it.bindingHash }.toSet(), read.bindingBenefits.keys)

        val reserve = read.bindingBenefits.getValue(inventory.cards[0].bindingHash)
        val travel = reserve.single { it.title == "Synthetic travel credit" }
        assertEquals(30_000L, travel.targetCents)
        assertEquals(PeriodLength.YEARLY, travel.periodLength)
        assertEquals(Enrollment.ENROLLED, travel.enrollment)
        assertEquals(1_000L, reserve.single { it.title == "Synthetic dining credit" }.targetCents)
        assertTrue(reserve.single { it.title == "Synthetic lounge access" }.completed)
        val tracker = reserve.single { it.section == BenefitSection.SPEND_PROGRESS }
        assertEquals(150_000L, tracker.earnedCents)
        assertEquals(400_000L, tracker.targetCents)

        val freedom = read.bindingBenefits.getValue(inventory.cards[1].bindingHash)
        assertEquals(listOf("Synthetic bonus category"), freedom.map { it.title })
    }

    @Test
    fun readsSpendProgressFromChaseGaugeAccessibleText() = runBlocking {
        variants += "gauge"

        val read = read(listOf(personal))

        assertNull(read.stop)
        val reserve = read.bindingBenefits.getValue(read.inventories.single().cards[0].bindingHash)
        val tracker = reserve.single { it.section == BenefitSection.SPEND_PROGRESS }
        assertEquals(150_000L, tracker.earnedCents)
        assertEquals(400_000L, tracker.targetCents)
        assertEquals(250_000L, tracker.remainingCents)
        assertEquals("Synthetic unlock", tracker.title)
        assertEquals("\$10 - Up to \$5 per transaction", reserve.single { it.title == "Synthetic dining credit" }.description)
    }

    @Test
    fun aSecondConnectionReadsOnlyCardsNotAlreadyRead() = runBlocking {
        variants += listOf("two", "three")

        val read = read(listOf(personal, business))

        assertEquals(2, read.inventories.size)
        assertEquals(2, read.inventories[0].cards.size)
        assertEquals(3, read.inventories[1].cards.size)
        // The shared cards bind to the same hashes; only the third card is read through the second connection.
        assertEquals(read.inventories[0].cards.map { it.bindingHash }, read.inventories[1].cards.take(2).map { it.bindingHash })
        assertEquals(3, read.bindingBenefits.size)
        assertEquals(listOf("Synthetic shipping credit"), read.bindingBenefits.getValue(read.inventories[1].cards[2].bindingHash).map { it.title })
    }

    @Test
    fun anAccountWithMoreCardsThanAnyCaptureIsReadEvenWhenEachSwitchReloadsThePage() = runBlocking {
        variants += "reload"
        val read = read(listOf(personal))

        assertNull(read.stop)
        val inventory = read.inventories.single()
        assertTrue(inventory.complete)
        assertEquals(10, inventory.cards.size)
        assertEquals(inventory.cards.map { it.bindingHash }.toSet(), read.bindingBenefits.keys)
        assertEquals(listOf("Synthetic perk 10"), read.bindingBenefits.getValue(inventory.cards.last().bindingHash).map { it.title })
    }

    @Test
    fun aCardSelectorThatListsItsCardsInBatchesIsReadInFull() = runBlocking {
        variants += "batch"
        val read = read(listOf(personal))

        assertNull(read.stop)
        assertEquals(listOf("Synthetic Reserve", "Synthetic Freedom"), read.inventories.single().cards.map { it.name })
        assertEquals(2, read.bindingBenefits.size)
    }

    @Test
    fun aCardWithMoreRowsThanTheCapIsLeftUnreadInsteadOfTruncated() = runBlocking {
        variants += "overflow"
        val read = read(listOf(personal))

        val inventory = read.inventories.single()
        assertEquals(2, inventory.cards.size)
        assertEquals(setOf(inventory.cards[0].bindingHash), read.bindingBenefits.keys)
    }

    @Test
    fun chaseShapedComponentsAndAnInPlaceBottomSheetReachTheBenefitsPage() = runBlocking {
        variants += "sheet"
        val read = read(listOf(personal))

        assertNull(read.stop)
        val inventory = read.inventories.single()
        assertEquals(listOf("Synthetic Reserve", "Synthetic Freedom"), inventory.cards.map { it.name })
        assertEquals(2, read.bindingBenefits.size)
    }

    @Test
    fun theHomePageGroupingMarksEachListedCardPersonalOrBusiness() = runBlocking {
        variants += "groups"
        val read = read(listOf(personal))

        assertNull(read.stop)
        val bySegment = read.inventories.single().cards.associate { it.lastDigits to it.segment }
        assertEquals(mapOf("1111" to CardSegment.PERSONAL, "2222" to CardSegment.PERSONAL, "3333" to CardSegment.BUSINESS), bySegment)
    }

    @Test
    fun aMissingMoreControlStopsWithoutAnInventory() = runBlocking {
        variants += "nomore"

        val read = read(listOf(personal))

        assertTrue(read.inventories.isEmpty())
        assertTrue(read.bindingBenefits.isEmpty())
    }

    @Test
    fun aPaymentControlShowingAnEndingIsNeverPressed() = runBlocking {
        variants += "pay"

        val read = read(listOf(personal))

        assertNull(read.stop)
        assertEquals(2, read.inventories.single().cards.size)
        assertEquals(2, read.bindingBenefits.size)
    }

    @Test
    fun anIdentityCheckStopsTheReadWithoutChoosingADeliveryOption() = runBlocking {
        variants += "verify"

        val read = read(listOf(personal))

        assertEquals(BenefitsStop.SIGNED_OUT, read.stop)
        assertTrue(read.inventories.isEmpty())
        assertTrue(read.bindingBenefits.isEmpty())
    }

    @Test
    fun aConnectionWithoutASessionIsSkipped() = runBlocking {
        variants += "two"
        lateinit var host: WebViewHost
        scenario.onActivity { host = ActivityWebViewHost(it) }

        val read = source(listOf(personal)).read(program, host, 120_000)

        assertTrue(read.inventories.isEmpty())
    }

    private object PassThroughAead : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?) = plaintext
        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?) = ciphertext
    }

    private companion object {
        const val HOST = "appassets.androidplatform.net"
    }
}

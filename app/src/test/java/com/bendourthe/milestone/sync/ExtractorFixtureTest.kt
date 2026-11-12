package com.bendourthe.milestone.sync

import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.domain.ExtractionSpec
import com.bendourthe.milestone.domain.SelectorStrategy
import com.bendourthe.milestone.domain.SyncCapability
import com.bendourthe.milestone.sync.extract.BalanceParser
import org.jsoup.Jsoup
import org.jsoup.helper.W3CDom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.w3c.dom.NodeList
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * Runs each verified selector against the committed synthetic fixture and, when present locally, a
 * private capture. A private capture is never on CI, so its absence is a visible skip, never a pass.
 */
@RunWith(Parameterized::class)
class ExtractorFixtureTest(private val programId: String, private val folder: String) {

    @Test
    fun selectorMatchesExactlyOneBalance() {
        val html = resource("$folder/$programId.html")
        assumeTrue("$programId: no $folder capture on this machine", html != null)
        val texts = matches(spec(programId), html!!)
        assertEquals("$programId: selector match count in $folder", 1, texts.size)
        val parsed = BalanceParser.parse(texts.single(), catalog.program(programId)!!.balanceUnit)
        assertNotNull("$programId: matched text in $folder does not parse as a balance", parsed)

        // Only synthetic fixtures carry an exact value. A private capture holds a real balance, which
        // nobody types in or reads out, so for it one match that parses is the whole check.
        if (folder == PRIVATE) return
        val expected = resource("$folder/$programId.expected")?.trim()?.toLong()
        assertNotNull("$programId: $folder/$programId.expected is missing", expected)
        assertEquals("$programId: parsed balance in $folder", expected, parsed)
    }

    companion object {
        private val catalog = StaticCatalogRepository()
        private const val PRIVATE = "fixtures-private"

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun cases(): List<Array<String>> = catalog.programs
            .filter { program -> (program.syncCapability as? SyncCapability.Automatic)?.spec is ExtractionSpec.Selector }
            .flatMap { listOf(arrayOf(it.programId, "fixtures"), arrayOf(it.programId, PRIVATE)) }

        private fun spec(programId: String): ExtractionSpec.Selector =
            (catalog.program(programId)!!.syncCapability as SyncCapability.Automatic).spec as ExtractionSpec.Selector

        /** Mirrors `extract.js`: every match's trimmed `textContent`, in document order. */
        fun matches(spec: ExtractionSpec.Selector, html: String): List<String> {
            val document = Jsoup.parse(html)
            return when (spec.strategy) {
                SelectorStrategy.CSS -> document.select(spec.expression).map { it.wholeText().trim() }
                SelectorStrategy.XPATH -> {
                    val w3c = W3CDom().namespaceAware(false).fromJsoup(document)
                    val nodes = XPathFactory.newInstance().newXPath()
                        .evaluate(spec.expression, w3c, XPathConstants.NODESET) as NodeList
                    (0 until nodes.length).map { nodes.item(it).textContent.trim() }
                }
            }
        }

        private fun resource(path: String): String? =
            ExtractorFixtureTest::class.java.classLoader?.getResource(path)?.readText()
    }
}

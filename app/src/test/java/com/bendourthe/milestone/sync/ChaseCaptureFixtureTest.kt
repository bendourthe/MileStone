package com.bendourthe.milestone.sync

import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.sync.extract.ChaseBenefitsReader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Count-only regression over the owner's private Chase captures (ADR-0009), which never leave this machine. Builds
 * the message `extract.js` would post with the Chase spec's selectors and caps, then validates it with the app's
 * reader. Never prints page text. Skipped when no capture is present, unless
 * MILESTONE_REQUIRE_CHASE_PRIVATE_CAPTURES=1, which makes missing captures fail.
 */
class ChaseCaptureFixtureTest {
    private val s = StaticCatalogRepository.CHASE_BENEFITS.selectors

    private fun captures(): List<File> {
        val root = javaClass.classLoader!!.getResource(PRIVATE)?.let { File(it.toURI()) }
        val files = root?.walkTopDown()?.filter { it.isFile && it.extension == "html" && it.path.contains("chase", ignoreCase = true) }?.toList().orEmpty()
        if (System.getenv("MILESTONE_REQUIRE_CHASE_PRIVATE_CAPTURES") == "1") assertTrue("required private Chase captures are missing", files.isNotEmpty())
        assumeTrue("no private Chase captures on this machine", files.isNotEmpty())
        return files.sorted()
    }

    private fun text(el: Element?, max: Int): String? = el?.text()?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.take(max)

    @Test
    fun everyCapturedRowAndTrackerBecomesExactlyOneDraft() {
        var pages = 0
        var rowsTotal = 0
        for (file in captures()) {
            val doc = Jsoup.parse(file, "utf-8")
            if (doc.select(s.getValue("page")).isEmpty()) continue
            pages++
            var rows = 0
            val groups = buildJsonArray {
                doc.select(s.getValue("group")).take(ChaseBenefitsReader.MAX_GROUPS).forEach { list ->
                    val heading = list.parent()?.parent()?.selectFirst("h2")
                    add(buildJsonObject {
                        text(heading, 80)?.let { put("title", it) } ?: put("title", JsonNull)
                        put("rows", buildJsonArray {
                            list.select(s.getValue("row")).forEach { row ->
                                val title = text(row.selectFirst(s.getValue("rowTitle")), 120) ?: return@forEach
                                if (rows >= ChaseBenefitsReader.MAX_ROWS) return@forEach
                                rows++
                                add(buildJsonObject {
                                    put("title", title)
                                    text(row.selectFirst(s.getValue("rowTag")), 40)?.let { put("tag", it) } ?: put("tag", JsonNull)
                                    text(row.selectFirst(s.getValue("rowValue")), 60)?.let { put("value", it) } ?: put("value", JsonNull)
                                })
                            }
                        })
                    })
                }
            }
            val trackers = buildJsonArray {
                doc.select(s.getValue("tracker")).take(ChaseBenefitsReader.MAX_TRACKERS).forEach { bar ->
                    val holder = bar.closest("a") ?: bar.parent()?.parent() ?: bar
                    add(buildJsonObject {
                        put("text", text(holder, 300) ?: "tracker")
                        put("value", bar.attr("value").toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonNull)
                        put("max", bar.attr("max").toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonNull)
                    })
                }
            }
            val drafts = ChaseBenefitsReader.benefits(groups, trackers)
            assertNotNull("capture ${pages}: reader rejected the page message", drafts)
            assertEquals("capture ${pages}: drafts per row and tracker", rows + (trackers as JsonArray).size, drafts!!.size)
            assertTrue("capture ${pages}: no rows found", rows > 0)
            rowsTotal += rows
        }
        assumeTrue("no Benefits page among the private captures", pages > 0)
        println("Chase private captures: benefits pages=$pages rows=$rowsTotal")
    }

    @Test
    fun selectorOpenCapturesListOneControlPerOption() {
        var sheets = 0
        for (file in captures()) {
            val doc = Jsoup.parse(file, "utf-8")
            val sheet = doc.selectFirst(s.getValue("sheet")) ?: continue
            sheets++
            val options = sheet.select(s.getValue("sheetOption"))
            val radios = options.mapNotNull { it.selectFirst("input[type=radio]") }
            assertEquals("sheet $sheets: one radio per option", options.size, radios.size)
            // The capture sanitizer masks the identifier part of each control id with '#', so only presence is checkable
            // here; the live probes of 2026-11-17/18 recorded the real ids as accepted control ids.
            assertTrue("sheet $sheets: control ids present", radios.all { it.id().isNotEmpty() })
            assertEquals("sheet $sheets: exactly one selected", 1, radios.count { it.hasAttr("checked") })
        }
        assumeTrue("no open card-selector sheet among the private captures", sheets > 0)
        println("Chase private captures: selector sheets=$sheets")
    }

    private companion object {
        const val PRIVATE = "fixtures-private"
    }
}

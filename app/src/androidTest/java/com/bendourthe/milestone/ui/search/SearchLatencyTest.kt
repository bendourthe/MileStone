package com.bendourthe.milestone.ui.search

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.File

/** Optional debug diagnostic; CI measures the same scenario in the optimized benchmark variant. */
@RunWith(AndroidJUnit4::class)
class SearchLatencyTest {
    @get:Rule val rule = ActivityScenarioRule(ComponentActivity::class.java)
    private lateinit var scenario: SearchLatencyScenario

    @Before fun setup() {
        val delay = InstrumentationRegistry.getArguments().getString("searchLatencyUiDelayMs")?.toLong() ?: 0L
        require(delay in 0L..1000L)
        rule.scenario.onActivity { scenario = SearchLatencyScenario(it, delay) }
        scenario.setup()
    }

    @After fun close() {
        if (::scenario.isInitialized) scenario.close()
    }

    @Test fun thirtySettledQueriesRenderWithinTwoHundredMillisecondsAtP95() {
        val result = scenario.run()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File(context.getExternalFilesDir(null), "search-latency.json")
        output.writeText(result.toString(2))
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp ${output.absolutePath} /sdcard/Download/milestone-search-latency.json").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
        assertTrue("Query-to-visible p95 was ${result.getDouble("p95_ms")} ms; limit is 200 ms", result.getDouble("p95_ms") <= 200.0)
    }
}

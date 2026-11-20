package com.bendourthe.milestone.ui.search

import android.os.Bundle
import android.os.Build
import android.content.ContentValues
import android.provider.MediaStore
import android.content.pm.ApplicationInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

class SearchBenchmarkActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        check(packageName == "com.bendourthe.milestone.benchmark")
        check(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        val rawId = requireNotNull(intent.getStringExtra("run_id"))
        val id = UUID.fromString(rawId).toString()
        check(id == rawId)
        val delay = intent.getLongExtra("delay_ms", 0)
        require(delay in 0L..1000L)
        executor.execute {
            val scenario = SearchLatencyScenario(this, delay)
            var result: JSONObject
            try {
                scenario.setup()
                result = scenario.run()
                scenario.close()
                val cleared = java.util.concurrent.CountDownLatch(1)
                runOnUiThread { setContent { }; cleared.countDown() }
                check(cleared.await(10, java.util.concurrent.TimeUnit.SECONDS))
                result.put("complete", true)
            } catch (error: Throwable) {
                result = JSONObject().put("complete", false).put("error_type", error.javaClass.name)
                try { scenario.close() } catch (_: Throwable) { }
            }
            result.put("run_id", id).put("target_package", packageName).put("debuggable", false)
            result.put("scenario_contract", "query-to-visible-v1")
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "search-$id.json")
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/MileStoneSearchProbe/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = requireNotNull(contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            requireNotNull(contentResolver.openOutputStream(uri)).use { it.write(result.toString().toByteArray()) }
            check(contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
            sendBroadcast(android.content.Intent("com.bendourthe.milestone.searchbenchmark.COMPLETE").setPackage("com.bendourthe.milestone.searchbenchmark").putExtra("run_id", id), "com.bendourthe.milestone.benchmark.PROBE")
            runOnUiThread { finish() }
        }
    }
    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}

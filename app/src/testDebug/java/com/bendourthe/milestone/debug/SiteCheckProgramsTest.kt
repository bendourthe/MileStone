package com.bendourthe.milestone.debug

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bendourthe.milestone.sync.webview.HostPolicy
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SiteCheckProgramsTest {
    private val programs: List<SiteCheckProgram> by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.assets.open("site_check_programs.json").bufferedReader().use { Json.decodeFromString(it.readText()) }
    }

    @Test
    fun listsFourteenUniquePrograms() {
        assertEquals(14, programs.size)
        assertEquals(14, programs.map { it.id }.toSet().size)
    }

    @Test
    fun everySignInUrlIsHttps() {
        programs.forEach { assertNotNull(it.id, HostPolicy.httpsHost(it.signInUrl)) }
    }
}

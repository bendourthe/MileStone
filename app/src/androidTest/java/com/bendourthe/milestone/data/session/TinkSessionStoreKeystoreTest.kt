package com.bendourthe.milestone.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TinkSessionStoreKeystoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = File(context.cacheDir, "keystore-test-sessions.pb").apply { delete() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun roundTripsWithTheKeystoreKeyAndStoresNoPlaintext() = runBlocking {
        val aead = TinkSessionStore.keystoreAead(context, file) {}
        val store = TinkSessionStore(file, aead, scope) {}
        store.markConnected("amex", 100)
        assertEquals(SessionRecord(100, 100), store.get("amex"))
        assertFalse(file.readBytes().decodeToString().contains("amex"))
    }
}

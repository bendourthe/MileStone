package com.bendourthe.milestone.data.session

import com.google.crypto.tink.Aead
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.GeneralSecurityException

/** Reversible stand-in for Tink: XORs the payload and checks the associated data. */
class FakeAead(private val key: Byte = 0x5A) : Aead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        associatedData + byteArrayOf(0) + plaintext.map { (it.toInt() xor key.toInt()).toByte() }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        val prefix = associatedData + byteArrayOf(0)
        if (ciphertext.size < prefix.size || !ciphertext.copyOfRange(0, prefix.size).contentEquals(prefix)) throw GeneralSecurityException("bad tag")
        return ciphertext.copyOfRange(prefix.size, ciphertext.size).map { (it.toInt() xor key.toInt()).toByte() }.toByteArray()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TinkSessionStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var resets = 0

    private fun TestScope.store(name: String = "s.pb") =
        TinkSessionStore(tmp.root.resolve(name), FakeAead(), backgroundScope) { resets++ }

    @Test
    fun roundTripVerifyAndClear() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.markConnected("amex", 100)
        store.markConnected("hilton", 150)
        store.markVerified("amex", 200)
        store.markVerified("unknown", 300)
        assertEquals(SessionRecord(100, 200), store.get("amex"))
        assertNull(store.get("unknown"))
        store.clear("amex")
        assertNull(store.get("amex"))
        assertEquals(setOf("hilton"), store.all().keys)
        store.clearAll()
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun storedBytesAreNotPlaintext() = runTest(UnconfinedTestDispatcher()) {
        store().markConnected("amex", 100)
        val bytes = tmp.root.resolve("s.pb").readBytes().decodeToString()
        assertFalse(bytes.contains("amex"))
    }

    @Test
    fun undecryptableFileResetsAndRequestsReconnect() = runTest(UnconfinedTestDispatcher()) {
        tmp.root.resolve("bad.pb").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val store = store("bad.pb")
        assertTrue(store.all().isEmpty())
        assertEquals(1, resets)
        store.markConnected("amex", 1)
        assertEquals(SessionRecord(1, 1), store.get("amex"))
    }
}

package com.bendourthe.milestone.data.credentials

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

@RunWith(RobolectricTestRunner::class)
class FileCredentialVaultTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Stands in for the Keystore: reversible, and refuses data sealed for a different program. */
    private class FakeBox : SecretBox {
        override fun seal(plaintext: ByteArray, associatedData: ByteArray) =
            associatedData.size.toByte().let { byteArrayOf(it) } + associatedData + plaintext.map { (it.toInt() xor 0x5A).toByte() }

        override fun open(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            val size = ciphertext[0].toInt()
            if (!ciphertext.copyOfRange(1, 1 + size).contentEquals(associatedData)) throw GeneralSecurityException("Wrong program")
            return ciphertext.copyOfRange(1 + size, ciphertext.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private fun vault() = FileCredentialVault(folder.root, FakeBox())

    @Test
    fun savedSignInSurvivesARestartWithoutPlaintextOnDisk() = runTest {
        vault().save("chase", Credentials("owner@example.com", "hunter2-secret"), readTexts = true)
        val bytes = folder.root.listFiles()!!.single().readBytes().decodeToString()
        assertFalse(bytes.contains("hunter2-secret"))

        val reopened = vault().apply { load() }
        assertEquals(AutoSignInStatus(saved = true, blocked = false, readTexts = true), reopened.statuses.value["chase"])
        assertEquals("hunter2-secret", reopened.usable("chase")!!.password)
    }

    @Test
    fun aWriteCutOffBeforeItsRenameNeitherCrashesTheLoadNorHidesTheOtherSignIns() = runTest {
        vault().save("chase", Credentials("owner", "pw"), readTexts = false)
        // What a process killed between writing and renaming leaves behind.
        java.io.File(folder.root, "amex.sealed.tmp").writeBytes(byteArrayOf(1, 2, 3))
        val reopened = vault().apply { load() }
        assertEquals(setOf("chase"), reopened.statuses.value.keys)
        assertFalse(java.io.File(folder.root, "amex.sealed.tmp").exists())
    }

    @Test
    fun aSavedLastNameIsSealedAndReturnedWithTheSignIn() = runTest {
        vault().save("hyatt", Credentials("owner", "pw", "Lastname-secret"), readTexts = false)
        assertFalse(folder.root.listFiles()!!.single().readBytes().decodeToString().contains("Lastname-secret"))
        assertEquals("Lastname-secret", vault().apply { load() }.usable("hyatt")!!.lastName)
    }

    @Test
    fun blockedSignInIsNotUsableUntilUnblocked() = runTest {
        val vault = vault()
        vault.save("amex", Credentials("owner", "pw"), readTexts = false)
        vault.block("amex")
        assertNull(vault.usable("amex"))
        assertTrue(vault.statuses.value["amex"]!!.blocked)
        vault.unblock("amex")
        assertEquals("pw", vault.usable("amex")!!.password)
    }

    @Test
    fun aBlockFromBeforeStopReasonsIsIgnoredButANewBlockIsKept() = runTest {
        val legacy = """{"username":"owner","password":"pw","readTexts":false,"blocked":true}""".encodeToByteArray()
        java.io.File(folder.root, "chase.sealed").writeBytes(FakeBox().seal(legacy, "milestone-signin-v1:chase".encodeToByteArray()))
        val vault = vault().apply { load() }
        assertFalse(vault.statuses.value["chase"]!!.blocked)
        assertEquals("pw", vault.usable("chase")!!.password)

        vault.block("chase")
        assertNull(vault().apply { load() }.usable("chase"))
    }

    @Test
    fun savingAgainClearsTheBlock() = runTest {
        val vault = vault()
        vault.save("amex", Credentials("owner", "pw"), readTexts = false)
        vault.block("amex")
        vault.save("amex", Credentials("owner", "new"), readTexts = false)
        assertEquals("new", vault.usable("amex")!!.password)
    }

    @Test
    fun fileSwappedFromAnotherProgramIsRejectedAndDeleted() = runTest {
        val vault = vault()
        vault.save("amex", Credentials("owner", "pw"), readTexts = false)
        val amex = folder.root.listFiles()!!.single()
        amex.copyTo(folder.root.resolve("chase.sealed"))
        assertNull(vault.usable("chase"))
        assertFalse(folder.root.resolve("chase.sealed").exists())
        assertEquals("pw", vault.usable("amex")!!.password)
    }

    @Test
    fun deleteRemovesTheFileAndStatus() = runTest {
        val vault = vault()
        vault.save("chase", Credentials("owner", "pw"), readTexts = false)
        vault.delete("chase")
        assertNull(vault.usable("chase"))
        assertNull(vault.statuses.value["chase"])
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    @Test
    fun credentialsNeverPrintTheirValues() {
        assertEquals("Credentials(username=<redacted>, password=<redacted>)", Credentials("owner", "pw").toString())
    }
}

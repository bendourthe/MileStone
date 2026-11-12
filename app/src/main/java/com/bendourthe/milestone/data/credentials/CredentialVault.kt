package com.bendourthe.milestone.data.credentials

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.bendourthe.milestone.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A saved sign-in; [lastName] is empty unless the program asks for it. [toString] never prints the values. */
class Credentials(val username: String, val password: String, val lastName: String = "") {
    override fun toString() = "Credentials(username=<redacted>, password=<redacted>)"
}

/** Whether a program can sign itself in, without exposing the saved values. */
data class AutoSignInStatus(val saved: Boolean, val blocked: Boolean, val readTexts: Boolean)

/** Saved sign-ins, encrypted on this phone only; nothing here is backed up, logged, or sent anywhere. */
interface CredentialVault {
    /** Program id to status, for the UI; holds no secrets. */
    val statuses: StateFlow<Map<String, AutoSignInStatus>>

    /** The saved sign-in, or null when none is saved or automatic sign-in is blocked. */
    suspend fun usable(programId: String): Credentials?
    suspend fun save(programId: String, credentials: Credentials, readTexts: Boolean)
    suspend fun delete(programId: String)

    /** Stops automatic sign-in after a refused or unfinished attempt, until the user signs in or saves again. */
    suspend fun block(programId: String)
    suspend fun unblock(programId: String)
}

/** Seals bytes so only this app on this phone can open them. */
interface SecretBox {
    fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray
    fun open(ciphertext: ByteArray, associatedData: ByteArray): ByteArray
}

/**
 * [legacyBlocked] was set by any stopped sign-in before ADR-0005 and is ignored, so those programs try once more;
 * [stopped] is set only for a refused password or a twice-unfinished sign-in.
 */
@Serializable
private data class StoredSignIn(
    val username: String,
    val password: String,
    val readTexts: Boolean,
    @SerialName("blocked") val legacyBlocked: Boolean = false,
    val stopped: Boolean = false,
    val lastName: String = "",
)

/** One sealed file per program, so a damaged file loses only that program's sign-in. */
class FileCredentialVault(private val directory: File, private val box: SecretBox) : CredentialVault {
    private val mutex = Mutex()
    private val _statuses = MutableStateFlow<Map<String, AutoSignInStatus>>(emptyMap())
    override val statuses: StateFlow<Map<String, AutoSignInStatus>> = _statuses.asStateFlow()

    /** Reads every file once so [statuses] is current; call before showing the UI. */
    suspend fun load() = mutex.withLock {
        val ids = withContext(Dispatchers.IO) {
            val files = directory.listFiles().orEmpty()
            // A write cut off between writing and renaming leaves a temporary file; it never holds a usable sign-in.
            files.filter { it.name.endsWith("$SUFFIX.tmp") }.forEach { it.delete() }
            files.map { it.name }.filter { it.endsWith(SUFFIX) }.map { it.removeSuffix(SUFFIX) }.filter { ID.matches(it) }
        }
        _statuses.value = ids.mapNotNull { id -> read(id)?.let { id to it.status() } }.toMap()
    }

    override suspend fun usable(programId: String): Credentials? = mutex.withLock {
        read(programId)?.takeIf { !it.stopped }?.let { Credentials(it.username, it.password, it.lastName) }
    }

    override suspend fun save(programId: String, credentials: Credentials, readTexts: Boolean) = mutex.withLock {
        write(programId, StoredSignIn(credentials.username, credentials.password, readTexts, lastName = credentials.lastName))
    }

    override suspend fun delete(programId: String) = mutex.withLock {
        withContext(Dispatchers.IO) { file(programId).delete() }
        _statuses.value -= programId
    }

    override suspend fun block(programId: String) = setBlocked(programId, true)

    override suspend fun unblock(programId: String) = setBlocked(programId, false)

    private suspend fun setBlocked(programId: String, blocked: Boolean) = mutex.withLock {
        val stored = read(programId) ?: return@withLock
        if (stored.stopped != blocked || stored.legacyBlocked) write(programId, stored.copy(legacyBlocked = false, stopped = blocked))
    }

    private suspend fun read(programId: String): StoredSignIn? = withContext(Dispatchers.IO) {
        val file = file(programId)
        if (!file.exists()) return@withContext null
        try {
            Json.decodeFromString(StoredSignIn.serializer(), box.open(file.readBytes(), aad(programId)).decodeToString())
        } catch (e: Exception) {
            if (e !is GeneralSecurityException && e !is IOException && e !is SerializationException) throw e
            DiagnosticLog.w(TAG, "Saved sign-in for $programId unreadable; deleting it")
            file.delete()
            _statuses.value -= programId
            null
        }
    }

    private suspend fun write(programId: String, stored: StoredSignIn) {
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val sealed = box.seal(Json.encodeToString(StoredSignIn.serializer(), stored).encodeToByteArray(), aad(programId))
            val temp = File(directory, "$programId$SUFFIX.tmp")
            temp.writeBytes(sealed)
            if (!temp.renameTo(file(programId))) {
                file(programId).delete()
                check(temp.renameTo(file(programId))) { "Saved sign-in for $programId not written" }
            }
        }
        _statuses.value += programId to stored.status()
    }

    private fun file(programId: String): File {
        require(ID.matches(programId)) { "Unexpected program id" }
        return File(directory, "$programId$SUFFIX")
    }

    private fun StoredSignIn.status() = AutoSignInStatus(saved = true, blocked = stopped, readTexts = readTexts)

    companion object {
        const val DIRECTORY = "signins"
        private const val TAG = "MileStoneSession"
        private const val SUFFIX = ".sealed"
        private val ID = Regex("^[a-z0-9_]+$")

        /** Binds each file to its program, so one program's file cannot be swapped in for another's. */
        private fun aad(programId: String) = "milestone-signin-v1:$programId".encodeToByteArray()
    }
}

/**
 * AES-256-GCM with a key that never leaves the Android Keystore, in the StrongBox security chip when the phone has
 * one. The key cannot be exported, so a copy of the app's files is useless off this phone.
 */
class KeystoreSecretBox(private val alias: String = ALIAS) : SecretBox {
    override fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData)
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plaintext)
    }

    override fun open(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        if (ciphertext.isEmpty()) throw GeneralSecurityException("Empty")
        val ivLength = ciphertext[0].toInt()
        if (ivLength !in 12..16 || ciphertext.size <= 1 + ivLength) throw GeneralSecurityException("Malformed")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, ciphertext, 1, ivLength))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext, 1 + ivLength, ciphertext.size - 1 - ivLength)
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "milestone_signin_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}

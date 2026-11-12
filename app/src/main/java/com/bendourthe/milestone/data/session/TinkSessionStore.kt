package com.bendourthe.milestone.data.session

import android.content.Context
import com.bendourthe.milestone.DiagnosticLog
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

@Serializable
data class SessionRecord(val connectedAtEpochMs: Long, val lastVerifiedEpochMs: Long)

@Serializable
data class SessionRecords(val records: Map<String, SessionRecord> = emptyMap())

/** Encrypts the serialized records with [aead]; any decryption or parse failure is reported as corruption. */
class AeadJsonSerializer(private val aead: Aead, private val associatedData: ByteArray) : OkioSerializer<SessionRecords> {
    override val defaultValue = SessionRecords()

    override suspend fun readFrom(source: BufferedSource): SessionRecords {
        val ciphertext = source.readByteArray()
        if (ciphertext.isEmpty()) return defaultValue
        return try {
            Json.decodeFromString(SessionRecords.serializer(), aead.decrypt(ciphertext, associatedData).decodeToString())
        } catch (e: GeneralSecurityException) {
            throw CorruptionException("Session records could not be decrypted", e)
        } catch (e: SerializationException) {
            throw CorruptionException("Session records could not be parsed", e)
        }
    }

    override suspend fun writeTo(t: SessionRecords, sink: BufferedSink) {
        sink.write(aead.encrypt(Json.encodeToString(SessionRecords.serializer(), t).encodeToByteArray(), associatedData))
    }
}

/** Connection metadata per program. Never holds passwords, one-time codes, cookies, or page HTML. */
interface SessionStore {
    suspend fun get(programId: String): SessionRecord?
    suspend fun all(): Map<String, SessionRecord>
    suspend fun markConnected(programId: String, nowEpochMs: Long)
    suspend fun markVerified(programId: String, nowEpochMs: Long)
    suspend fun clear(programId: String)
    suspend fun clearAll()
}

class TinkSessionStore(
    file: File,
    aead: Aead,
    scope: CoroutineScope,
    private val onReset: suspend () -> Unit,
) : SessionStore {
    private val store: DataStore<SessionRecords> = DataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, AeadJsonSerializer(aead, ASSOCIATED_DATA)) { file.absoluteFile.toOkioPath() },
        corruptionHandler = ReplaceFileCorruptionHandler {
            DiagnosticLog.w(TAG, "Session store unreadable; resetting and asking for reconnect")
            scope.launch { onReset() }
            SessionRecords()
        },
        scope = scope,
    )

    override suspend fun get(programId: String): SessionRecord? = store.data.first().records[programId]

    override suspend fun all(): Map<String, SessionRecord> = store.data.first().records

    override suspend fun markConnected(programId: String, nowEpochMs: Long) {
        store.updateData { it.copy(records = it.records + (programId to SessionRecord(nowEpochMs, nowEpochMs))) }
    }

    override suspend fun markVerified(programId: String, nowEpochMs: Long) {
        store.updateData { current ->
            val record = current.records[programId] ?: return@updateData current
            current.copy(records = current.records + (programId to record.copy(lastVerifiedEpochMs = nowEpochMs)))
        }
    }

    override suspend fun clear(programId: String) {
        store.updateData { it.copy(records = it.records - programId) }
    }

    override suspend fun clearAll() {
        store.updateData { SessionRecords() }
    }

    companion object {
        const val FILE_NAME = "session_records.pb"
        private const val TAG = "MileStoneSession"
        private val ASSOCIATED_DATA = FILE_NAME.encodeToByteArray()
        private const val MASTER_KEY_URI = "android-keystore://milestone_session_master"
        private const val KEYSET_NAME = "milestone_session_keyset"
        private const val KEYSET_PREFS = "milestone_session_keyset_prefs"

        /** Builds the Keystore-backed AEAD; an invalidated Keystore key deletes the keyset and the store, then rebuilds. */
        fun keystoreAead(context: Context, storeFile: File, onKeyLost: () -> Unit): Aead {
            AeadConfig.register()
            fun build() = AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            return try {
                build()
            } catch (e: Exception) {
                if (e !is GeneralSecurityException && e !is IOException) throw e
                DiagnosticLog.w(TAG, "Session key unavailable; resetting session store")
                context.deleteSharedPreferences(KEYSET_PREFS)
                storeFile.delete()
                onKeyLost()
                build()
            }
        }
    }
}

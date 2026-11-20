package com.bendourthe.milestone.sync.extract

import java.io.File
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Turns an issuer page's card control id into the per-install binding hash stored in card coverage (ADR-0009):
 * HMAC-SHA256 under a random key that never leaves this phone. The same control on two sign-ins of one program
 * gives the same hash, so their card can be shared; the raw control id is never stored or logged.
 */
class BindingHasher(private val key: ByteArray) {
    init {
        require(key.size >= 32) { "binding key must be at least 32 bytes" }
    }

    fun hash(programId: String, control: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        val digest = mac.doFinal("$programId\u001F$control".encodeToByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val ALGORITHM = "HmacSHA256"
        const val FILE_NAME = "card-binding.key"

        /** Loads the key from [file] (in the app's no-backup folder), creating it on first use. */
        fun fromFile(file: File): BindingHasher {
            val existing = file.takeIf { it.isFile }?.readBytes()?.takeIf { it.size == 32 }
            if (existing != null) return BindingHasher(existing)
            val key = ByteArray(32).also(SecureRandom()::nextBytes)
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeBytes(key)
            check(temp.renameTo(file) || (file.delete() && temp.renameTo(file))) { "binding key not written" }
            return BindingHasher(key)
        }
    }
}

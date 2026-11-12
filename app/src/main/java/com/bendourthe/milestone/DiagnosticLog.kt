package com.bendourthe.milestone

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Sends each sync and sign-in diagnostic to Logcat and keeps the latest [MAX_BYTES] of them in a private, never
 * backed-up file, because the phone's log buffer can hold only a minute or two. Callers pass hosts, program IDs,
 * page shapes, and stop reasons only: never a value typed, read, or received.
 */
object DiagnosticLog {
    const val MAX_BYTES = 512 * 1024L
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "diagnostics").apply { isDaemon = true } }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var file: File? = null

    fun init(file: File) {
        this.file = file
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append("I", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        append("W", tag, message)
    }

    private fun append(level: String, tag: String, message: String) {
        val target = file ?: return
        val line = "${synchronized(stamp) { stamp.format(Date()) }} $level/$tag: $message\n"
        writer.execute {
            runCatching {
                target.appendText(line)
                if (target.length() > MAX_BYTES) {
                    val text = target.readText()
                    target.writeText(text.substring(text.indexOf('\n', text.length / 2) + 1))
                }
            }
        }
    }
}

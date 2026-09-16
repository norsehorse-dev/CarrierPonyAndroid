package com.carrierpony.app.sms

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Disk-backed so a multi-segment message survives the process being torn down
 * between broadcasts, and so a completed envelope survives until the app is next
 * live to decrypt it (we never decrypt in the background, matching the relay's
 * contentless-wake model). Segments live under cp-sms/parts/<id>/, completed
 * envelopes waiting for the app under cp-sms/pending/.
 */
class SmsReassembly(context: Context) {
    private val root = File(context.filesDir, "cp-sms").apply { mkdirs() }
    private val parts = File(root, "parts").apply { mkdirs() }
    private val pending = File(root, "pending").apply { mkdirs() }

    /** Store one frame body. Returns the full envelope when this was the last
     *  missing segment for its message, else null. */
    @Synchronized
    fun offer(id: Int, index: Int, total: Int, body: ByteArray): ByteArray? {
        val dir = File(parts, id.toString()).apply { mkdirs() }
        File(dir, index.toString()).writeBytes(body)
        val present = dir.listFiles()?.mapNotNull { it.name.toIntOrNull() }?.toSet() ?: emptySet()
        if (present.size < total) return null
        val out = ByteArrayOutputStream()
        for (i in 0 until total) {
            val f = File(dir, i.toString())
            if (!f.exists()) return null
            out.write(f.readBytes())
        }
        dir.deleteRecursively()
        return out.toByteArray()
    }

    /** Persist a completed envelope for the app to ingest when it next runs. */
    @Synchronized
    fun enqueue(envelope: ByteArray) {
        File(pending, "${System.nanoTime()}.env").writeBytes(envelope)
    }

    /** Return and remove all pending envelopes, oldest first. */
    @Synchronized
    fun drain(): List<ByteArray> {
        val files = pending.listFiles()?.sortedBy { it.name } ?: return emptyList()
        val out = files.map { it.readBytes() }
        files.forEach { it.delete() }
        return out
    }
}

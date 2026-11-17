// A public key handed to CarrierPony by another app: a key file opened from a
// file manager, or "share public key" from a PGP app. It is only ever loaded
// into Add by public key, where the usual key check runs and the person still
// has to tap Add contact. Nothing is added or sent on arrival.

package com.carrierpony.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.carrierpony.core.CPContactKey
import java.io.InputStream

object IncomingKey {

    /** True for the two ways a key reaches the app from outside. */
    fun isKeyIntent(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_SEND

    /** The bytes the intent carries, or an empty array when they can't be
     *  read. Only content: URIs are opened, so another app can't point this
     *  at a file path of its choosing. */
    fun read(context: Context, intent: Intent): ByteArray {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) {
            if (uri.scheme != "content") return ByteArray(0)
            return try {
                context.contentResolver.openInputStream(uri)?.use { readCapped(it) } ?: ByteArray(0)
            } catch (e: Exception) {
                ByteArray(0)
            }
        }
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            return capped(text.toByteArray(Charsets.UTF_8))
        }
        return ByteArray(0)
    }

    /** Reads one byte more than a key may be at most, so an oversized file is
     *  turned down by the key check without being loaded whole. */
    fun readCapped(stream: InputStream): ByteArray {
        val buffer = ByteArray(CPContactKey.MAX_INPUT_BYTES + 1)
        var total = 0
        while (total < buffer.size) {
            val read = stream.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return buffer.copyOf(total)
    }

    private fun capped(bytes: ByteArray): ByteArray =
        if (bytes.size > CPContactKey.MAX_INPUT_BYTES + 1) bytes.copyOf(CPContactKey.MAX_INPUT_BYTES + 1) else bytes
}

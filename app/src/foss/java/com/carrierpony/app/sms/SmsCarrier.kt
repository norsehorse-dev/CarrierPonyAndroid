package com.carrierpony.app.sms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager

/** The SMS send side (foss). Chunks a sealed envelope across port-addressed binary
 *  data SMS to a phone number. Receipt is handled by the manifest [SmsReceiver] so
 *  it works even when the app is not running. Best-effort: one default SIM, no
 *  per-segment retry, and a large payload becomes many slow segments. */
class SmsCarrier(private val context: Context) {

    /** Chunk and send. Returns true if every segment dispatched without throwing
     *  (dispatch, not delivery confirmation). */
    fun send(number: String, envelope: ByteArray): Boolean {
        return try {
            val mgr = smsManager()
            val total = (envelope.size + SmsWire.CHUNK - 1) / SmsWire.CHUNK
            if (total == 0 || total > 0xFFFF) return false
            val id = System.nanoTime().toInt() and 0x7FFFFFFF
            var index = 0
            var offset = 0
            while (offset < envelope.size) {
                val end = minOf(offset + SmsWire.CHUNK, envelope.size)
                val frame = SmsWire.header(id, index, total) + envelope.copyOfRange(offset, end)
                mgr.sendDataMessage(number, null, SmsWire.PORT.toShort(), frame, null, null)
                index++
                offset = end
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
}

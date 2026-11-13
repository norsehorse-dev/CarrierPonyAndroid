package com.carrierpony.app.sms

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.messaging.Transport
import com.carrierpony.app.messaging.TransportCapabilities
import com.carrierpony.app.messaging.TransportID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SMS as a Transport (foss flavor only). A direct, off-grid path: it chunks the
 * sealed envelope across binary data SMS to the peer's saved number and reassembles
 * on the far side. Carries no counter and ignores the sealed mailbox, like the
 * other direct transports. Reaches a peer only when SMS is on and that contact has
 * a number saved.
 */
class SmsTransport(
    private val carrier: SmsCarrier,
    private val numberFor: (Fingerprint) -> String?,
    private val enabled: () -> Boolean,
) : Transport {
    override val id = TransportID.SMS
    override val capabilities = TransportCapabilities(storeAndForward = false, revealsIP = false, worksOffline = true)

    override fun canReach(peer: Fingerprint): Boolean = enabled() && numberFor(peer) != null

    override suspend fun send(envelope: ByteArray, to: Fingerprint, mailbox: String?, expiresAt: Long, silent: Boolean): Boolean {
        if (!enabled()) return false
        // SMS is for short messages. A photo or video would be thousands of texts,
        // which trips the OS's bulk-SMS prompt over and over (GrapheneOS asks on
        // every batch) and takes forever. Anything over the cap returns false so the
        // relay carries it instead.
        if (envelope.size > MAX_ENVELOPE_BYTES) return false
        val number = numberFor(to) ?: return false
        // SmsManager calls are blocking binder calls; never make them on the main
        // thread (a long send there froze the UI into an ANR).
        return withContext(Dispatchers.IO) { carrier.send(number, envelope) }
    }

    companion object {
        /** About 19 data SMS at 110 bytes each: room for a sealed text message,
         *  well short of a burst big enough to set off the bulk-SMS prompt. */
        const val MAX_ENVELOPE_BYTES = 2048
    }
}

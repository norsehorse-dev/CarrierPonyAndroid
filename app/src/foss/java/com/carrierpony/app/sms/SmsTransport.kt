package com.carrierpony.app.sms

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.messaging.Transport
import com.carrierpony.app.messaging.TransportCapabilities
import com.carrierpony.app.messaging.TransportID

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
        val number = numberFor(to) ?: return false
        return carrier.send(number, envelope)
    }
}

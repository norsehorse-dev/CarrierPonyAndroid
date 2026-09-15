// Transport.kt
// CarrierPony Android
//
// What carries a sealed envelope from us to a peer. The relay is the default
// store-and-forward path; LAN-direct and future transports (SMS, Meshtastic,
// Nostr) are alternatives. The sealed envelope, crypto, and pair state stay
// ABOVE this seam in ChatStore; a Transport only decides how the opaque bytes
// reach the peer. See CarrierPony-2.1-Transport-LANDirect-Design.md.

package com.carrierpony.app.messaging

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.relay.MailboxCrypto
import com.carrierpony.app.relay.RelayClient

enum class TransportID { RELAY, LAN_DIRECT }

data class TransportCapabilities(
    val storeAndForward: Boolean,
    val revealsIP: Boolean,
    val worksOffline: Boolean,
)

interface Transport {
    val id: TransportID
    val capabilities: TransportCapabilities

    /** True if this transport can deliver to that peer right now (relay: always;
     *  LAN: only a peer currently discovered on the network). */
    fun canReach(peer: Fingerprint): Boolean

    /** Deliver one sealed envelope. Returns true if delivered, false to let the
     *  caller fall through to the next transport; throws on a hard error, which
     *  the relay (the last transport) surfaces to the user exactly as before. */
    suspend fun send(envelope: ByteArray, to: Fingerprint, expiresAt: Long, silent: Boolean): Boolean
}

/** The default transport: the relay's store-and-forward path. It owns the sealed
 *  mailbox-address computation (the relay's routing model) so ChatStore no longer
 *  has to; it wraps the existing RelayClient. */
class RelayTransport(
    private val relay: RelayClient,
    private val sealedKeys: SealedKeyStore?,
) : Transport {

    override val id = TransportID.RELAY
    override val capabilities = TransportCapabilities(storeAndForward = true, revealsIP = false, worksOffline = false)

    override fun canReach(peer: Fingerprint) = true

    override suspend fun send(envelope: ByteArray, to: Fingerprint, expiresAt: Long, silent: Boolean): Boolean {
        val mailbox = nextSealedAddress(to)
        if (mailbox != null) {
            relay.sealedSend(mailbox, envelope, expiresAt, silent)
        } else {
            relay.send(envelope, to, expiresAt, silent)
        }
        return true
    }

    /** The next sealed address for a peer, advancing the send counter. Returns
     *  null when the pair is not sealed-capable yet (no peer inbound key), so the
     *  caller falls back to the legacy fingerprint-routed send. */
    private fun nextSealedAddress(to: Fingerprint): String? {
        val sk = sealedKeys ?: return null
        val st = sk.pair(to.hex) ?: return null
        val peerKey = st.peerInboundKey ?: return null
        val address = MailboxCrypto.address(peerKey, st.sendCounter)
        sk.setPair(to.hex, SealedPairState(st.myInboundKey, st.peerInboundKey, st.sendCounter + 1, st.receiveHigh, st.sharedMine))
        return address
    }
}

/** LAN-direct as a Transport (M3b). Wraps LanDiscovery so ChatStore can treat
 *  direct LAN delivery like any other transport. Non-store-and-forward, reveals
 *  this device's IP to the peer, works with no internet. The relay stays the
 *  authority; this only accelerates. */
class LanDirectTransport(
    private val discovery: com.carrierpony.app.net.LanDiscovery,
) : Transport {
    override val id = TransportID.LAN_DIRECT
    override val capabilities = TransportCapabilities(storeAndForward = false, revealsIP = true, worksOffline = true)

    override fun canReach(peer: Fingerprint): Boolean = discovery.canReachLan(peer.hex)

    override suspend fun send(envelope: ByteArray, to: Fingerprint, expiresAt: Long, silent: Boolean): Boolean =
        discovery.deliver(to.hex, envelope)
}

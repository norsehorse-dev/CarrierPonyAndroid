// PairingSupport.kt
// CarrierPony Android
//
// The key-consistency gate shared by both relay pairing directions, ported
// from AppModel.consistentContact on iOS: a Contact is built only if the
// armored key actually hashes to the claimed fingerprint. Returns null on a
// malformed key or any mismatch — we never add a contact whose key doesn't
// match. Lives outside AppModel so it unit-tests without a Context.

package com.carrierpony.app.pairing

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.TrustLevel
import com.carrierpony.core.CPKeyInfo

object PairingSupport {

    fun consistentContact(fprHex: String, armoredPub: String, trust: TrustLevel): Contact? {
        val fingerprint = Fingerprint.from(fprHex) ?: return null
        val computed = CPKeyInfo.primaryFingerprint(armoredPub) ?: return null
        if (computed.uppercase() != fingerprint.hex) return null
        return Contact(
            fingerprint = fingerprint,
            publicKey = PublicKey(fingerprint, armoredPub),
            name = null,
            trust = trust
        )
    }
}

/** How long an offerer keeps a pairing offer it created. Expiry only ends the window
 *  for a NEW accept; an offer accepted while the offerer's app was closed must still
 *  be collectable afterwards, from the relay (which keeps accepted offers for 30
 *  days) or from the responder's pair-complete op. So an offer is dropped only once
 *  it is past expiry plus this grace period. Times are epoch seconds. */
object PairingRetention {
    const val GRACE_SECONDS: Long = 30L * 86_400
    /** Offers the relay gave no expiry for are kept this long after creation. */
    const val NO_EXPIRY_KEEP_SECONDS: Long = 31L * 86_400

    fun isStale(expiresAt: Long?, createdAt: Long, nowSec: Long): Boolean =
        if (expiresAt != null) nowSec > expiresAt + GRACE_SECONDS
        else nowSec > createdAt + NO_EXPIRY_KEEP_SECONDS
}

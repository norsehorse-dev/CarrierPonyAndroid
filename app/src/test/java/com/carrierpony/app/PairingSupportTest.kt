// PairingSupportTest.kt
// CarrierPony Android
//
// The pairing consistency gate: a contact is only built when the armored key
// hashes to the claimed fingerprint, against real generated keys.

package com.carrierpony.app

import com.carrierpony.app.messaging.TrustLevel
import com.carrierpony.app.pairing.PairingRetention
import com.carrierpony.app.pairing.PairingSupport
import com.carrierpony.core.CPIdentityGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSupportTest {

    @Test
    fun matchingKeyBuildsContact() {
        val identity = CPIdentityGenerator.generateV4Identity("Peer", "peer@carrierpony.com")
        val contact = PairingSupport.consistentContact(identity.fingerprint, identity.armoredPublicKey, TrustLevel.UNVERIFIED)
        assertNotNull(contact)
        assertEquals(identity.fingerprint, contact!!.fingerprint.hex)
        assertEquals(TrustLevel.UNVERIFIED, contact.trust)
    }

    @Test
    fun mismatchedFingerprintIsRefused() {
        val identity = CPIdentityGenerator.generateV4Identity("Peer", "peer@carrierpony.com")
        val other = CPIdentityGenerator.generateV4Identity("Impostor", "mallory@carrierpony.com")
        assertNull(PairingSupport.consistentContact(other.fingerprint, identity.armoredPublicKey, TrustLevel.VERIFIED))
    }

    @Test
    fun malformedInputIsRefused() {
        val identity = CPIdentityGenerator.generateV4Identity("Peer", "peer@carrierpony.com")
        assertNull(PairingSupport.consistentContact("not-a-fingerprint", identity.armoredPublicKey, TrustLevel.UNVERIFIED))
        assertNull(PairingSupport.consistentContact(identity.fingerprint, "not a key", TrustLevel.UNVERIFIED))
    }

    @Test
    fun expiredOfferIsKeptThroughTheGraceWindow() {
        val expires = 1_000_000L
        val created = expires - 86_400
        assertFalse(PairingRetention.isStale(expires, created, expires + 1))
        assertFalse(PairingRetention.isStale(expires, created, expires + PairingRetention.GRACE_SECONDS))
        assertTrue(PairingRetention.isStale(expires, created, expires + PairingRetention.GRACE_SECONDS + 1))
    }

    @Test
    fun offerWithoutExpiryIsKeptFromCreation() {
        val created = 2_000_000L
        assertFalse(PairingRetention.isStale(null, created, created + PairingRetention.NO_EXPIRY_KEEP_SECONDS))
        assertTrue(PairingRetention.isStale(null, created, created + PairingRetention.NO_EXPIRY_KEEP_SECONDS + 1))
    }
}

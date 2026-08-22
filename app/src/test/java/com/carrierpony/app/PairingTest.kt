// PairingTest.kt
// CarrierPony Android
//
// Invite parsing, pairing payload verification (real keys from the core), and
// the safety number derivation with a pinned cross-platform vector: the value
// below is computed from the same fingerprint pair the iOS test suite uses,
// so iOS and Android provably display identical safety numbers.

package com.carrierpony.app

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.pairing.Invite
import com.carrierpony.app.pairing.PairingPayload
import com.carrierpony.app.pairing.SafetyNumber
import com.carrierpony.core.CPIdentityGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {

    private val fprA = Fingerprint.from("08108C383A81C409F3E28757397AC5A52B6C5A8D")!!
    private val fprB = Fingerprint.from("05299C734EA466728C71BD7ACDD6B5F7C410B233")!!

    // ── Fingerprint type ───────────────────────────────────────────────

    @Test
    fun fingerprintRequires40Hex() {
        assertNotNull(Fingerprint.from("08108c383a81c409f3e28757397ac5a52b6c5a8d"))
        assertEquals("08108C383A81C409F3E28757397AC5A52B6C5A8D",
            Fingerprint.from("08108c383a81c409f3e28757397ac5a52b6c5a8d")!!.hex)
        assertNull(Fingerprint.from("08108C383A81C409F3E28757397AC5A52B6C5A8"))   // 39
        assertNull(Fingerprint.from("08108C383A81C409F3E28757397AC5A52B6C5A8DG"))  // non-hex
        assertNull(Fingerprint.from(""))
    }

    // ── Invite ─────────────────────────────────────────────────────────

    @Test
    fun inviteRoundTrip() {
        val token = "0123456789abcdef0123456789abcdef"
        val invite = Invite.create(token, fprA, "Sea Biscuit")
        val decoded = Invite.decode(invite.encoded())
        assertNotNull(decoded)
        assertEquals(token, decoded!!.t)
        assertEquals(fprA, decoded.fingerprint)
        assertEquals("Sea Biscuit", decoded.n)
    }

    @Test
    fun inviteWithoutNameRoundTrips() {
        val invite = Invite.create("0123456789abcdef0123456789abcdef", fprA, null)
        val decoded = Invite.decode(invite.encoded())
        assertNotNull(decoded)
        assertNull(decoded!!.n)
    }

    @Test
    fun inviteToleratesSurroundingWhitespace() {
        val invite = Invite.create("0123456789abcdef0123456789abcdef", fprA, "A")
        assertNotNull(Invite.decode("  " + invite.encoded() + "\n"))
    }

    @Test
    fun inviteRejectsBadInput() {
        assertNull(Invite.decode("not an invite"))
        assertNull(Invite.decode("CPPAIR1:%%%%"))
        // Bad token (31 hex)
        val badToken = Invite(1, "0123456789abcdef0123456789abcde", fprA.hex, null)
        assertNull(Invite.decode(badToken.encoded()))
        // Bad fingerprint (39 hex)
        val badFpr = Invite(1, "0123456789abcdef0123456789abcdef", fprA.hex.dropLast(1), null)
        assertNull(Invite.decode(badFpr.encoded()))
    }

    // ── PairingPayload ─────────────────────────────────────────────────

    @Test
    fun payloadVerifiesMatchingKey() {
        val identity = CPIdentityGenerator.generateV4Identity("Pair Pony", "pair@carrierpony.com")
        val fingerprint = Fingerprint.from(identity.fingerprint)!!
        val payload = PairingPayload.create(fingerprint, "Pair Pony", identity.armoredPublicKey)

        val decoded = PairingPayload.decode(payload.encoded())
        assertNotNull(decoded)
        val contact = decoded!!.verifiedContact()
        assertNotNull(contact)
        assertEquals(fingerprint, contact!!.fingerprint)
        assertEquals("Pair Pony", contact.name)
        assertEquals(com.carrierpony.app.messaging.TrustLevel.VERIFIED, contact.trust)
    }

    @Test
    fun payloadRejectsFingerprintMismatch() {
        val identity = CPIdentityGenerator.generateV4Identity("Pair Pony", "pair@carrierpony.com")
        // Claim a different (valid-shaped) fingerprint than the key hashes to.
        val payload = PairingPayload.create(fprA, "Mallory", identity.armoredPublicKey)
        val decoded = PairingPayload.decode(payload.encoded())
        assertNotNull(decoded)
        assertNull("A key that does not hash to its claimed fingerprint must be rejected",
            decoded!!.verifiedContact())
    }

    @Test
    fun payloadRejectsGarbage() {
        assertNull(PairingPayload.decode("!!!! not base64 !!!!"))
        assertNull(PairingPayload.decode(""))
    }

    // ── SafetyNumber ───────────────────────────────────────────────────

    @Test
    fun safetyNumberIsSymmetricAnd60Digits() {
        val ab = SafetyNumber.compute(fprA, fprB)
        val ba = SafetyNumber.compute(fprB, fprA)
        assertEquals(ab, ba)
        assertEquals(60, ab.length)
        assertTrue(ab.all { it.isDigit() })
    }

    @Test
    fun safetyNumberMatchesCrossPlatformVector() {
        // Same fingerprint pair as the iOS thread-ID harness test; iOS must
        // display exactly this number for these two identities.
        assertEquals(
            "254835486535974676843358408500344941892165948799090198347439",
            SafetyNumber.compute(fprA, fprB)
        )
    }

    @Test
    fun groupedFormatsTwelveGroupsOfFive() {
        val grouped = SafetyNumber.grouped(fprA, fprB)
        val groups = grouped.split(" ")
        assertEquals(12, groups.size)
        assertTrue(groups.all { it.length == 5 && it.all(Char::isDigit) })
        assertEquals("25483 54865 35974 67684 33584 08500 34494 18921 65948 79909 01983 47439", grouped)
    }
}

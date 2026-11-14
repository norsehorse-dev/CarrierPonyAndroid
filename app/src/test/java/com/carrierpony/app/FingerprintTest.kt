// FingerprintTest.kt
// CarrierPony Android
//
// Fingerprint accepts v4 (40 hex) and v6 (64 hex) and nothing else, and the
// pieces built on the fingerprint string (short display, safety number,
// invites) work at both lengths. The safety-number vectors are shared with iOS.

package com.carrierpony.app

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.pairing.Invite
import com.carrierpony.app.pairing.SafetyNumber
import com.carrierpony.app.ui.UiFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintTest {

    private val v4 = "08108C383A81C409F3E28757397AC5A52B6C5A8D"
    private val v6a = "0F1E2D3C4B5A69788796A5B4C3D2E1F00112233445566778899AABBCCDDEEFF0"
    private val v6b = "A1B2C3D4E5F60718293A4B5C6D7E8F90FFEEDDCCBBAA99887766554433221100"

    @Test
    fun acceptsV4AndV6() {
        val four = Fingerprint.from(v4)
        val six = Fingerprint.from(v6a)
        assertNotNull(four)
        assertNotNull(six)
        assertFalse(four!!.isV6)
        assertTrue(six!!.isV6)
        assertEquals(40, four.hex.length)
        assertEquals(64, six.hex.length)
    }

    @Test
    fun normalizesToUppercase() {
        assertEquals(v6a, Fingerprint.from(v6a.lowercase())!!.hex)
        assertEquals(Fingerprint.from(v6a), Fingerprint.from(v6a.lowercase()))
    }

    @Test
    fun rejectsOtherLengths() {
        for (n in listOf(0, 39, 41, 50, 63, 65, 80)) {
            assertNull("length $n", Fingerprint.from("A".repeat(n)))
        }
    }

    @Test
    fun rejectsNonHex() {
        assertNull(Fingerprint.from("G".repeat(40)))
        assertNull(Fingerprint.from("G".repeat(64)))
        assertNull(Fingerprint.from(v4.dropLast(1) + " "))
        // Arabic-Indic digits are digits to Char.isDigit but are not hex.
        assertNull(Fingerprint.from("\u0661".repeat(40)))
        assertNull(Fingerprint.from("\u0661".repeat(64)))
    }

    @Test
    fun shortFingerprintUsesLastEightAtBothLengths() {
        assertEquals("2B6C 5A8D", UiFormat.shortFingerprint(Fingerprint.from(v4)!!))
        assertEquals("CDDE EFF0", UiFormat.shortFingerprint(Fingerprint.from(v6a)!!))
    }

    @Test
    fun safetyNumberV6MatchesCrossPlatformVector() {
        val a = Fingerprint.from(v6a)!!
        val b = Fingerprint.from(v6b)!!
        assertEquals("784770140712922461606816496090514746184762508438433826276267", SafetyNumber.compute(a, b))
        assertEquals(SafetyNumber.compute(a, b), SafetyNumber.compute(b, a))
    }

    @Test
    fun safetyNumberMixedMatchesCrossPlatformVector() {
        val four = Fingerprint.from(v4)!!
        val six = Fingerprint.from(v6a)!!
        assertEquals("488344755630788373039536550042141250842433193540265516197226", SafetyNumber.compute(four, six))
        assertEquals(SafetyNumber.compute(four, six), SafetyNumber.compute(six, four))
    }

    @Test
    fun inviteRoundTripsAV6Fingerprint() {
        val token = "0123456789abcdef0123456789abcdef"
        val invite = Invite.create(token, Fingerprint.from(v6a)!!, "Sea Biscuit")
        val decoded = Invite.decode(invite.encoded())
        assertNotNull(decoded)
        assertEquals(v6a, decoded!!.fingerprint!!.hex)
        assertEquals(token, decoded.t)
    }
}

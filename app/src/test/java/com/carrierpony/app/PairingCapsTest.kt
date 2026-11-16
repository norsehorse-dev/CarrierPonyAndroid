// PairingCapsTest.kt
// CarrierPony Android
//
// The caps field on invites and offline key codes, and the one rule built on
// it: a v6 identity does not pair with an app that cannot hold a v6 contact.
//
// The literal invites below are fixed strings, written the way each kind of
// app writes them. The iOS suite decodes the same strings, so both platforms
// read each other's invites, old and new.

package com.carrierpony.app

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.pairing.Invite
import com.carrierpony.app.pairing.PairingCaps
import com.carrierpony.app.pairing.PairingPayload
import com.carrierpony.core.CPIdentityGenerator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.encoding.Base64

class PairingCapsTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val v4 = Fingerprint.from("08108C383A81C409F3E28757397AC5A52B6C5A8D")!!
    private val v4b = Fingerprint.from("05299C734EA466728C71BD7ACDD6B5F7C410B233")!!
    private val v6 = Fingerprint.from("0F1E2D3C4B5A69788796A5B4C3D2E1F00112233445566778899AABBCCDDEEFF0")!!
    private val v6b = Fingerprint.from("A1B2C3D4E5F60718293A4B5C6D7E8F90FFEEDDCCBBAA99887766554433221100")!!

    // An invite from an app before 4.0: no caps.
    private val oldV4Invite = "CPPAIR1:eyJ2IjoxLCJ0IjoiMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYiLCJmIjoiMDgxMDhDMzgzQTgxQzQwOUYzRTI4NzU3Mzk3QUM1QTUyQjZDNUE4RCIsIm4iOiJTZWEgQmlzY3VpdCJ9"
    // The same invite from 4.0.
    private val newV4Invite = "CPPAIR1:eyJ2IjoxLCJ0IjoiMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYiLCJmIjoiMDgxMDhDMzgzQTgxQzQwOUYzRTI4NzU3Mzk3QUM1QTUyQjZDNUE4RCIsIm4iOiJTZWEgQmlzY3VpdCIsImMiOlsidjYiXX0"
    // A v6 identity's invite, no name.
    private val newV6Invite = "CPPAIR1:eyJ2IjoxLCJ0IjoiMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYiLCJmIjoiMEYxRTJEM0M0QjVBNjk3ODg3OTZBNUI0QzNEMkUxRjAwMTEyMjMzNDQ1NTY2Nzc4ODk5QUFCQkNDRERFRUZGMCIsImMiOlsidjYiXX0"
    // From some later version: caps this build has never heard of, entries that
    // are not cap names at all, and a field this build does not know.
    private val futureInvite = "CPPAIR1:eyJ2IjoxLCJ0IjoiMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYiLCJmIjoiMDgxMDhDMzgzQTgxQzQwOUYzRTI4NzU3Mzk3QUM1QTUyQjZDNUE4RCIsImMiOlsidjYiLCJjYWxscyIsIk5vdCBBIENhcCIsNywieHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eCJdLCJ6Ijp7ImxhdGVyIjp0cnVlfX0"
    // "c" is a string where a list belongs.
    private val oddCapsInvite = "CPPAIR1:eyJ2IjoxLCJ0IjoiMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYiLCJmIjoiMDgxMDhDMzgzQTgxQzQwOUYzRTI4NzU3Mzk3QUM1QTUyQjZDNUE4RCIsImMiOiJ2NiJ9"

    private fun json(invite: String): JSONObject {
        val body = invite.removePrefix(Invite.PREFIX)
        val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        return JSONObject(String(decoder.decode(body), Charsets.UTF_8))
    }

    // ── Invites ────────────────────────────────────────────────────────

    @Test
    fun newInvitesSayV6() {
        for (fingerprint in listOf(v4, v6)) {
            val invite = Invite.create(token, fingerprint, "Sea Biscuit")
            assertEquals(listOf("v6"), invite.c)
            val decoded = Invite.decode(invite.encoded())
            assertNotNull(decoded)
            assertEquals(listOf("v6"), decoded!!.c)
            assertEquals(fingerprint, decoded.fingerprint)
            assertEquals(token, decoded.t)
            assertEquals("Sea Biscuit", decoded.n)
        }
    }

    @Test
    fun capsAreWrittenAsAListUnderC() {
        val written = json(Invite.create(token, v4, null).encoded())
        assertEquals(1, written.getJSONArray("c").length())
        assertEquals("v6", written.getJSONArray("c").getString(0))
        // Everything an older app reads is still where it looks for it.
        assertEquals(1, written.getInt("v"))
        assertEquals(token, written.getString("t"))
        assertEquals(v4.hex, written.getString("f"))
        assertFalse(written.has("n"))
    }

    @Test
    fun inviteFromBeforeFourHasNoCaps() {
        val invite = Invite.decode(oldV4Invite)
        assertNotNull(invite)
        assertNull(invite!!.c)
        assertEquals(v4, invite.fingerprint)
        assertEquals("Sea Biscuit", invite.n)
    }

    @Test
    fun fixedFourPointZeroInvitesDecode() {
        val fromV4 = Invite.decode(newV4Invite)!!
        assertEquals(listOf("v6"), fromV4.c)
        assertEquals(v4, fromV4.fingerprint)
        assertEquals("Sea Biscuit", fromV4.n)

        val fromV6 = Invite.decode(newV6Invite)!!
        assertEquals(listOf("v6"), fromV6.c)
        assertEquals(v6, fromV6.fingerprint)
        assertTrue(fromV6.fingerprint!!.isV6)
        assertNull(fromV6.n)
    }

    @Test
    fun unknownCapsAndFieldsDoNotBreakAnInvite() {
        val invite = Invite.decode(futureInvite)
        assertNotNull(invite)
        // The names this build does not know are kept; the entries that are not
        // cap names (a phrase, a number, an overlong string) are dropped.
        assertEquals(listOf("v6", "calls"), invite!!.c)
        assertEquals(v4, invite.fingerprint)
    }

    @Test
    fun capsOfTheWrongShapeCountAsNone() {
        val invite = Invite.decode(oddCapsInvite)
        assertNotNull(invite)
        assertNull(invite!!.c)
    }

    @Test
    fun inviteWithoutCapsIsWrittenWithoutTheField() {
        val written = json(Invite(1, token, v4.hex, null).encoded())
        assertFalse(written.has("c"))
        assertFalse(json(Invite(1, token, v4.hex, null, emptyList()).encoded()).has("c"))
    }

    // ── Offline key code ───────────────────────────────────────────────

    @Test
    fun payloadCarriesCapsAndStillVerifies() {
        for (identity in listOf(
            CPIdentityGenerator.generateV4Identity("Test Pony", "test@carrierpony.com"),
            CPIdentityGenerator.generateV6Identity("Test Pony", "test@carrierpony.com")
        )) {
            val fingerprint = Fingerprint.from(identity.fingerprint)!!
            val payload = PairingPayload.create(fingerprint, null, identity.armoredPublicKey)
            assertEquals(listOf("v6"), payload.caps)
            val decoded = PairingPayload.decode(payload.encoded())
            assertNotNull(decoded)
            assertEquals(listOf("v6"), decoded!!.caps)
            assertEquals(fingerprint, decoded.verifiedContact()?.fingerprint)
        }
    }

    @Test
    fun payloadFromBeforeFourHasNoCaps() {
        val identity = CPIdentityGenerator.generateV4Identity("Test Pony", "test@carrierpony.com")
        val fingerprint = Fingerprint.from(identity.fingerprint)!!
        val old = PairingPayload(1, fingerprint.hex, null, identity.armoredPublicKey)
        val text = old.encoded()
        val decoder = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
        assertFalse(JSONObject(String(decoder.decode(text), Charsets.UTF_8)).has("caps"))
        val decoded = PairingPayload.decode(text)
        assertNotNull(decoded)
        assertNull(decoded!!.caps)
        assertNotNull(decoded.verifiedContact())
    }

    // ── The rule ───────────────────────────────────────────────────────

    @Test
    fun aV4IdentityPairsWithAnyone() {
        assertTrue(PairingCaps.canPair(v4, v4b, null))
        assertTrue(PairingCaps.canPair(v4, v4b, listOf("v6")))
        assertTrue(PairingCaps.canPair(v4, v6, listOf("v6")))
        assertTrue(PairingCaps.canPair(v4, v6, null))
    }

    @Test
    fun aV6IdentityNeedsAPeerThatCanHoldIt() {
        // A v4 peer on 4.0 says so.
        assertTrue(PairingCaps.canPair(v6, v4, listOf("v6")))
        assertTrue(PairingCaps.canPair(v6, v4, listOf("calls", "v6")))
        // A v4 peer that says nothing, or names only other things, is before 4.0.
        assertFalse(PairingCaps.canPair(v6, v4, null))
        assertFalse(PairingCaps.canPair(v6, v4, emptyList()))
        assertFalse(PairingCaps.canPair(v6, v4, listOf("calls")))
        assertFalse(PairingCaps.canPair(v6, v4, listOf("V6")))
        // A v6 peer needs no cap: only 4.0 or later makes one.
        assertTrue(PairingCaps.canPair(v6, v6b, null))
        assertTrue(PairingCaps.canPair(v6, v6b, listOf("v6")))
    }

    @Test
    fun theRuleAppliedToTheFixedInvites() {
        val old = Invite.decode(oldV4Invite)!!
        val new = Invite.decode(newV4Invite)!!
        val fromV6 = Invite.decode(newV6Invite)!!
        // A v6 identity opening each of them.
        assertFalse(PairingCaps.canPair(v6b, old.fingerprint!!, old.c))
        assertTrue(PairingCaps.canPair(v6b, new.fingerprint!!, new.c))
        assertTrue(PairingCaps.canPair(v6b, fromV6.fingerprint!!, fromV6.c))
        // A v4 identity opening each of them.
        assertTrue(PairingCaps.canPair(v4b, old.fingerprint!!, old.c))
        assertTrue(PairingCaps.canPair(v4b, new.fingerprint!!, new.c))
        assertTrue(PairingCaps.canPair(v4b, fromV6.fingerprint!!, fromV6.c))
    }
}

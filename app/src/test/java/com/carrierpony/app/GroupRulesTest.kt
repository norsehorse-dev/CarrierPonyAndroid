// GroupRulesTest.kt
// CarrierPony Android
//
// A group whose roster includes a v6 identity needs every member on 4.0,
// because an older app drops a member it cannot parse and tells nobody. These
// tests pin who gets named, and that a roster with no v6 identity is never
// touched.

package com.carrierpony.app

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.messaging.GroupRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRulesTest {

    private val v4a = Fingerprint.from("08108C383A81C409F3E28757397AC5A52B6C5A8D")!!
    private val v4b = Fingerprint.from("05299C734EA466728C71BD7ACDD6B5F7C410B233")!!
    private val v4c = Fingerprint.from("61D27B04E1E90899E8D162824891888765D64C51")!!
    private val v6a = Fingerprint.from("0F1E2D3C4B5A69788796A5B4C3D2E1F00112233445566778899AABBCCDDEEFF0")!!
    private val v6b = Fingerprint.from("A1B2C3D4E5F60718293A4B5C6D7E8F90FFEEDDCCBBAA99887766554433221100")!!

    private fun member(fingerprint: Fingerprint, label: String, vararg caps: String) =
        GroupRules.Member(fingerprint, caps.toList().ifEmpty { null }, label)

    private fun blocked(vararg roster: GroupRules.Member) = GroupRules.needingUpdate(roster.toList()).map { it.label }

    @Test
    fun aGroupWithNoV6IdentityIsNeverRestricted() {
        // Nobody has said what they support, exactly as in every group made before 4.0.
        assertTrue(blocked(member(v4a, "me"), member(v4b, "Bob"), member(v4c, "Carol")).isEmpty())
        assertTrue(blocked(member(v4a, "me", "v6"), member(v4b, "Bob")).isEmpty())
        assertTrue(blocked().isEmpty())
    }

    @Test
    fun aV6MemberNeedsEveryoneOnFour() {
        assertEquals(listOf("Bob"), blocked(member(v4a, "me", "v6"), member(v4b, "Bob"), member(v6a, "Six")))
        assertEquals(
            listOf("Bob", "Carol"),
            blocked(member(v4a, "me", "v6"), member(v4b, "Bob"), member(v4c, "Carol"), member(v6a, "Six"))
        )
        // Naming some other capability is not enough.
        assertEquals(listOf("Bob"), blocked(member(v4a, "me", "v6"), member(v4b, "Bob", "calls"), member(v6a, "Six")))
    }

    @Test
    fun itIsFineOnceEveryoneHasSaidSo() {
        assertTrue(blocked(member(v4a, "me", "v6"), member(v4b, "Bob", "v6"), member(v6a, "Six")).isEmpty())
        assertTrue(blocked(member(v4a, "me", "v6"), member(v4b, "Bob", "calls", "v6"), member(v6a, "Six")).isEmpty())
    }

    @Test
    fun v6MembersNeedNoCap() {
        assertTrue(blocked(member(v6a, "me"), member(v6b, "Other Six")).isEmpty())
        assertTrue(blocked(member(v6a, "me", "v6"), member(v6b, "Other Six"), member(v4b, "Bob", "v6")).isEmpty())
    }

    @Test
    fun theCreatorBeingV6IsEnoughToApplyTheRule() {
        assertEquals(listOf("Bob"), blocked(member(v6a, "me", "v6"), member(v4b, "Bob")))
    }
}

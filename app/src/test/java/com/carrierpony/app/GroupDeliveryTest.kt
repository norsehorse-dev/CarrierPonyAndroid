// GroupDeliveryTest.kt
// CarrierPony Android
//
// Which way a group message travels to a member. A member's group address
// only exists on the relay after they have received the group key, and the
// relay silently drops anything sent to an address it does not know. So a
// member gets group messages by the per-contact path until a group message
// has arrived from them in the current epoch, and by the group address after.

package com.carrierpony.app

import com.carrierpony.app.messaging.GroupDelivery
import com.carrierpony.app.messaging.SealedGroupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupDeliveryTest {

    private val bob = "05299C734EA466728C71BD7ACDD6B5F7C410B233"
    private val six = "0F1E2D3C4B5A69788796A5B4C3D2E1F00112233445566778899AABBCCDDEEFF0"
    private val day = 86_400L
    private val t0 = 1_800_000_000L

    private fun fresh() = SealedGroupState(0, mutableMapOf(), mutableMapOf())

    @Test
    fun aMemberNotHeardFromGoesByThePeerPath() {
        assertTrue(GroupDelivery.usePeerPath(null, bob, t0))
        assertTrue(GroupDelivery.usePeerPath(fresh(), bob, t0))
        val state = fresh().apply { since[bob] = t0 }
        assertTrue(GroupDelivery.usePeerPath(state, bob, t0 + 1))
        assertTrue(GroupDelivery.usePeerPath(state, bob, t0 + 29 * day))
    }

    @Test
    fun onceHeardTheyGoByTheGroupAddress() {
        val state = fresh().apply { since[bob] = t0; heard.add(bob) }
        assertFalse(GroupDelivery.usePeerPath(state, bob, t0 + 1))
        // Hearing from one member says nothing about another.
        assertTrue(GroupDelivery.usePeerPath(state, six, t0 + 1))
    }

    @Test
    fun aMemberWhoNeverPostsMovesOverAfterThirtyDays() {
        val state = fresh().apply { since[bob] = t0 }
        assertTrue(GroupDelivery.usePeerPath(state, bob, t0 + 30 * day - 1))
        assertFalse(GroupDelivery.usePeerPath(state, bob, t0 + 30 * day))
        assertFalse(GroupDelivery.usePeerPath(state, bob, t0 + 90 * day))
    }

    @Test
    fun stateRoundTripsWithTheNewFields() {
        val state = SealedGroupState(3, mutableMapOf(bob to 7L), mutableMapOf(six to 2L)).apply {
            heard.add(six)
            since[bob] = t0
        }
        val restored = SealedGroupState.fromJson(state.toJson())
        assertNotNull(restored)
        assertEquals(3, restored!!.epoch)
        assertEquals(mapOf(bob to 7L), restored.sendCounters)
        assertEquals(mapOf(six to 2L), restored.recvHighs)
        assertEquals(setOf(six), restored.heard)
        assertEquals(mapOf(bob to t0), restored.since)
    }

    // State saved by a build before this one has neither field. A member whose
    // messages have been arriving on their group address was plainly listening,
    // so an established group carries on as it was. Anyone else starts on the
    // per-contact path.
    @Test
    fun stateFromAnOlderBuildKeepsEstablishedMembersOnTheGroupAddress() {
        val old = """{"epoch":2,"send":{"$bob":5,"$six":1},"recv":{"$bob":9}}"""
        val state = SealedGroupState.fromJson(old)
        assertNotNull(state)
        assertEquals(setOf(bob), state!!.heard)
        assertTrue(state.since.isEmpty())
        assertEquals(5L, state.sendCounters[bob])
        assertFalse(GroupDelivery.usePeerPath(state, bob, t0))
        assertTrue(GroupDelivery.usePeerPath(state, six, t0))
    }
}

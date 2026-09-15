// GroupCryptoTest.kt
// CarrierPony Android
//
// Real-crypto round-trip for the group message layer, the Android twin of iOS
// CarrierPonyTests/GroupCryptoTests.swift. Two freshly generated v4 identities
// (Alice, Bob) exercise GroupCrypto.seal/open over the real PonyCryptoEngine +
// CarrierPonyCore, so this proves the AES-GCM seal/open plus the new
// CPMessenger.verifyDetached sender-auth actually round-trip, and that a forged
// sender and a wrong epoch key are rejected. This is the one verification
// available without two devices + a live relay.

package com.carrierpony.app

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PonyCryptoEngine
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.messaging.ChatGroup
import com.carrierpony.app.messaging.GroupCrypto
import com.carrierpony.app.messaging.GroupCryptoException
import com.carrierpony.app.messaging.GroupMember
import com.carrierpony.core.CPGeneratedIdentity
import com.carrierpony.core.CPIdentityGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class GroupCryptoTest {

    private val alice = CPIdentityGenerator.generateV4Identity("Alice", "alice@carrierpony.com")
    private val bob = CPIdentityGenerator.generateV4Identity("Bob", "bob@carrierpony.com")

    private fun fpr(i: CPGeneratedIdentity) = Fingerprint.from(i.fingerprint)!!

    private fun engine(own: CPGeneratedIdentity, knows: List<CPGeneratedIdentity>) = PonyCryptoEngine(
        fingerprint = fpr(own),
        secretKey = own.secretKey,
        armored = own.armoredPublicKey,
        publicKeyResolver = { f ->
            (listOf(own) + knows).firstOrNull { it.fingerprint == f.hex }
                ?.let { PublicKey(Fingerprint.from(it.fingerprint)!!, it.armoredPublicKey) }
        }
    )

    private fun member(i: CPGeneratedIdentity, admin: Boolean) =
        GroupMember(fpr(i), i.armoredPublicKey, null, admin)

    private fun randomKey(): SecretKey {
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        return SecretKeySpec(b, "AES")
    }

    private val group = ChatGroup(
        groupID = "00112233445566778899aabbccddeeff",
        name = "Test Group",
        members = listOf(member(alice, admin = true), member(bob, admin = false)),
        epoch = 0
    )

    @Test
    fun sealOpenRoundTrips() {
        val aliceEngine = engine(alice, listOf(bob))
        val bobEngine = engine(bob, listOf(alice))
        val key = randomKey()
        val container = "hello group, this is Alice".toByteArray(Charsets.UTF_8)

        val payload = GroupCrypto.seal(container, group.groupID, fpr(alice), 0, key, aliceEngine)
        val (sender, opened) = GroupCrypto.open(payload, group, { if (it == 0) key else null }, bobEngine)

        assertEquals(fpr(alice), sender)
        assertArrayEquals(container, opened)
    }

    @Test
    fun wrongEpochKeyIsRejected() {
        val aliceEngine = engine(alice, listOf(bob))
        val bobEngine = engine(bob, listOf(alice))
        val payload = GroupCrypto.seal("secret".toByteArray(Charsets.UTF_8), group.groupID, fpr(alice), 0, randomKey(), aliceEngine)

        assertThrows(GroupCryptoException::class.java) {
            GroupCrypto.open(payload, group, { randomKey() }, bobEngine)
        }
    }

    @Test
    fun forgedSenderIsRejected() {
        // Alice signs the container, but the box claims Bob is the sender.
        val aliceEngine = engine(alice, listOf(bob))
        val bobEngine = engine(bob, listOf(alice))
        val key = randomKey()
        val payload = GroupCrypto.seal("forged".toByteArray(Charsets.UTF_8), group.groupID, fpr(bob), 0, key, aliceEngine)

        assertThrows(GroupCryptoException.BadSignature::class.java) {
            GroupCrypto.open(payload, group, { key }, bobEngine)
        }
    }
}

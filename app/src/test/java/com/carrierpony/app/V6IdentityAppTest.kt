// V6IdentityAppTest.kt
// CarrierPony Android
//
// A v6 identity through the app's own layers, the ones above the core: the
// crypto engine the stores use, group messages, the stored identity record,
// the encrypted backup, and thread IDs. From 4.0 this is what a new account
// is, so each of these has to work for it and alongside a v4 identity.

package com.carrierpony.app

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PonyCryptoEngine
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.envelope.Threading
import com.carrierpony.app.identity.Identity
import com.carrierpony.app.identity.IdentityBackup
import com.carrierpony.app.identity.IdentityRecord
import com.carrierpony.app.messaging.ChatGroup
import com.carrierpony.app.messaging.GroupCrypto
import com.carrierpony.app.messaging.GroupCryptoException
import com.carrierpony.app.messaging.GroupMember
import com.carrierpony.core.CPGeneratedIdentity
import com.carrierpony.core.CPIdentityGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class V6IdentityAppTest {

    private val six = CPIdentityGenerator.generateV6Identity("Six", "six@carrierpony.com")
    private val otherSix = CPIdentityGenerator.generateV6Identity("Other Six", "other@carrierpony.com")
    private val four = CPIdentityGenerator.generateV4Identity("Four", "four@carrierpony.com")

    private fun fpr(i: CPGeneratedIdentity) = Fingerprint.from(i.fingerprint)!!
    private fun key(i: CPGeneratedIdentity) = PublicKey(fpr(i), i.armoredPublicKey)

    private fun engine(own: CPGeneratedIdentity, knows: List<CPGeneratedIdentity>) = PonyCryptoEngine(
        fingerprint = fpr(own),
        secretKey = own.secretKey,
        armored = own.armoredPublicKey,
        publicKeyResolver = { f -> (listOf(own) + knows).firstOrNull { it.fingerprint == f.hex }?.let { key(it) } }
    )

    private fun identity(i: CPGeneratedIdentity) = Identity(
        fingerprint = fpr(i),
        secretKey = i.secretKey,
        armoredPublicKey = i.armoredPublicKey
    )

    @Test
    fun theFingerprintTypeTakesIt() {
        assertTrue(fpr(six).isV6)
        assertEquals(64, fpr(six).hex.length)
        assertEquals(40, fpr(four).hex.length)
    }

    // ── Messages through the engine the stores use ─────────────────────

    @Test
    fun messagesFlowBetweenEveryPairing() {
        for ((sender, recipient) in listOf(six to otherSix, six to four, four to six)) {
            val plaintext = "from ${sender.fingerprint.length} to ${recipient.fingerprint.length}".toByteArray()
            val envelope = engine(sender, listOf(recipient)).signAndEncrypt(plaintext, key(recipient))
            val opened = engine(recipient, listOf(sender)).decryptAndVerify(envelope)
            assertEquals(fpr(sender), opened.sender)
            assertArrayEquals(plaintext, opened.plaintext)
        }
    }

    @Test
    fun aMessageFromAnUnknownV6SenderIsRefused() {
        val envelope = engine(six, listOf(otherSix)).signAndEncrypt("hello".toByteArray(), key(otherSix))
        assertThrows(Exception::class.java) { engine(otherSix, emptyList()).decryptAndVerify(envelope) }
        // decryptOnly is for control messages that carry their own proof; it still opens.
        assertArrayEquals("hello".toByteArray(), engine(otherSix, emptyList()).decryptOnly(envelope))
    }

    @Test
    fun relayChallengeSignatureVerifies() {
        val challenge = "5b1e0c7a9f3d48e2a6c1b7d09e4f2a8c".toByteArray()
        val signature = engine(six, emptyList()).detachedSignature(challenge)
        assertTrue(engine(four, listOf(six)).verifyDetached(signature, challenge, key(six)))
        assertTrue(!engine(four, listOf(six)).verifyDetached(signature, challenge + 1.toByte(), key(six)))
        assertTrue(!engine(four, listOf(six)).verifyDetached(signature, challenge, key(otherSix)))
    }

    // ── Groups ─────────────────────────────────────────────────────────

    private fun randomKey(): SecretKey = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")

    private fun group(vararg members: CPGeneratedIdentity) = ChatGroup(
        groupID = "00112233445566778899aabbccddeeff",
        name = "Mixed Group",
        members = members.mapIndexed { index, m -> GroupMember(fpr(m), m.armoredPublicKey, null, index == 0) },
        epoch = 0
    )

    @Test
    fun groupMessagesVerifyInAMixedGroup() {
        val mixed = group(six, four, otherSix)
        val groupKey = randomKey()
        for (sender in listOf(six, four, otherSix)) {
            val container = "group message from ${sender.fingerprint.take(8)}".toByteArray()
            val payload = GroupCrypto.seal(container, mixed.groupID, fpr(sender), 0, groupKey, engine(sender, emptyList()))
            for (reader in listOf(six, four, otherSix)) {
                val (from, opened) = GroupCrypto.open(payload, mixed, { if (it == 0) groupKey else null }, engine(reader, emptyList()))
                assertEquals(fpr(sender), from)
                assertArrayEquals(container, opened)
            }
        }
    }

    @Test
    fun aV6MemberCannotBeImpersonatedInAGroup() {
        val mixed = group(six, four)
        val groupKey = randomKey()
        // Four signs, but the box claims Six sent it.
        val payload = GroupCrypto.seal("forged".toByteArray(), mixed.groupID, fpr(six), 0, groupKey, engine(four, emptyList()))
        assertThrows(GroupCryptoException.BadSignature::class.java) {
            GroupCrypto.open(payload, mixed, { groupKey }, engine(six, emptyList()))
        }
    }

    // ── What gets stored and backed up ─────────────────────────────────

    @Test
    fun storedRecordRoundTrips() {
        val original = identity(six)
        val restored = IdentityRecord.decode(IdentityRecord.encode(original))
        assertNotNull(restored)
        assertEquals(original.fingerprint, restored!!.fingerprint)
        assertArrayEquals(original.secretKey, restored.secretKey)
        assertEquals(original.armoredPublicKey, restored.armoredPublicKey)
        assertEquals(false, restored.protected)
    }

    @Test
    fun backupRoundTripsAndTheRestoredIdentityWorks() {
        val blob = IdentityBackup.export(identity(six), "correct horse battery staple")
        val restored = IdentityBackup.restore(blob, "correct horse battery staple")
        assertEquals(fpr(six), restored.fingerprint)
        assertArrayEquals(six.secretKey, restored.secretKey)

        // The restored bytes are a working identity: it opens what was sent to it and signs.
        val restoredEngine = PonyCryptoEngine(
            fingerprint = restored.fingerprint,
            secretKey = restored.secretKey,
            armored = restored.armoredPublicKey,
            publicKeyResolver = { f -> listOf(six, four).firstOrNull { it.fingerprint == f.hex }?.let { key(it) } }
        )
        val inbound = engine(four, listOf(six)).signAndEncrypt("to the restored one".toByteArray(), key(six))
        assertArrayEquals("to the restored one".toByteArray(), restoredEngine.decryptAndVerify(inbound).plaintext)
        val outbound = restoredEngine.signAndEncrypt("from the restored one".toByteArray(), key(four))
        assertEquals(fpr(six), engine(four, listOf(six)).decryptAndVerify(outbound).sender)
    }

    // ── Thread IDs ─────────────────────────────────────────────────────

    @Test
    fun pairwiseThreadIdsWorkAtEitherLength() {
        val a = fpr(six).hex
        val b = fpr(four).hex
        val c = fpr(otherSix).hex
        assertEquals(Threading.pairwise(a, b), Threading.pairwise(b, a))
        assertEquals(Threading.pairwise(a, c), Threading.pairwise(c.lowercase(), a.lowercase()))
        assertEquals(64, Threading.pairwise(a, b).length)
        assertNotEquals(Threading.pairwise(a, b), Threading.pairwise(a, c))
    }
}

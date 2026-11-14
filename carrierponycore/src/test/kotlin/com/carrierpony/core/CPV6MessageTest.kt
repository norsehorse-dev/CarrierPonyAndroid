// CPV6MessageTest.kt
// CarrierPonyCore
//
// Messages to and from v6 identities. A v6 identity is reached through its
// ML-KEM-768 + X25519 subkey (algorithm 35) and signs with its primary key, so
// the sender a receiver sees is the identity's own fingerprint. Covers v6 to v6,
// both mixed directions, the wire shape, the failure cases, and the RFC sample
// key and message, which this core did not produce.

package com.carrierpony.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom

class CPV6MessageTest {

    private fun v6(name: String) = CPIdentityGenerator.generateV6Identity(name, "test@carrierpony.com")
    private fun v4(name: String) = CPIdentityGenerator.generateV4Identity(name, "test@carrierpony.com")

    private fun publicBytes(identity: CPGeneratedIdentity): ByteArray = CPArmor.dearmor(identity.armoredPublicKey)!!

    private fun resolver(vararg known: CPGeneratedIdentity): (String) -> ByteArray? = { fingerprint ->
        known.firstOrNull { it.fingerprint == fingerprint }?.let { publicBytes(it) }
    }

    private fun send(from: CPGeneratedIdentity, to: CPGeneratedIdentity, plaintext: ByteArray): ByteArray =
        CPMessenger.signAndEncrypt(plaintext, from.secretKey, null, publicBytes(to))

    private fun roundTrip(from: CPGeneratedIdentity, to: CPGeneratedIdentity) {
        val plaintext = "hello from ${from.fingerprint.take(8)}".toByteArray(Charsets.UTF_8)
        val envelope = send(from, to, plaintext)
        val opened = CPMessenger.decryptAndVerify(envelope, to.secretKey, null, resolver(from))
        assertArrayEquals(plaintext, opened.plaintext)
        assertEquals("the sender must be the identity's own fingerprint", from.fingerprint, opened.senderFingerprint)
    }

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/pqc/$name")!!.use { it.readBytes() }

    @Test
    fun v6ToV6RoundTrips() = roundTrip(v6("A"), v6("B"))

    @Test
    fun v4ToV6RoundTrips() = roundTrip(v4("A"), v6("B"))

    @Test
    fun v6ToV4RoundTrips() = roundTrip(v6("A"), v4("B"))

    @Test
    fun v6SenderIsA64HexFingerprint() {
        val a = v6("A")
        val b = v6("B")
        val opened = CPMessenger.decryptAndVerify(send(a, b, byteArrayOf(1, 2, 3)), b.secretKey, null, resolver(a))
        assertEquals(64, opened.senderFingerprint.length)
    }

    @Test
    fun largePayloadRoundTrips() {
        val a = v6("A")
        val b = v6("B")
        val plaintext = ByteArray(300_000).also { SecureRandom().nextBytes(it) }
        val opened = CPMessenger.decryptAndVerify(send(a, b, plaintext), b.secretKey, null, resolver(a))
        assertArrayEquals(plaintext, opened.plaintext)
    }

    @Test
    fun envelopeToV6StartsWithAnAlgorithm35SessionKeyPacket() {
        val a = v6("A")
        val b = v6("B")
        val envelope = send(a, b, "x".toByteArray())
        val body = CPCompositeKeys.packetBody(envelope)
        assertEquals("PKESK tag", 1, if (envelope[0].toInt() and 0x40 != 0) envelope[0].toInt() and 0x3F else (envelope[0].toInt() shr 2) and 0x0F)
        assertEquals("PKESK version", 6, body[0].toInt())
        assertEquals("fingerprint field length", 33, body[1].toInt())
        assertEquals("recipient key version", 6, body[2].toInt())
        assertEquals("algorithm", 35, body[35].toInt())
        // version + count + key version + fingerprint + algorithm + ephemeral + ciphertext + length + wrapped
        assertEquals(1 + 1 + 1 + 32 + 1 + 32 + 1088 + 1 + 40, body.size)
        val parsed = CPCompositeMessage.parsePkesk(body)
        assertNotNull(parsed)
        val subkey = CPCompositeKeys.encryptionSubkey(CPMessenger.loadPublicRing(publicBytes(b)))!!
        assertArrayEquals(subkey.fingerprint, parsed!!.recipientFingerprint)
    }

    @Test
    fun wrongV6RecipientCannotDecrypt() {
        val a = v6("A")
        val b = v6("B")
        val c = v6("C")
        try {
            CPMessenger.decryptAndVerify(send(a, b, "secret".toByteArray()), c.secretKey, null, resolver(a))
            fail("a v6 identity opened a message addressed to another")
        } catch (e: CPCryptoError.DecryptionFailed) {
            // expected
        }
    }

    @Test
    fun v4RecipientCannotOpenAMessageToV6() {
        val a = v6("A")
        val b = v6("B")
        val c = v4("C")
        try {
            CPMessenger.decryptAndVerify(send(a, b, "secret".toByteArray()), c.secretKey, null, resolver(a))
            fail("a v4 identity opened a message addressed to a v6 one")
        } catch (e: CPCryptoError) {
            // expected
        }
    }

    @Test
    fun unknownV6SenderIsRefused() {
        val a = v6("A")
        val b = v6("B")
        try {
            CPMessenger.decryptAndVerify(send(a, b, "hi".toByteArray()), b.secretKey, null, resolver())
            fail("a message from an unknown sender was accepted")
        } catch (e: CPCryptoError.NoValidSignature) {
            // expected
        }
    }

    @Test
    fun aSenderCannotClaimAnotherIdentity() {
        val a = v6("A")
        val b = v6("B")
        val impostor = v6("Impostor")
        // The resolver hands back the impostor's key under the real sender's fingerprint.
        val lying: (String) -> ByteArray? = { publicBytes(impostor) }
        try {
            CPMessenger.decryptAndVerify(send(a, b, "hi".toByteArray()), b.secretKey, null, lying)
            fail("a signature verified against the wrong key")
        } catch (e: CPCryptoError.NoValidSignature) {
            // expected
        }
    }

    @Test
    fun tamperedEnvelopeIsRefused() {
        val a = v6("A")
        val b = v6("B")
        val envelope = send(a, b, ByteArray(2000) { 7 })
        // Flip a bit in the encrypted body, past the session-key packet.
        val tampered = envelope.copyOf().also { it[it.size - 40] = (it[it.size - 40].toInt() xor 1).toByte() }
        try {
            CPMessenger.decryptAndVerify(tampered, b.secretKey, null, resolver(a))
            fail("a tampered envelope was accepted")
        } catch (e: CPCryptoError) {
            // expected: the AEAD tag fails
        }
    }

    @Test
    fun tamperedSessionKeyPacketIsRefused() {
        val a = v6("A")
        val b = v6("B")
        val envelope = send(a, b, "hi".toByteArray())
        // Flip a bit inside the ML-KEM ciphertext.
        val tampered = envelope.copyOf().also { it[200] = (it[200].toInt() xor 1).toByte() }
        try {
            CPMessenger.decryptAndVerify(tampered, b.secretKey, null, resolver(a))
            fail("a tampered session-key packet was accepted")
        } catch (e: CPCryptoError.DecryptionFailed) {
            // expected: the unwrap integrity check fails
        }
    }

    @Test
    fun decryptOnlyOpensAV6Envelope() {
        val a = v6("A")
        val b = v6("B")
        val plaintext = "control op".toByteArray()
        assertArrayEquals(plaintext, CPMessenger.decryptOnly(send(a, b, plaintext), b.secretKey))
    }

    @Test
    fun twoEnvelopesOfTheSamePlaintextDiffer() {
        val a = v6("A")
        val b = v6("B")
        assertFalse(send(a, b, "same".toByteArray()).contentEquals(send(a, b, "same".toByteArray())))
    }

    // The sample v6 Ed25519 + ML-KEM-768 + X25519 key and message from the
    // OpenPGP post-quantum specification. Neither was made by this core, so
    // this is the check that the KEM, the key parsing and the session-key
    // packet all match the standard and not merely each other.
    @Test
    fun specificationSampleMessageDecrypts() {
        val key = resource("rfc9580-pqc-sample-key.asc")
        val message = resource("rfc9580-pqc-sample-message.asc")
        assertEquals(6, CPKeyInfo.keyVersion(key))
        val plaintext = CPMessenger.decryptOnly(message, key)
        assertEquals("Testing", String(plaintext, Charsets.UTF_8).trim())
    }

    @Test
    fun weCanEncryptToTheSpecificationSampleKey() {
        val a = v6("A")
        val key = resource("rfc9580-pqc-sample-key.asc")
        val publicKey = CPArmor.dearmor(CPIdentityGenerator.exportArmoredPublicKey(key))!!
        val plaintext = "to the sample key".toByteArray()
        val envelope = CPMessenger.signAndEncrypt(plaintext, a.secretKey, null, publicKey)
        assertArrayEquals(plaintext, CPMessenger.decryptOnly(envelope, key))
        assertTrue(envelope.size > 1088)
    }
}

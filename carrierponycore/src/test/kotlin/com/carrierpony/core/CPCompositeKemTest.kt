// CPCompositeKemTest.kt
// CarrierPonyCore
//
// The ML-KEM-768 + X25519 KEM (algorithm 35): both sides derive the same KEK,
// the wrong key does not, tampering is caught, the session-key wrap round-trips,
// and the combiner's byte layout matches a known-answer vector computed
// independently of this code (Python hashlib.sha3_256 over the RFC 9980 input
// order). A full vector from a third implementation, shared with the iOS core,
// covers seed expansion, decapsulation and the wrap.

package com.carrierpony.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom

class CPCompositeKemTest {

    private val random = SecureRandom()

    private fun range(from: Int): ByteArray = ByteArray(32) { (from + it).toByte() }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun keyPairHasTheWireLengths() {
        val kp = CPCompositeKem.generateKeyPair(random)
        assertEquals(1216, kp.publicKey.size)
        assertEquals(96, kp.secretKey.size)
        assertEquals(CPCompositeKem.COMPOSITE_PUB_LEN, kp.publicKey.size)
        assertEquals(CPCompositeKem.COMPOSITE_SECRET_LEN, kp.secretKey.size)
    }

    @Test
    fun seedExpandsToTheSamePublicKey() {
        val kp = CPCompositeKem.generateKeyPair(random)
        val (_, mlkemPublic) = CPCompositeKem.splitPublic(kp.publicKey)
        val (_, seed) = CPCompositeKem.splitSecret(kp.secretKey)
        assertArrayEquals(mlkemPublic, CPCompositeKem.mlkemPublicFromSeed(seed))
    }

    @Test
    fun encapsulateAndDecapsulateDeriveTheSameKek() {
        val kp = CPCompositeKem.generateKeyPair(random)
        val (xPublic, _) = CPCompositeKem.splitPublic(kp.publicKey)

        val enc = CPCompositeKem.encapsulate(kp.publicKey, random)
        val kek = CPCompositeKem.decapsulate(enc.ephemeralPublic, enc.mlkemCiphertext, kp.secretKey, xPublic)

        assertArrayEquals(enc.kek, kek)
        assertEquals(32, kek.size)
        assertEquals(32, enc.ephemeralPublic.size)
        assertEquals(1088, enc.mlkemCiphertext.size)
    }

    @Test
    fun eachEncapsulationIsFresh() {
        val kp = CPCompositeKem.generateKeyPair(random)
        val a = CPCompositeKem.encapsulate(kp.publicKey, random)
        val b = CPCompositeKem.encapsulate(kp.publicKey, random)
        assertFalse(a.kek.contentEquals(b.kek))
        assertFalse(a.ephemeralPublic.contentEquals(b.ephemeralPublic))
        assertFalse(a.mlkemCiphertext.contentEquals(b.mlkemCiphertext))
    }

    @Test
    fun wrongKeyDerivesADifferentKek() {
        val kp = CPCompositeKem.generateKeyPair(random)
        val other = CPCompositeKem.generateKeyPair(random)
        val (xPublic, _) = CPCompositeKem.splitPublic(kp.publicKey)

        val enc = CPCompositeKem.encapsulate(kp.publicKey, random)
        val wrong = CPCompositeKem.decapsulate(enc.ephemeralPublic, enc.mlkemCiphertext, other.secretKey, xPublic)
        assertFalse(enc.kek.contentEquals(wrong))
    }

    // Each half alone must change the KEK, or the hybrid is not a hybrid.
    @Test
    fun eitherHalfAloneChangesTheKek() {
        val kp = CPCompositeKem.generateKeyPair(random)
        val other = CPCompositeKem.generateKeyPair(random)
        val (xPublic, _) = CPCompositeKem.splitPublic(kp.publicKey)
        val (xSecret, seed) = CPCompositeKem.splitSecret(kp.secretKey)
        val (otherXSecret, otherSeed) = CPCompositeKem.splitSecret(other.secretKey)
        val enc = CPCompositeKem.encapsulate(kp.publicKey, random)

        val wrongX = CPCompositeKem.decapsulate(enc.ephemeralPublic, enc.mlkemCiphertext, otherXSecret + seed, xPublic)
        val wrongM = CPCompositeKem.decapsulate(enc.ephemeralPublic, enc.mlkemCiphertext, xSecret + otherSeed, xPublic)
        assertFalse("X25519 half must matter", enc.kek.contentEquals(wrongX))
        assertFalse("ML-KEM half must matter", enc.kek.contentEquals(wrongM))
    }

    @Test
    fun tamperedCiphertextDerivesADifferentKek() {
        val kp = CPCompositeKem.generateKeyPair(random)
        val (xPublic, _) = CPCompositeKem.splitPublic(kp.publicKey)
        val enc = CPCompositeKem.encapsulate(kp.publicKey, random)

        val ct = enc.mlkemCiphertext.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        val eph = enc.ephemeralPublic.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertFalse(enc.kek.contentEquals(CPCompositeKem.decapsulate(enc.ephemeralPublic, ct, kp.secretKey, xPublic)))
        assertFalse(enc.kek.contentEquals(CPCompositeKem.decapsulate(eph, enc.mlkemCiphertext, kp.secretKey, xPublic)))
    }

    @Test
    fun lowOrderEphemeralIsRefused() {
        val kp = CPCompositeKem.generateKeyPair(random)
        val (xPublic, _) = CPCompositeKem.splitPublic(kp.publicKey)
        val enc = CPCompositeKem.encapsulate(kp.publicKey, random)
        try {
            CPCompositeKem.decapsulate(ByteArray(32), enc.mlkemCiphertext, kp.secretKey, xPublic)
            fail("an all-zero X25519 share must be refused")
        } catch (e: CPCryptoError.DecryptionFailed) {
            // expected
        }
    }

    @Test
    fun wrongLengthsAreRefused() {
        val kp = CPCompositeKem.generateKeyPair(random)
        val (xPublic, _) = CPCompositeKem.splitPublic(kp.publicKey)
        val enc = CPCompositeKem.encapsulate(kp.publicKey, random)
        val bad = listOf<() -> Any>(
            { CPCompositeKem.encapsulate(kp.publicKey.copyOf(1215), random) },
            { CPCompositeKem.splitSecret(kp.secretKey.copyOf(95)) },
            { CPCompositeKem.decapsulate(enc.ephemeralPublic.copyOf(31), enc.mlkemCiphertext, kp.secretKey, xPublic) },
            { CPCompositeKem.decapsulate(enc.ephemeralPublic, enc.mlkemCiphertext.copyOf(1087), kp.secretKey, xPublic) },
            { CPCompositeKem.decapsulate(enc.ephemeralPublic, enc.mlkemCiphertext, kp.secretKey, xPublic.copyOf(31)) }
        )
        for ((i, call) in bad.withIndex()) {
            try {
                call()
                fail("case $i accepted a wrong length")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun sessionKeyWrapRoundTrips() {
        val kek = ByteArray(32).also { random.nextBytes(it) }
        val sessionKey = ByteArray(32).also { random.nextBytes(it) }
        val wrapped = CPCompositeKem.wrapSessionKey(kek, sessionKey)
        assertEquals(40, wrapped.size)
        assertArrayEquals(sessionKey, CPCompositeKem.unwrapSessionKey(kek, wrapped))
    }

    @Test
    fun unwrapWithTheWrongKekFails() {
        val kek = ByteArray(32).also { random.nextBytes(it) }
        val wrapped = CPCompositeKem.wrapSessionKey(kek, ByteArray(32).also { random.nextBytes(it) })
        val wrongKek = kek.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        try {
            CPCompositeKem.unwrapSessionKey(wrongKek, wrapped)
            fail("unwrap with the wrong KEK must fail the integrity check")
        } catch (e: Exception) {
            // expected (InvalidCipherTextException)
        }
    }

    @Test
    fun combinerMatchesTheKnownAnswer() {
        // mlkemShare = 00..1f, ecdhShare = 20..3f, ephemeral = 40..5f, recipient = 60..7f
        val kek = CPCompositeKem.combine(range(0), range(32), range(64), range(96))
        assertEquals("8b341efe0f87f2e97e1ab8772b8e7cebc6b52240a631ad4d5e220acd0f419b75", hex(kek))
    }

    private fun unhex(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val vector: Map<String, String> by lazy {
        val json = javaClass.getResourceAsStream("/pqc/kem-vector-1.json")!!.use { it.readBytes() }.toString(Charsets.US_ASCII)
        Regex("\"(\\w+)\"\\s*:\\s*\"([0-9a-f]+)\"").findAll(json).associate { it.groupValues[1] to it.groupValues[2] }
    }

    // A message key encapsulated by a third implementation (Python: kyber-py,
    // cryptography, hashlib; the generator sits beside the vector) opens here.
    // The iOS core replays the same vector in CompositeKEMTests.
    @Test
    fun opensTheSharedVector() {
        val secret = unhex(vector.getValue("compositeSecret"))
        val public = unhex(vector.getValue("compositePublic"))
        val (xPublic, mlkemPublic) = CPCompositeKem.splitPublic(public)
        val (_, seed) = CPCompositeKem.splitSecret(secret)
        assertArrayEquals(mlkemPublic, CPCompositeKem.mlkemPublicFromSeed(seed))
        val kek = CPCompositeKem.decapsulate(
            unhex(vector.getValue("ephemeralPublic")),
            unhex(vector.getValue("mlkemCiphertext")),
            secret,
            xPublic
        )
        assertEquals(vector.getValue("kek"), hex(kek))
        val sessionKey = CPCompositeKem.unwrapSessionKey(kek, unhex(vector.getValue("wrappedSessionKey")))
        assertEquals(vector.getValue("sessionKey"), hex(sessionKey))
        assertEquals(vector.getValue("wrappedSessionKey"), hex(CPCompositeKem.wrapSessionKey(kek, sessionKey)))
    }

    @Test
    fun encapsulatesToTheSharedVectorKey() {
        val secret = unhex(vector.getValue("compositeSecret"))
        val public = unhex(vector.getValue("compositePublic"))
        val sent = CPCompositeKem.encapsulate(public, random)
        val received = CPCompositeKem.decapsulate(
            sent.ephemeralPublic, sent.mlkemCiphertext, secret, CPCompositeKem.splitPublic(public).first
        )
        assertArrayEquals(sent.kek, received)
    }
}

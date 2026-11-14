// CPSignatureRepairTest.kt
// CarrierPonyCore
//
// An older iOS build writes an EdDSA signature's R and S with the right bit
// count but all 32 octets, so about one signature in 128 (those where R or S
// starts with a zero octet) cannot be read as written. These tests make such
// signatures on purpose, by taking a correct one and re-encoding it the way
// that build does, and check that they are refused as written, accepted after
// repair, and that correct data is never touched.
//
// When the iOS project's MessageStressTests have been run, the files under
// /ios hold real messages from that encoder, and the last tests use them.

package com.carrierpony.core

import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CPSignatureRepairTest {

    private fun v4() = CPIdentityGenerator.generateV4Identity("Test Pony", "test@carrierpony.com")

    private fun resourceOrNull(path: String): ByteArray? = javaClass.getResourceAsStream(path)?.use { it.readBytes() }

    private fun uint16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun bitLength(value: ByteArray): Int {
        for ((index, b) in value.withIndex()) {
            val v = b.toInt() and 0xFF
            if (v != 0) return (value.size - index) * 8 - (Integer.numberOfLeadingZeros(v) - 24)
        }
        return 0
    }

    private fun packet(tag: Int, body: ByteArray): ByteArray = byteArrayOf(
        (0xC0 or tag).toByte(), 0xFF.toByte(),
        (body.size ushr 24).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()
    ) + body

    /**
     * Re-encode a correct v4 EdDSA signature body the way the old iOS encoder
     * does: the right bit count, then all 32 octets. Null when R and S are both
     * full width, since those come out the same either way.
     */
    private fun mislabel(body: ByteArray): ByteArray? {
        if (body[0].toInt() != 4 || body[2].toInt() != 22) return null
        var i = 4
        i += 2 + uint16(body, i)
        i += 2 + uint16(body, i)
        i += 2
        val rLength = (uint16(body, i) + 7) / 8
        val r = body.copyOfRange(i + 2, i + 2 + rLength)
        val sStart = i + 2 + rLength
        val sLength = (uint16(body, sStart) + 7) / 8
        val s = body.copyOfRange(sStart + 2, sStart + 2 + sLength)
        if (r.size == 32 && s.size == 32) return null
        val r32 = ByteArray(32 - r.size) + r
        val s32 = ByteArray(32 - s.size) + s
        val rBits = bitLength(r32)
        val sBits = bitLength(s32)
        return body.copyOfRange(0, i) +
            byteArrayOf((rBits ushr 8).toByte(), rBits.toByte()) + r32 +
            byteArrayOf((sBits ushr 8).toByte(), sBits.toByte()) + s32
    }

    private class Mislabelled(val bytes: ByteArray, val bindingChanged: Boolean, val anyChanged: Boolean)

    /** Every signature packet in [ring] that can be mislabelled, mislabelled. */
    private fun mislabelRing(ring: ByteArray): Mislabelled {
        val out = ByteArrayOutputStream()
        var i = 0
        var previousTag = -1
        var bindingChanged = false
        var anyChanged = false
        while (i < ring.size) {
            val header = CPSignatureRepair.header(ring, i)!!
            val end = header.bodyStart + header.bodyLength
            val changed = if (header.tag == 2) mislabel(ring.copyOfRange(header.bodyStart, end)) else null
            if (changed != null) {
                out.write(packet(2, changed))
                anyChanged = true
                if (previousTag == 14) bindingChanged = true
            } else {
                out.write(ring, i, end - i)
            }
            previousTag = header.tag
            i = end
        }
        return Mislabelled(out.toByteArray(), bindingChanged, anyChanged)
    }

    /** A v4 identity whose subkey binding signature the old encoder would mislabel, in that form. */
    private fun identityWithMislabelledBinding(): Pair<CPGeneratedIdentity, ByteArray> {
        repeat(20_000) {
            val identity = v4()
            val mislabelled = mislabelRing(CPArmor.dearmor(identity.armoredPublicKey)!!)
            if (mislabelled.bindingChanged) return identity to mislabelled.bytes
        }
        throw AssertionError("no binding signature with a leading zero octet in 20,000 keys")
    }

    @Test
    fun correctKeysAreReturnedUntouched() {
        val v4 = CPArmor.dearmor(v4().armoredPublicKey)!!
        val v6 = CPArmor.dearmor(CPIdentityGenerator.generateV6Identity("A", "a@carrierpony.com").armoredPublicKey)!!
        assertSame(v4, CPSignatureRepair.normalize(v4))
        assertSame(v6, CPSignatureRepair.normalize(v6))
        val garbage = ByteArray(200) { 7 }
        assertSame(garbage, CPSignatureRepair.normalize(garbage))
        val empty = ByteArray(0)
        assertSame(empty, CPSignatureRepair.normalize(empty))
    }

    @Test
    fun aMislabelledBindingIsRefusedAsWrittenAndAcceptedAfterRepair() {
        val (identity, mislabelled) = identityWithMislabelledBinding()
        val original = CPArmor.dearmor(identity.armoredPublicKey)!!

        // As written, BouncyCastle cannot verify the binding.
        val asWritten = try {
            val ring = PGPPublicKeyRing(ByteArrayInputStream(mislabelled), BcKeyFingerprintCalculator())
            CPMessenger.findEncryptionKey(ring) != null
        } catch (e: Exception) {
            false
        }
        assertFalse("the mislabelled binding must not verify as written", asWritten)

        // Through the core's loader it is repaired and the subkey is usable.
        val ring = CPMessenger.loadPublicRing(mislabelled)
        assertEquals(identity.fingerprint, CPKeyInfo.bytesToHex(ring.publicKey.fingerprint))
        assertNotNull(CPMessenger.findEncryptionKey(ring))

        // Repair gives back exactly the key BouncyCastle would have written.
        val repaired = CPSignatureRepair.normalize(mislabelled)
        assertArrayEquals(
            CPMessenger.loadPublicRing(original).encoded,
            PGPPublicKeyRing(ByteArrayInputStream(repaired), BcKeyFingerprintCalculator()).encoded
        )
    }

    @Test
    fun aMessageToAKeyWithAMislabelledBindingRoundTrips() {
        val (recipient, mislabelled) = identityWithMislabelledBinding()
        val sender = v4()
        val plaintext = "to a key the old encoder mislabelled".toByteArray()
        val envelope = CPMessenger.signAndEncrypt(plaintext, sender.secretKey, null, mislabelled)
        val opened = CPMessenger.decryptAndVerify(envelope, recipient.secretKey, null) {
            if (it == sender.fingerprint) CPArmor.dearmor(sender.armoredPublicKey) else null
        }
        assertArrayEquals(plaintext, opened.plaintext)
    }

    @Test
    fun repairDoesNotRescueASignatureThatIsActuallyWrong() {
        val (_, mislabelled) = identityWithMislabelledBinding()
        // Flip one bit in the last octet of the ring, which is inside S of the binding.
        val tampered = mislabelled.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val ring = try {
            CPMessenger.loadPublicRing(tampered)
        } catch (e: CPCryptoError) {
            null
        }
        if (ring != null) assertEquals(null, CPMessenger.findEncryptionKey(ring))
    }

    @Test
    fun aSignatureAreaOfAnotherSizeIsLeftAlone() {
        val ring = CPArmor.dearmor(v4().armoredPublicKey)!!
        // Find the first signature packet and append a stray octet to its body.
        var i = 0
        while (true) {
            val header = CPSignatureRepair.header(ring, i)!!
            val end = header.bodyStart + header.bodyLength
            if (header.tag == 2) {
                val body = ring.copyOfRange(header.bodyStart, end) + byteArrayOf(0)
                assertEquals(null, CPSignatureRepair.repairBody(body))
                assertEquals(null, CPSignatureRepair.repairBody(ring.copyOfRange(header.bodyStart, end)))
                return
            }
            i = end
        }
    }

    // ── Real messages from the old iOS encoder ─────────────────────────

    @Test
    fun realIosMessagesWithMislabelledSignaturesVerify() {
        val envelopes = resourceOrNull("/ios/ios-quirk-envelopes.b64")
        val recipientSecret = resourceOrNull("/ios/ios-quirk-recipient-secret.asc")
        val senderPublic = resourceOrNull("/ios/ios-quirk-sender-public.asc")
        val senderFingerprint = resourceOrNull("/ios/ios-quirk-sender-fingerprint.txt")
        assumeTrue(
            "Run the iOS MessageStressTests and copy /tmp/carrierpony-fixtures/ios-quirk-* into src/test/resources/ios to activate",
            envelopes != null && recipientSecret != null && senderPublic != null && senderFingerprint != null
        )
        val fingerprint = String(senderFingerprint!!, Charsets.UTF_8).trim()
        val senderKey = CPArmor.dearmor(String(senderPublic!!, Charsets.UTF_8))!!
        val lines = String(envelopes!!, Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }
        assumeTrue("the iOS run rejected no messages, so there is nothing to check", lines.isNotEmpty())
        var verified = 0
        for (line in lines) {
            val envelope = java.util.Base64.getDecoder().decode(line)
            val opened = CPMessenger.decryptAndVerify(envelope, recipientSecret!!, null) {
                if (it == fingerprint) senderKey else null
            }
            assertEquals(fingerprint, opened.senderFingerprint)
            assertTrue(String(opened.plaintext, Charsets.UTF_8).startsWith("stress message "))
            verified++
        }
        println("[repair] real iOS envelopes the iOS parser rejected, verified here: $verified of ${lines.size}")
        assertEquals(lines.size, verified)
    }
}

// CPCompositeMessage.kt
// CarrierPonyCore
//
// The message side of the ML-KEM-768 + X25519 encryption subkey (algorithm 35,
// RFC 9980): writing the session-key packet when encrypting to a v6 identity,
// and recovering the session key when decrypting as one.
//
// Encrypting plugs into BouncyCastle: PkeskGenerator is a key-encryption method
// that BC's encrypted-data generator calls with the session key, and it returns
// a v6 PKESK packet whose algorithm fields are
//
//   X25519 ephemeral (32) || ML-KEM ciphertext (1088) || length (1) ||
//   RFC 3394 wrapped session key
//
// Decrypting cannot go through BC's reader, because BC 1.85 throws on a PKESK
// with an algorithm it does not know. So the leading session-key packets are
// split off by hand, the session key is recovered with CPCompositeKem, and the
// encrypted-data packet that follows is handed to BC with that session key. BC
// still does the SEIPDv2 (AEAD) body and its integrity check.
//
// Ported from the approach proven in PGPony Android, cut down to v6 PKESKs and
// the one parameter set CarrierPony uses.

package com.carrierpony.core

import org.bouncycastle.bcpg.BCPGInputStream
import org.bouncycastle.bcpg.ContainedPacket
import org.bouncycastle.bcpg.PublicKeyEncSessionPacket
import org.bouncycastle.bcpg.PublicKeyPacket
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPEncryptedData
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSessionKey
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.PGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.PGPKeyEncryptionMethodGenerator
import org.bouncycastle.openpgp.operator.bc.BcSessionKeyDataDecryptorFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.SecureRandom

object CPCompositeMessage {

    private const val TAG_PKESK = 1
    private const val TAG_SKESK = 3
    private const val PKESK_VERSION = 6

    /** Wraps the session key for an algorithm-35 recipient subkey. */
    class PkeskGenerator(
        private val recipientSubkey: PGPPublicKey,
        private val random: SecureRandom = SecureRandom()
    ) : PGPKeyEncryptionMethodGenerator {

        override fun generate(
            dataEncryptorBuilder: PGPDataEncryptorBuilder,
            sessionKey: ByteArray
        ): ContainedPacket {
            val material = CPCompositeKeys.publicMaterial(recipientSubkey)
                ?: throw PGPException("Recipient subkey is not a v6 ML-KEM-768 + X25519 key")
            val enc = CPCompositeKem.encapsulate(material, random)
            val wrapped = CPCompositeKem.wrapSessionKey(enc.kek, sessionKey)
            if (wrapped.size !in 1..255) throw PGPException("Wrapped session key length out of range")

            val fields = ByteArrayOutputStream().apply {
                write(enc.ephemeralPublic)
                write(enc.mlkemCiphertext)
                write(wrapped.size)
                write(wrapped)
            }.toByteArray()

            return PublicKeyEncSessionPacket.createV6PKESKPacket(
                PublicKeyPacket.VERSION_6,
                recipientSubkey.fingerprint,
                CPCompositeKem.ALGORITHM_ID,
                arrayOf(fields)
            )
        }
    }

    /** An opened envelope: the plaintext packet stream and BC's object for the integrity gate. */
    class Opened(val stream: InputStream, val integrity: PGPEncryptedData)

    /**
     * Open [message] with the algorithm-35 subkey in [ring].
     *
     * Returns null when the message has no algorithm-35 session-key packet, so
     * the caller takes the ordinary BouncyCastle path. When it has one, this
     * either returns the opened envelope or throws
     * [CPCryptoError.DecryptionFailed]: a message addressed to a composite key
     * is never retried another way.
     */
    fun open(message: ByteArray, ring: PGPSecretKeyRing): Opened? {
        val binary = PGPUtil.getDecoderStream(ByteArrayInputStream(message)).readBytes()
        val split = split(binary) ?: return null

        val secretSubkey = CPCompositeKeys.encryptionSubkey(ring)
            ?: throw CPCryptoError.DecryptionFailed("No matching decryption key found")
        val publicMaterial = CPCompositeKeys.publicMaterial(secretSubkey.publicKey)
            ?: throw CPCryptoError.DecryptionFailed("No matching decryption key found")
        val secretMaterial = CPCompositeKeys.secretMaterial(secretSubkey)
        val (x25519Public, _) = CPCompositeKem.splitPublic(publicMaterial)
        val ourFingerprint = secretSubkey.publicKey.fingerprint

        var sessionKey: ByteArray? = null
        for (pkesk in split.pkesks) {
            // An empty fingerprint is an anonymous recipient: try our key.
            if (pkesk.recipientFingerprint.isNotEmpty() &&
                !pkesk.recipientFingerprint.contentEquals(ourFingerprint)
            ) continue
            sessionKey = try {
                val kek = CPCompositeKem.decapsulate(
                    pkesk.ephemeralPublic, pkesk.mlkemCiphertext, secretMaterial, x25519Public
                )
                CPCompositeKem.unwrapSessionKey(kek, pkesk.wrappedSessionKey)
            } catch (e: Exception) {
                null
            }
            if (sessionKey != null) break
        }
        if (sessionKey == null) throw CPCryptoError.DecryptionFailed("No matching decryption key found")

        // A v6 PKESK does not name the cipher; the key length does.
        val algorithm = when (sessionKey.size) {
            16 -> SymmetricKeyAlgorithmTags.AES_128
            24 -> SymmetricKeyAlgorithmTags.AES_192
            32 -> SymmetricKeyAlgorithmTags.AES_256
            else -> throw CPCryptoError.DecryptionFailed("Unsupported session key length")
        }

        try {
            val list = PGPEncryptedDataList(BCPGInputStream(ByteArrayInputStream(split.remainder)))
            val sessionEncrypted = list.extractSessionKeyEncryptedData()
            val stream = sessionEncrypted.getDataStream(
                BcSessionKeyDataDecryptorFactory(PGPSessionKey(algorithm, sessionKey))
            )
            return Opened(stream, sessionEncrypted)
        } catch (e: CPCryptoError) {
            throw e
        } catch (e: Exception) {
            throw CPCryptoError.DecryptionFailed(e.message ?: "Could not open the encrypted data")
        }
    }

    // ── Session-key packets ────────────────────────────────────────────

    internal class Pkesk(
        /** The recipient subkey's fingerprint, or empty for an anonymous recipient. */
        val recipientFingerprint: ByteArray,
        val ephemeralPublic: ByteArray,
        val mlkemCiphertext: ByteArray,
        val wrappedSessionKey: ByteArray
    )

    /** Parse a PKESK packet body. Null unless it is a well-formed v6 algorithm-35 PKESK. */
    internal fun parsePkesk(body: ByteArray): Pkesk? {
        var i = 0
        fun take(n: Int): ByteArray? {
            if (n < 0 || body.size - i < n) return null
            return body.copyOfRange(i, i + n).also { i += n }
        }
        fun octet(): Int? = take(1)?.let { it[0].toInt() and 0xFF }

        if (octet() != PKESK_VERSION) return null
        val count = octet() ?: return null
        val fingerprint = if (count == 0) {
            ByteArray(0)
        } else {
            octet() ?: return null
            take(count - 1) ?: return null
        }
        if (octet() != CPCompositeKem.ALGORITHM_ID) return null
        val ephemeral = take(CPCompositeKem.X25519_KEY_LEN) ?: return null
        val ciphertext = take(CPCompositeKem.MLKEM768_CT_LEN) ?: return null
        val wrappedLength = octet() ?: return null
        val wrapped = take(wrappedLength) ?: return null
        if (i != body.size) return null
        return Pkesk(fingerprint, ephemeral, ciphertext, wrapped)
    }

    private class Split(val pkesks: List<Pkesk>, val remainder: ByteArray)

    /**
     * Walk the leading session-key packets (PKESK tag 1, SKESK tag 3) and
     * return every algorithm-35 PKESK plus the bytes from the first other
     * packet (the encrypted data) onward. Null if there is no algorithm-35
     * PKESK. Session-key packets always have definite lengths; the encrypted
     * data may use partial lengths, so it is passed on with its framing intact
     * and never measured here.
     */
    private fun split(data: ByteArray): Split? {
        val pkesks = mutableListOf<Pkesk>()
        var i = 0
        while (i < data.size) {
            val first = data[i].toInt() and 0xFF
            if (first and 0x80 == 0) return null
            val tag = if (first and 0x40 != 0) first and 0x3F else (first shr 2) and 0x0F
            if (tag != TAG_PKESK && tag != TAG_SKESK) {
                return if (pkesks.isEmpty()) null else Split(pkesks, data.copyOfRange(i, data.size))
            }
            val header = header(data, i) ?: return null
            val end = header.bodyStart + header.bodyLength
            if (header.bodyLength < 0 || end > data.size || end <= i) return null
            if (tag == TAG_PKESK) {
                parsePkesk(data.copyOfRange(header.bodyStart, end))?.let { pkesks.add(it) }
            }
            i = end
        }
        return null
    }

    private class Header(val bodyStart: Int, val bodyLength: Int)

    /** A definite-length packet header at [start], old or new format. Null otherwise. */
    private fun header(data: ByteArray, start: Int): Header? {
        var i = start
        fun octet(): Int? = if (i < data.size) data[i++].toInt() and 0xFF else null
        fun uint32(): Int? {
            if (data.size - i < 4) return null
            val v = ((data[i].toInt() and 0xFF) shl 24) or ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            i += 4
            return v
        }
        val c = octet() ?: return null
        val length: Int = if (c and 0x40 != 0) {
            val l0 = octet() ?: return null
            when {
                l0 < 192 -> l0
                l0 < 224 -> ((l0 - 192) shl 8) + (octet() ?: return null) + 192
                l0 == 255 -> uint32() ?: return null
                else -> return null
            }
        } else {
            when (c and 0x03) {
                0 -> octet() ?: return null
                1 -> ((octet() ?: return null) shl 8) or (octet() ?: return null)
                2 -> uint32() ?: return null
                else -> return null
            }
        }
        return Header(i, length)
    }
}

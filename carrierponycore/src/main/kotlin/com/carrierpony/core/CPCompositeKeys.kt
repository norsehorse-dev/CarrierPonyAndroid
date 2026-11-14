// CPCompositeKeys.kt
// CarrierPonyCore
//
// The key-packet side of the ML-KEM-768 + X25519 encryption subkey (algorithm
// 35, RFC 9980) on a v6 identity.
//
// BouncyCastle 1.85 cannot emit an algorithm-35 key packet, but it can parse
// one: a v6 key packet carries its key-material length, so an algorithm BC does
// not know is kept as opaque bytes, and BC still computes the fingerprint and
// can sign and verify a binding over it. So the subkey packets are written by
// hand here and everything around them (ring parsing, fingerprints, binding
// signatures) stays with BC. This is the approach proven in PGPony Android.
//
// v6 public subkey body:  6 | created (4) | 35 | material length (4) |
//                         X25519 public (32) || ML-KEM-768 public (1184)
// v6 secret subkey body:  the public body | S2K usage 0 |
//                         X25519 secret (32) || ML-KEM-768 seed (64)
//
// Only unprotected secret material (S2K usage 0) is handled. A generated
// identity has no passphrase: the whole record lives in the platform-encrypted
// store, as with v4.

package com.carrierpony.core

import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import java.io.ByteArrayOutputStream

object CPCompositeKeys {

    private const val TAG_SECRET_SUBKEY = 7
    private const val TAG_PUBLIC_SUBKEY = 14
    private const val KEY_VERSION = 6
    private const val USAGE_NONE = 0

    /** The algorithm-35 encryption subkey in [ring], if it has one. */
    fun encryptionSubkey(ring: PGPPublicKeyRing): PGPPublicKey? =
        ring.publicKeys.asSequence().firstOrNull { it.algorithm == CPCompositeKem.ALGORITHM_ID }

    /** The secret half of the algorithm-35 subkey in [ring], if it has one. */
    fun encryptionSubkey(ring: PGPSecretKeyRing): PGPSecretKey? =
        ring.secretKeys.asSequence().firstOrNull { it.publicKey.algorithm == CPCompositeKem.ALGORITHM_ID }

    /**
     * The composite public key material of an algorithm-35 key: X25519 public
     * (32) || ML-KEM-768 public (1184). Null if [key] is not algorithm 35 or
     * its material is not exactly that length.
     */
    fun publicMaterial(key: PGPPublicKey): ByteArray? {
        if (key.algorithm != CPCompositeKem.ALGORITHM_ID || key.version != KEY_VERSION) return null
        val bytes = key.publicKeyPacket.key.encoded
        return if (bytes.size == CPCompositeKem.COMPOSITE_PUB_LEN) bytes else null
    }

    /**
     * The composite secret key material of an algorithm-35 secret subkey:
     * X25519 secret (32) || ML-KEM-768 seed (64). Throws
     * [CPCryptoError.PassphraseRequired] for protected material, which this
     * core does not unlock, and [CPCryptoError.MalformedKey] for anything that
     * is not a well-formed v6 algorithm-35 secret subkey.
     */
    fun secretMaterial(key: PGPSecretKey): ByteArray {
        val body = packetBody(key.encoded)
        var i = 0
        fun need(n: Int) {
            if (body.size - i < n) throw CPCryptoError.MalformedKey("Composite secret key is truncated")
        }
        need(10)
        val version = body[i++].toInt() and 0xFF
        if (version != KEY_VERSION) throw CPCryptoError.MalformedKey("Composite secret key must be v6, got v$version")
        i += 4
        val algorithm = body[i++].toInt() and 0xFF
        if (algorithm != CPCompositeKem.ALGORITHM_ID) {
            throw CPCryptoError.MalformedKey("Expected algorithm 35, got $algorithm")
        }
        val materialLength = readUInt32(body, i)
        i += 4
        if (materialLength != CPCompositeKem.COMPOSITE_PUB_LEN) {
            throw CPCryptoError.MalformedKey("Composite public material must be ${CPCompositeKem.COMPOSITE_PUB_LEN} bytes")
        }
        need(materialLength + 1)
        val publicMaterial = body.copyOfRange(i, i + materialLength)
        i += materialLength
        val usage = body[i++].toInt() and 0xFF
        if (usage != USAGE_NONE) throw CPCryptoError.PassphraseRequired()
        if (body.size - i < CPCompositeKem.COMPOSITE_SECRET_LEN) {
            throw CPCryptoError.MalformedKey("Composite secret material must be ${CPCompositeKem.COMPOSITE_SECRET_LEN} bytes")
        }
        val secret = body.copyOfRange(i, i + CPCompositeKem.COMPOSITE_SECRET_LEN)

        // The secret must belong to the public key it sits under: the X25519
        // half is checked by the KEM at use, the ML-KEM half here.
        val (_, seed) = CPCompositeKem.splitSecret(secret)
        val (_, mlkemPublic) = CPCompositeKem.splitPublic(publicMaterial)
        if (!CPCompositeKem.mlkemPublicFromSeed(seed).contentEquals(mlkemPublic)) {
            throw CPCryptoError.MalformedKey("Composite secret key does not match its public key")
        }
        return secret
    }

    /** The (public subkey packet, secret subkey packet) for a fresh key pair. */
    internal fun subkeyPackets(creationSeconds: Long, keyPair: CPCompositeKem.KeyPair): Pair<ByteArray, ByteArray> {
        require(keyPair.publicKey.size == CPCompositeKem.COMPOSITE_PUB_LEN)
        require(keyPair.secretKey.size == CPCompositeKem.COMPOSITE_SECRET_LEN)
        val publicBody = ByteArrayOutputStream().apply {
            write(KEY_VERSION)
            write(uint32(creationSeconds.toInt()))
            write(CPCompositeKem.ALGORITHM_ID)
            write(uint32(keyPair.publicKey.size))
            write(keyPair.publicKey)
        }.toByteArray()
        val secretBody = ByteArrayOutputStream().apply {
            write(publicBody)
            write(USAGE_NONE)
            write(keyPair.secretKey)
        }.toByteArray()
        return packet(TAG_PUBLIC_SUBKEY, publicBody) to packet(TAG_SECRET_SUBKEY, secretBody)
    }

    // ── Packet framing ─────────────────────────────────────────────────

    /** A new-format packet with a five-octet length, which fits any body. */
    private fun packet(tag: Int, body: ByteArray): ByteArray =
        byteArrayOf((0xC0 or tag).toByte(), 0xFF.toByte()) + uint32(body.size) + body

    /** The body of the first packet in [encoded], old or new format. */
    internal fun packetBody(encoded: ByteArray): ByteArray {
        fun bad(): Nothing = throw CPCryptoError.MalformedKey("Not an OpenPGP packet")
        if (encoded.size < 2) bad()
        val header = encoded[0].toInt() and 0xFF
        if (header and 0x80 == 0) bad()
        var i = 1
        val length: Int
        if (header and 0x40 != 0) {
            val first = encoded[i++].toInt() and 0xFF
            length = when {
                first < 192 -> first
                first < 224 -> {
                    if (encoded.size < 3) bad()
                    ((first - 192) shl 8) + (encoded[i++].toInt() and 0xFF) + 192
                }
                first == 255 -> {
                    if (encoded.size < 6) bad()
                    readUInt32(encoded, i).also { i += 4 }
                }
                else -> bad()
            }
        } else {
            length = when (header and 0x03) {
                0 -> encoded[i++].toInt() and 0xFF
                1 -> {
                    if (encoded.size < 3) bad()
                    (((encoded[i].toInt() and 0xFF) shl 8) or (encoded[i + 1].toInt() and 0xFF)).also { i += 2 }
                }
                2 -> {
                    if (encoded.size < 5) bad()
                    readUInt32(encoded, i).also { i += 4 }
                }
                else -> bad()
            }
        }
        // PGPSecretKey.getEncoded() appends a subkey's binding signatures after
        // the key packet, so only the first packet is read.
        if (length < 0 || encoded.size - i < length) bad()
        return encoded.copyOfRange(i, i + length)
    }

    private fun uint32(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun readUInt32(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 24) or ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or (b[offset + 3].toInt() and 0xFF)
}

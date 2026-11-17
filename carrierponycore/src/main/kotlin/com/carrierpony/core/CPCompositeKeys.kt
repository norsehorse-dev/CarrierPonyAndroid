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
    private const val USAGE_AEAD = 253
    private const val CIPHER_AES128 = 7
    private const val CIPHER_AES192 = 8
    private const val CIPHER_AES256 = 9
    private const val AEAD_EAX = 1
    private const val AEAD_OCB = 2
    private const val AEAD_GCM = 3
    private const val AEAD_TAG_LEN = 16
    private const val S2K_ARGON2 = 4
    private const val ARGON2_SALT_LEN = 16
    private const val ARGON2_MAX_MEMORY_EXPONENT = 18

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
     * X25519 secret (32) || ML-KEM-768 seed (64).
     *
     * Protected material is unlocked with [passphrase] when it uses the v6
     * AEAD protection (S2K usage 253) with an Argon2 S2K, the form PGPony
     * exports. Throws [CPCryptoError.PassphraseRequired] when a passphrase is
     * needed and none was given, [CPCryptoError.InvalidPassphrase] when it
     * does not open the key, and [CPCryptoError.MalformedKey] for anything
     * that is not a well-formed v6 algorithm-35 secret subkey or uses a
     * protection this core does not read.
     */
    fun secretMaterial(key: PGPSecretKey, passphrase: String? = null): ByteArray {
        val encoded = key.encoded
        val body = packetBody(encoded)
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
        val publicEnd = i
        val usage = body[i++].toInt() and 0xFF
        val secret = if (usage == USAGE_NONE) {
            if (body.size - i < CPCompositeKem.COMPOSITE_SECRET_LEN) {
                throw CPCryptoError.MalformedKey("Composite secret material must be ${CPCompositeKem.COMPOSITE_SECRET_LEN} bytes")
            }
            body.copyOfRange(i, i + CPCompositeKem.COMPOSITE_SECRET_LEN)
        } else {
            unlock(packetTypeOctet(encoded), body, publicEnd, passphrase)
        }

        // The secret must belong to the public key it sits under: the X25519
        // half is checked by the KEM at use, the ML-KEM half here.
        val (_, seed) = CPCompositeKem.splitSecret(secret)
        val (_, mlkemPublic) = CPCompositeKem.splitPublic(publicMaterial)
        if (!CPCompositeKem.mlkemPublicFromSeed(seed).contentEquals(mlkemPublic)) {
            throw CPCryptoError.MalformedKey("Composite secret key does not match its public key")
        }
        return secret
    }

    /** True when the algorithm-35 secret subkey [key] is passphrase-protected. */
    fun isProtected(key: PGPSecretKey): Boolean {
        val body = packetBody(key.encoded)
        if (body.size < 10) return false
        val usageAt = 10 + readUInt32(body, 6)
        return usageAt in 10 until body.size && (body[usageAt].toInt() and 0xFF) != USAGE_NONE
    }

    // ── Protected secret material (RFC 9580 section 5.5.3, usage 253) ──

    /**
     * Material already unlocked in this process, so the Argon2 run (64 MiB and
     * a second or so on a phone) is paid once and not for every message. The
     * key is a digest of the packet and the passphrase; the value is no more
     * than the passphrase the app already holds would give. [forgetUnlocked]
     * empties it when the app locks or the identity is removed.
     */
    private val unlocked = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** Drop every unlocked composite secret held in memory. */
    fun forgetUnlocked() {
        unlocked.values.forEach { it.fill(0) }
        unlocked.clear()
    }

    private fun unlock(packetType: Int, body: ByteArray, publicEnd: Int, passphrase: String?): ByteArray {
        fun unsupported(what: String): Nothing =
            throw CPCryptoError.MalformedKey("Unsupported protection on the composite secret key: $what")
        fun truncated(): Nothing = throw CPCryptoError.MalformedKey("Composite secret key is truncated")

        var i = publicEnd
        val usage = body[i++].toInt() and 0xFF
        if (usage != USAGE_AEAD) unsupported("S2K usage $usage")
        if (body.size - i < 4) truncated()
        val parametersLength = body[i++].toInt() and 0xFF
        val parametersEnd = i + parametersLength
        if (parametersEnd > body.size) truncated()
        val cipher = body[i++].toInt() and 0xFF
        val aead = body[i++].toInt() and 0xFF
        val s2kLength = body[i++].toInt() and 0xFF
        if (i + s2kLength > parametersEnd) truncated()
        val s2k = body.copyOfRange(i, i + s2kLength)
        i += s2kLength

        val keyLength = when (cipher) {
            CIPHER_AES128 -> 16
            CIPHER_AES192 -> 24
            CIPHER_AES256 -> 32
            else -> unsupported("cipher $cipher")
        }
        val nonceLength = when (aead) {
            AEAD_EAX -> 16
            AEAD_OCB -> 15
            AEAD_GCM -> 12
            else -> unsupported("AEAD mode $aead")
        }
        if (parametersEnd - i != nonceLength) throw CPCryptoError.MalformedKey("Composite secret key has a bad nonce")
        val nonce = body.copyOfRange(i, i + nonceLength)
        val ciphertext = body.copyOfRange(parametersEnd, body.size)
        if (ciphertext.size != CPCompositeKem.COMPOSITE_SECRET_LEN + AEAD_TAG_LEN) {
            throw CPCryptoError.MalformedKey("Composite secret material must be ${CPCompositeKem.COMPOSITE_SECRET_LEN} bytes")
        }

        if (s2k.isEmpty() || (s2k[0].toInt() and 0xFF) != S2K_ARGON2) {
            unsupported("S2K type ${s2k.firstOrNull()?.toInt()?.and(0xFF)}")
        }
        if (s2k.size != 1 + ARGON2_SALT_LEN + 3) throw CPCryptoError.MalformedKey("Composite secret key has a bad Argon2 S2K")
        val salt = s2k.copyOfRange(1, 1 + ARGON2_SALT_LEN)
        val passes = s2k[17].toInt() and 0xFF
        val parallelism = s2k[18].toInt() and 0xFF
        val memoryExponent = s2k[19].toInt() and 0xFF
        if (passes < 1 || parallelism < 1 || memoryExponent < 3) {
            throw CPCryptoError.MalformedKey("Composite secret key has a bad Argon2 S2K")
        }
        // 2^18 KiB is 256 MiB. More than that will not fit on a phone, and a
        // crafted file must not be able to ask for it.
        if (memoryExponent > ARGON2_MAX_MEMORY_EXPONENT) unsupported("Argon2 memory 2^$memoryExponent KiB")

        if (passphrase.isNullOrEmpty()) throw CPCryptoError.PassphraseRequired()
        val passphraseBytes = passphrase.toByteArray(Charsets.UTF_8)

        val cacheKey = org.bouncycastle.crypto.digests.SHA256Digest().let { digest ->
            digest.update(body, 0, body.size)
            digest.update(0.toByte())
            digest.update(passphraseBytes, 0, passphraseBytes.size)
            ByteArray(32).also { digest.doFinal(it, 0) }
        }.joinToString("") { "%02x".format(it) }
        unlocked[cacheKey]?.let { return it.copyOf() }

        val derived = ByteArray(keyLength)
        try {
            val parameters = org.bouncycastle.crypto.params.Argon2Parameters.Builder(
                org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id
            )
                .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withIterations(passes)
                .withParallelism(parallelism)
                .withMemoryAsKB(1 shl memoryExponent)
                .build()
            org.bouncycastle.crypto.generators.Argon2BytesGenerator().apply { init(parameters) }
                .generateBytes(passphraseBytes, derived)
        } catch (e: OutOfMemoryError) {
            unsupported("Argon2 memory 2^$memoryExponent KiB")
        }

        // KEK = HKDF-SHA256(derived, no salt, info = packet type, key version,
        // cipher, AEAD mode). The same packet type octet and the public key
        // fields are the associated data.
        val kek = ByteArray(keyLength)
        org.bouncycastle.crypto.generators.HKDFBytesGenerator(org.bouncycastle.crypto.digests.SHA256Digest()).apply {
            init(
                org.bouncycastle.crypto.params.HKDFParameters(
                    derived, null,
                    byteArrayOf(packetType.toByte(), KEY_VERSION.toByte(), cipher.toByte(), aead.toByte())
                )
            )
        }.generateBytes(kek, 0, kek.size)
        derived.fill(0)

        val associated = byteArrayOf(packetType.toByte()) + body.copyOfRange(0, publicEnd)
        val engine: org.bouncycastle.crypto.modes.AEADCipher = when (aead) {
            AEAD_EAX -> org.bouncycastle.crypto.modes.EAXBlockCipher(org.bouncycastle.crypto.engines.AESEngine.newInstance())
            AEAD_OCB -> org.bouncycastle.crypto.modes.OCBBlockCipher(
                org.bouncycastle.crypto.engines.AESEngine.newInstance(),
                org.bouncycastle.crypto.engines.AESEngine.newInstance()
            )
            else -> org.bouncycastle.crypto.modes.GCMBlockCipher.newInstance(org.bouncycastle.crypto.engines.AESEngine.newInstance())
        }
        engine.init(
            false,
            org.bouncycastle.crypto.params.AEADParameters(
                org.bouncycastle.crypto.params.KeyParameter(kek), AEAD_TAG_LEN * 8, nonce, associated
            )
        )
        val plain = ByteArray(engine.getOutputSize(ciphertext.size))
        try {
            val written = engine.processBytes(ciphertext, 0, ciphertext.size, plain, 0)
            engine.doFinal(plain, written)
        } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
            throw CPCryptoError.InvalidPassphrase()
        } finally {
            kek.fill(0)
        }
        if (plain.size != CPCompositeKem.COMPOSITE_SECRET_LEN) {
            throw CPCryptoError.MalformedKey("Composite secret material must be ${CPCompositeKem.COMPOSITE_SECRET_LEN} bytes")
        }
        unlocked[cacheKey] = plain.copyOf()
        return plain
    }

    /** The packet type octet of [encoded] in new-format form (0xC0 | tag),
     *  whichever framing the packet was written with. */
    private fun packetTypeOctet(encoded: ByteArray): Int {
        val header = encoded[0].toInt() and 0xFF
        val tag = if (header and 0x40 != 0) header and 0x3F else (header ushr 2) and 0x0F
        return 0xC0 or tag
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

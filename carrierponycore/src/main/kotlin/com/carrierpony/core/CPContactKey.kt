// CPContactKey.kt
// CarrierPony core
//
// Reading a public key someone handed over as text or as a file, to add them
// as a contact. Pairing delivers a key the other app wrote; this path takes
// whatever a person pastes, so it has to say clearly what is wrong with it.
//
// A key is usable when it has the shape the apps make and can encrypt to:
//   v4: Ed25519 primary key, Curve25519 encryption subkey
//   v6: Ed25519 primary key, ML-KEM-768 + X25519 encryption subkey
// and the primary key has signed for that subkey (CPKeyTrust), the same line
// held every time a message is encrypted.
//
// What comes back is the first key in the input, as clean armor with nothing
// around it: no comment headers, no surrounding text, no further keys.

package com.carrierpony.core

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object CPContactKey {

    sealed class Result {
        /** A key the app can hold as a contact. */
        class Usable(
            /** Primary-key fingerprint, uppercase hex: 40 characters (v4) or 64 (v6). */
            val fingerprint: String,
            val armoredPublicKey: String,
            /** The name in the key's first user ID, if it has one. */
            val name: String?
        ) : Result()

        /** A secret key. Never something to hand to another person. */
        data object SecretKey : Result()

        /** Not an OpenPGP public key at all. */
        data object NotAKey : Result()

        /** A real public key, but not one the app can use: another algorithm,
         *  no encryption subkey, a subkey its primary key has not signed, or a
         *  revoked key. */
        data object Unsupported : Result()
    }

    /** Anything larger is not one person's public key. */
    const val MAX_INPUT_BYTES = 256 * 1024

    private const val BEGIN_PUBLIC = "-----BEGIN PGP PUBLIC KEY BLOCK-----"
    private const val END_PUBLIC = "-----END PGP PUBLIC KEY BLOCK-----"
    private const val PRIVATE_MARK = "PGP PRIVATE KEY BLOCK"

    /** Inspect pasted text. */
    fun inspect(text: String): Result = inspect(text.toByteArray(Charsets.UTF_8))

    /** Inspect a file's bytes, armored or binary. */
    fun inspect(input: ByteArray): Result {
        if (input.isEmpty() || input.size > MAX_INPUT_BYTES) return Result.NotAKey
        val binary = binary(input) ?: return if (looksPrivate(input)) Result.SecretKey else Result.NotAKey
        if (isSecretKey(binary)) return Result.SecretKey

        val first = firstRing(binary) ?: return Result.NotAKey
        val ring = try { CPMessenger.loadPublicRing(first) } catch (e: Exception) { return Result.NotAKey }
        val primary = ring.publicKey
        if (!primary.isMasterKey) return Result.NotAKey
        if (primary.hasRevocation()) return Result.Unsupported

        val composite = CPCompositeKeys.encryptionSubkey(ring)
        val usable = when (primary.version) {
            4 -> primary.algorithm == PublicKeyAlgorithmTags.EDDSA_LEGACY &&
                composite == null &&
                CPMessenger.findEncryptionKey(ring)?.let { !it.isMasterKey && it.algorithm == PublicKeyAlgorithmTags.ECDH } == true
            6 -> primary.algorithm == PublicKeyAlgorithmTags.Ed25519 &&
                composite != null && CPKeyTrust.isBound(ring, composite)
            else -> false
        }
        if (!usable) return Result.Unsupported

        val armored = armor(first)
        val fingerprint = CPKeyInfo.primaryFingerprint(armored) ?: return Result.NotAKey
        return Result.Usable(fingerprint, armored, CPKeyInfo.userIDName(first))
    }

    // ── Internals ──────────────────────────────────────────────────────

    /** The binary packets of the input: the public key block if it is armored
     *  text (whatever surrounds it), else the bytes as they are. Null when it
     *  is text with no public key block in it, or armor that does not decode. */
    private fun binary(input: ByteArray): ByteArray? {
        val head = String(input, 0, minOf(input.size, 64 * 1024), Charsets.ISO_8859_1)
        if (!head.contains("-----BEGIN PGP")) {
            // Binary packets start with a byte whose top bit is set.
            return if (input[0].toInt() and 0x80 != 0) input else null
        }
        val text = String(input, Charsets.UTF_8)
        val start = text.indexOf(BEGIN_PUBLIC)
        if (start < 0) return null
        val end = text.indexOf(END_PUBLIC, start)
        if (end < 0) return null
        return CPArmor.dearmor(text.substring(start, end + END_PUBLIC.length))
    }

    private fun looksPrivate(input: ByteArray): Boolean =
        String(input, 0, minOf(input.size, 64 * 1024), Charsets.ISO_8859_1).contains(PRIVATE_MARK)

    private fun isSecretKey(binary: ByteArray): Boolean = try {
        PGPSecretKeyRing(ByteArrayInputStream(binary), BcKeyFingerprintCalculator())
        true
    } catch (e: Exception) {
        false
    }

    /** The bytes of the first key in [binary], leaving out any keys after it.
     *  Null if no key parses. */
    private fun firstRing(binary: ByteArray): ByteArray? {
        val stream = ByteArrayInputStream(binary)
        try {
            PGPPublicKeyRing(stream, BcKeyFingerprintCalculator())
        } catch (e: Exception) {
            return null
        }
        val left = stream.available()
        if (left == 0) return binary
        // The reader stops after taking the first byte of the next key.
        val length = binary.size - left - 1
        if (length <= 0) return null
        val cut = binary.copyOf(length)
        return try {
            PGPPublicKeyRing(ByteArrayInputStream(cut), BcKeyFingerprintCalculator())
            cut
        } catch (e: Exception) {
            // Not where the first key ends after all: keep everything.
            binary
        }
    }

    private fun armor(binary: ByteArray): String {
        val out = ByteArrayOutputStream()
        val armored = ArmoredOutputStream(out).clean()
        armored.write(binary)
        armored.close()
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}

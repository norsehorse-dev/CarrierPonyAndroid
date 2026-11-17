// CPKeyImport.kt
// CarrierPonyCore
//
// Primitives for importing an existing OpenPGP private key (from gpg, PGPony,
// etc.) as a CarrierPony identity. Two shapes are taken, the two the engine
// makes itself: v4 Ed25519 (sign) + Cv25519 (encrypt), and v6 Ed25519 with an
// ML-KEM-768 + X25519 encryption subkey (algorithm 35). A v6 key may sign with
// its primary or with a signing subkey, and may carry other subkeys, which
// are kept and not used. Anything else is rejected. A passphrase-protected key stays
// protected: its bytes are stored as-is; callers hold the passphrase behind
// the app lock and pass it per-operation (CPMessenger already threads a
// passphrase through signing and decryption).

package com.carrierpony.core

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

sealed class CPKeyImportError(message: String) : Exception(message) {
    class Malformed : CPKeyImportError("Not a parseable OpenPGP secret key")
    class NotSupported : CPKeyImportError("Primary key is not Ed25519")
    class NoEncryptionSubkey : CPKeyImportError("No usable Curve25519 or ML-KEM-768 encryption subkey")
    class WrongPassphrase : CPKeyImportError("Passphrase does not unlock this key")
}

object CPKeyImport {

    /** Parse a secret key and gate the algorithm: the primary must be Ed25519
     *  (EdDSA-legacy). Throws Malformed or NotSupported. */
    private fun ring(secret: ByteArray): PGPSecretKeyRing {
        val ring = try {
            PGPSecretKeyRing(PGPUtil.getDecoderStream(ByteArrayInputStream(secret)), BcKeyFingerprintCalculator())
        } catch (e: Exception) {
            throw CPKeyImportError.Malformed()
        }
        val primary = ring.secretKey.publicKey
        val v4 = primary.version == 4 && primary.algorithm == PublicKeyAlgorithmTags.EDDSA_LEGACY
        val v6 = primary.version == 6 && primary.algorithm == PublicKeyAlgorithmTags.Ed25519
        if (!v4 && !v6) throw CPKeyImportError.NotSupported()
        return ring
    }

    private fun isV6(ring: PGPSecretKeyRing): Boolean = ring.secretKey.publicKey.version == 6

    /** The armored transferable public key derived from a secret key. */
    fun transferablePublicFromSecret(secret: ByteArray): String {
        val ring = ring(secret)
        val out = ByteArrayOutputStream()
        val armored = ArmoredOutputStream(out).clean()
        for (key in ring.publicKeys) {
            (key as PGPPublicKey).encode(armored)
        }
        armored.close()
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** True when the primary secret key material is passphrase-protected. */
    fun needsPassphrase(secret: ByteArray): Boolean {
        val ring = ring(secret)
        if (isV6(ring)) {
            // A v6 export can protect its keys one by one, so any protected
            // key the app would use means a passphrase is needed.
            val signing = CPMessenger.pickSigningSecretKey(ring)
            val composite = CPCompositeKeys.encryptionSubkey(ring)
            return (signing != null && signing.s2KUsage.toInt() != 0) ||
                (composite != null && CPCompositeKeys.isProtected(composite))
        }
        return try {
            ring.secretKey.extractPrivateKey(null)
            false
        } catch (e: Exception) {
            true
        }
    }

    /** Validate the passphrase against the primary key and confirm a usable
     *  Cv25519 encryption subkey exists. Throws WrongPassphrase or
     *  NoEncryptionSubkey (or Malformed/NotSupported from parsing). */
    fun validate(secret: ByteArray, passphrase: String?) {
        val ring = ring(secret)
        val decryptor = passphrase?.let {
            BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider()).build(it.toCharArray())
        }
        if (isV6(ring)) {
            // The key that signs (a signing subkey when there is one, else the
            // primary) and the ML-KEM-768 + X25519 subkey must both open.
            val signing = CPMessenger.pickSigningSecretKey(ring) ?: throw CPKeyImportError.NotSupported()
            try {
                signing.extractPrivateKey(if (signing.s2KUsage.toInt() == 0) null else decryptor)
            } catch (e: Exception) {
                throw CPKeyImportError.WrongPassphrase()
            }
            val composite = CPCompositeKeys.encryptionSubkey(ring) ?: throw CPKeyImportError.NoEncryptionSubkey()
            try {
                CPCompositeKeys.secretMaterial(composite, passphrase)
            } catch (e: CPCryptoError.PassphraseRequired) {
                throw CPKeyImportError.WrongPassphrase()
            } catch (e: CPCryptoError.InvalidPassphrase) {
                throw CPKeyImportError.WrongPassphrase()
            } catch (e: Exception) {
                throw CPKeyImportError.NoEncryptionSubkey()
            }
            return
        }
        try {
            ring.secretKey.extractPrivateKey(decryptor)
        } catch (e: Exception) {
            throw CPKeyImportError.WrongPassphrase()
        }
        val encryption = ring.secretKeys.asSequence()
            .filterIsInstance<org.bouncycastle.openpgp.PGPSecretKey>()
            .firstOrNull { it.publicKey.algorithm == PublicKeyAlgorithmTags.ECDH }
            ?: throw CPKeyImportError.NoEncryptionSubkey()
        try {
            encryption.extractPrivateKey(decryptor)
        } catch (e: Exception) {
            throw CPKeyImportError.NoEncryptionSubkey()
        }
    }

    /** The display name from the key's first User ID ("Name <email>" -> "Name").
     *  Null when the UID is only an email or absent. */
    fun userIDName(secret: ByteArray): String? {
        val ring = try { ring(secret) } catch (e: Exception) { return null }
        val uid = ring.publicKey.userIDs.asSequence().firstOrNull() ?: return null
        val trimmed = uid.trim()
        val bracket = trimmed.indexOf('<')
        val name = if (bracket >= 0) trimmed.substring(0, bracket).trim() else trimmed
        return name.ifEmpty { null }
    }
}

// CPIdentityGenerator.kt
// CarrierPonyCore
//
// Generates the app's messaging identity: a v4 Ed25519 signing primary
// (EdDSA-legacy, algorithm 22) with a Cv25519 encryption subkey (ECDH,
// algorithm 18). This is the Android twin of iOS
// OpenPGPKeyGenerator.generateV4Identity(name:email:) and follows the key
// layout the CarrierPony relay and iOS peers expect: 40-hex fingerprint,
// signing primary for relay-challenge signatures, Cv25519 subkey for message
// encryption.
//
// Generated identities are passphrase-less by design — the whole record lives
// inside platform-encrypted storage on the device (Keychain on iOS, the
// Keystore-encrypted store on Android), matching Identity.protected = false.
// Passphrase-protected keys enter only via import, which this core unlocks at
// use time.
//
// The generator settings (POSITIVE_CERTIFICATION, SHA-256 certification hash,
// AES-256/192/128 and SHA-256/384/512 preferences, sign+certify primary
// flags, comms+storage encryption subkey flags) mirror the Ed25519 path
// proven in PGPony Android.

package com.carrierpony.core

import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.openpgp.PGPKeyFlags
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Date

/**
 * The material a fresh identity consists of, in the exact shape the app's
 * Identity record persists: serialized secret key bytes, the armored public
 * key, and the primary fingerprint (uppercase hex: 40 for v4, 64 for v6).
 */
data class CPGeneratedIdentity(
    val fingerprint: String,
    val secretKey: ByteArray,
    val armoredPublicKey: String
)

object CPIdentityGenerator {

    /**
     * Generate a fresh v4 Ed25519+Cv25519 identity with user ID
     * "name <email>". Throws CPCryptoError.KeyGenerationFailed on any
     * BouncyCastle failure.
     */
    fun generateV4Identity(name: String, email: String): CPGeneratedIdentity {
        try {
            val userID = "$name <$email>"
            val creationDate = Date()
            val random = SecureRandom()

            // Ed25519 signing primary — EdDSA-legacy (algorithm 22)
            val edGen = Ed25519KeyPairGenerator()
            edGen.init(Ed25519KeyGenerationParameters(random))
            val masterKeyPair = BcPGPKeyPair(
                PublicKeyAlgorithmTags.EDDSA_LEGACY,
                edGen.generateKeyPair(),
                creationDate
            )

            // Cv25519 encryption subkey — ECDH (algorithm 18)
            val xGen = X25519KeyPairGenerator()
            xGen.init(X25519KeyGenerationParameters(random))
            val encKeyPair = BcPGPKeyPair(
                PublicKeyAlgorithmTags.ECDH,
                xGen.generateKeyPair(),
                creationDate
            )

            val sigSubpackets = PGPSignatureSubpacketGenerator()
            sigSubpackets.setKeyFlags(false, PGPKeyFlags.CAN_SIGN or PGPKeyFlags.CAN_CERTIFY)
            sigSubpackets.setPreferredSymmetricAlgorithms(false, intArrayOf(
                SymmetricKeyAlgorithmTags.AES_256,
                SymmetricKeyAlgorithmTags.AES_192,
                SymmetricKeyAlgorithmTags.AES_128
            ))
            sigSubpackets.setPreferredHashAlgorithms(false, intArrayOf(
                HashAlgorithmTags.SHA256,
                HashAlgorithmTags.SHA384,
                HashAlgorithmTags.SHA512
            ))
            sigSubpackets.setIssuerFingerprint(false, masterKeyPair.publicKey)

            val encSubpackets = PGPSignatureSubpacketGenerator()
            encSubpackets.setKeyFlags(false, PGPKeyFlags.CAN_ENCRYPT_COMMS or PGPKeyFlags.CAN_ENCRYPT_STORAGE)

            val certSigner = BcPGPContentSignerBuilder(
                masterKeyPair.publicKey.algorithm,
                HashAlgorithmTags.SHA256
            )

            val generator = PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                masterKeyPair,
                userID,
                BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1),
                sigSubpackets.generate(),
                null,
                certSigner,
                null
            )
            // Three-argument addSubKey: an encryption subkey gets a binding
            // signature from the PRIMARY only. The four-argument overload
            // additionally embeds a primary-key-binding back-signature made
            // BY the subkey, which only signing-capable subkeys can produce —
            // an X25519 ECDH key cannot sign, so that path throws.
            generator.addSubKey(encKeyPair, encSubpackets.generate(), null)

            val secretKeyRing = generator.generateSecretKeyRing()
            val publicKeyRing = generator.generatePublicKeyRing()

            return CPGeneratedIdentity(
                fingerprint = CPKeyInfo.bytesToHex(publicKeyRing.publicKey.fingerprint),
                secretKey = secretKeyRing.encoded,
                armoredPublicKey = armorPublicKeyRing(publicKeyRing)
            )
        } catch (e: CPCryptoError) {
            throw e
        } catch (e: Exception) {
            throw CPCryptoError.KeyGenerationFailed(e.message ?: "Unknown error")
        }
    }

    /**
     * Generate a fresh v6 (RFC 9580) identity with user ID "name <email>":
     * an Ed25519 primary that certifies and signs, and one ML-KEM-768 + X25519
     * encryption subkey (algorithm 35, RFC 9980). The fingerprint is 64 hex
     * characters.
     *
     * The primary signs, as it does on a v4 identity, so the fingerprint inside
     * a message signature is the identity itself and a receiver finds the
     * sender by it. There is no signing subkey: every key lives in the same
     * store, so one would add nothing but a second fingerprint to track.
     *
     * There is no separate classical encryption subkey either: the composite is
     * already a hybrid, and a plain X25519 subkey would let a sender leave the
     * post-quantum part out.
     *
     * BouncyCastle builds the primary and every signature. It cannot emit the
     * algorithm-35 subkey, so CPCompositeKeys writes those two packets and BC
     * binds the subkey to the primary.
     */
    fun generateV6Identity(name: String, email: String): CPGeneratedIdentity {
        try {
            val userID = "$name <$email>"
            val creationDate = Date()
            val random = SecureRandom()

            val implementation = org.bouncycastle.openpgp.api.bc.BcOpenPGPImplementation()
            val keyGenerator = org.bouncycastle.openpgp.api.OpenPGPKeyGenerator(
                implementation,
                org.bouncycastle.bcpg.PublicKeyPacket.VERSION_6,
                false,
                creationDate
            )
            // The generator makes the primary certify-only and puts a five-year
            // expiry on it. A messaging identity signs with its primary and does
            // not expire, so both subpackets are replaced.
            val selfSignature = org.bouncycastle.openpgp.api.SignatureParameters.Callback.Util.modifyHashedSubpackets(
                org.bouncycastle.openpgp.api.SignatureSubpacketsFunction { subpackets ->
                    subpackets.removePacketsOfType(org.bouncycastle.bcpg.SignatureSubpacketTags.KEY_EXPIRE_TIME)
                    subpackets.removePacketsOfType(org.bouncycastle.bcpg.SignatureSubpacketTags.KEY_FLAGS)
                    subpackets.setKeyFlags(true, PGPKeyFlags.CAN_CERTIFY or PGPKeyFlags.CAN_SIGN)
                    subpackets
                }
            )
            val baseRing = keyGenerator
                .withPrimaryKey(
                    org.bouncycastle.openpgp.api.KeyPairGeneratorCallback { gen -> gen.generateEd25519KeyPair() },
                    selfSignature
                )
                .addUserId(userID, selfSignature)
                .build()
                .getPGPSecretKeyRing()

            // The composite encryption subkey, hand-emitted.
            val composite = CPCompositeKem.generateKeyPair(random)
            val (publicPacket, secretPacket) = CPCompositeKeys.subkeyPackets(creationDate.time / 1000L, composite)

            // Parse primary + new subkey so BC holds the subkey as a PGPPublicKey.
            val calculator = org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator()
            val primaryPublic = baseRing.publicKey
            val temp = ByteArrayOutputStream().apply {
                write(primaryPublic.encoded)
                write(publicPacket)
            }.toByteArray()
            val subkeyPublic = PGPPublicKeyRing(java.io.ByteArrayInputStream(temp), calculator)
                .publicKeys.asSequence().first { !it.isMasterKey }

            // Subkey binding signature from the primary.
            val primaryPrivate = baseRing.secretKey.extractPrivateKey(null)
            val binding = org.bouncycastle.openpgp.PGPSignatureGenerator(
                BcPGPContentSignerBuilder(primaryPublic.algorithm, HashAlgorithmTags.SHA256),
                primaryPublic
            )
            binding.init(PGPSignature.SUBKEY_BINDING, primaryPrivate)
            val bindingSubpackets = PGPSignatureSubpacketGenerator()
            bindingSubpackets.setKeyFlags(true, PGPKeyFlags.CAN_ENCRYPT_COMMS or PGPKeyFlags.CAN_ENCRYPT_STORAGE)
            bindingSubpackets.setIssuerFingerprint(false, primaryPublic)
            binding.setHashedSubpackets(bindingSubpackets.generate())
            val bindingSignature = binding.generateCertification(primaryPublic, subkeyPublic).encoded

            val assembled = ByteArrayOutputStream().apply {
                write(baseRing.encoded)
                write(secretPacket)
                write(bindingSignature)
            }.toByteArray()
            val secretKeyRing = org.bouncycastle.openpgp.PGPSecretKeyRing(
                java.io.ByteArrayInputStream(assembled), calculator
            )
            val publicKeyRing = PGPPublicKeyRing(secretKeyRing.publicKeys.asSequence().toList())

            // Fail here, not at first use, if the ring did not come back whole.
            val storedSubkey = CPCompositeKeys.encryptionSubkey(secretKeyRing)
                ?: throw CPCryptoError.KeyGenerationFailed("Composite subkey missing from the generated ring")
            if (!CPCompositeKeys.secretMaterial(storedSubkey).contentEquals(composite.secretKey)) {
                throw CPCryptoError.KeyGenerationFailed("Composite subkey did not round-trip")
            }

            return CPGeneratedIdentity(
                fingerprint = CPKeyInfo.bytesToHex(publicKeyRing.publicKey.fingerprint),
                secretKey = secretKeyRing.encoded,
                armoredPublicKey = armorPublicKeyRing(publicKeyRing)
            )
        } catch (e: CPCryptoError) {
            throw e
        } catch (e: Exception) {
            throw CPCryptoError.KeyGenerationFailed(e.message ?: "Unknown error")
        }
    }

    /**
     * Export any key material's public half as clean armor (no Version, no
     * Comment header). Used at generation time above and by the import flow
     * to derive the armored public key from an imported secret key.
     */
    fun exportArmoredPublicKey(keyData: ByteArray): String {
        val primary = CPKeyInfo.primaryKey(keyData)
            ?: throw CPCryptoError.MalformedKey("Key data did not parse as an OpenPGP key ring")
        val ring = publicRing(keyData)
            ?: throw CPCryptoError.MalformedKey("Could not derive a public key ring for ${CPKeyInfo.bytesToHex(primary.fingerprint)}")
        return armorPublicKeyRing(ring)
    }

    // ── Internals ──────────────────────────────────────────────────────

    private fun publicRing(keyData: ByteArray): PGPPublicKeyRing? {
        val calculator = org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator()
        try {
            val stream = org.bouncycastle.openpgp.PGPUtil.getDecoderStream(java.io.ByteArrayInputStream(keyData))
            val secret = org.bouncycastle.openpgp.PGPSecretKeyRing(stream, calculator)
            val keys = secret.secretKeys.asSequence().map { it.publicKey }.toList()
            return PGPPublicKeyRing(keys)
        } catch (e: Exception) {
            // Not a secret ring; fall through to public.
        }
        return try {
            val stream = org.bouncycastle.openpgp.PGPUtil.getDecoderStream(java.io.ByteArrayInputStream(keyData))
            PGPPublicKeyRing(stream, calculator)
        } catch (e: Exception) {
            null
        }
    }

    private fun armorPublicKeyRing(ring: PGPPublicKeyRing): String {
        val out = ByteArrayOutputStream()
        val armored = ArmoredOutputStream(out).clean()
        ring.encode(armored)
        armored.close()
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}

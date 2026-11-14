// CPCompositeKem.kt
// CarrierPonyCore
//
// The KEM behind a v6 identity's encryption subkey: ML-KEM-768 + X25519,
// OpenPGP public-key algorithm 35 (RFC 9980). BouncyCastle 1.85 ships the
// primitives (ML-KEM, X25519, SHA3-256, RFC 3394 key wrap) but not the OpenPGP
// composite framing, so the framing is built here on top of them. Ported from
// the combiner proven in PGPony Android, cut down to the one parameter set
// CarrierPony uses.
//
// Composite public key  = X25519 public (32) || ML-KEM-768 public (1184).
// Composite secret key  = X25519 secret (32) || ML-KEM-768 seed (64).
// Encapsulation yields an X25519 ephemeral public and an ML-KEM ciphertext, and
//
//   KEK = SHA3-256( mlkemShare || ecdhShare || ephemeralPublic ||
//                   recipientX25519Public || 35 ||
//                   "OpenPGPCompositeKDFv1" || 21 )
//
// with ecdhShare = X25519(ephemeralSecret, recipientX25519Public). The session
// key is RFC 3394 AES-256 key-wrapped under the KEK.
//
// SECURITY-CRITICAL. Both halves have to be broken to recover the KEK, which
// is the point of the hybrid. The byte layout of the combiner is pinned by a
// known-answer vector in CPCompositeKemTest computed independently of this code.

package com.carrierpony.core

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA3Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.engines.RFC3394WrapEngine
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import java.security.SecureRandom

object CPCompositeKem {

    /** OpenPGP public-key algorithm id for ML-KEM-768 + X25519 (RFC 9980). */
    const val ALGORITHM_ID = 35

    const val X25519_KEY_LEN = 32
    const val MLKEM768_PUB_LEN = 1184
    const val MLKEM768_CT_LEN = 1088
    /** The FIPS 203 d || z seed an ML-KEM secret key is stored as. */
    const val MLKEM_SEED_LEN = 64
    const val COMPOSITE_PUB_LEN = X25519_KEY_LEN + MLKEM768_PUB_LEN
    const val COMPOSITE_SECRET_LEN = X25519_KEY_LEN + MLKEM_SEED_LEN
    const val KEK_LEN = 32

    // KDF domain separation (RFC 9980): 21 octets.
    private val DOM_SEP = "OpenPGPCompositeKDFv1".toByteArray(Charsets.US_ASCII)

    /** A fresh composite key pair in the layouts the key packets carry. */
    class KeyPair(
        /** X25519 public (32) || ML-KEM-768 public (1184). */
        val publicKey: ByteArray,
        /** X25519 secret (32) || ML-KEM-768 seed (64). */
        val secretKey: ByteArray
    )

    /** What encapsulating to a recipient produces. */
    class Encapsulation(
        /** X25519 ephemeral public (32). */
        val ephemeralPublic: ByteArray,
        /** ML-KEM-768 ciphertext (1088). */
        val mlkemCiphertext: ByteArray,
        /** Key-encryption key (32). Wrap the session key with it. */
        val kek: ByteArray
    )

    /** Generate a composite key pair. */
    fun generateKeyPair(random: SecureRandom = SecureRandom()): KeyPair {
        val x = X25519KeyPairGenerator()
            .apply { init(X25519KeyGenerationParameters(random)) }
            .generateKeyPair()
        val xPub = (x.public as X25519PublicKeyParameters).encoded
        val xSec = (x.private as X25519PrivateKeyParameters).encoded

        val m = MLKEMKeyPairGenerator()
            .apply { init(MLKEMKeyGenerationParameters(random, MLKEMParameters.ml_kem_768)) }
            .generateKeyPair()
        val mPub = (m.public as MLKEMPublicKeyParameters).encoded
        val seed = (m.private as MLKEMPrivateKeyParameters).seed
            ?: throw CPCryptoError.KeyGenerationFailed("ML-KEM key has no seed")
        require(seed.size == MLKEM_SEED_LEN) { "ML-KEM seed must be $MLKEM_SEED_LEN bytes" }

        return KeyPair(publicKey = xPub + mPub, secretKey = xSec + seed)
    }

    /** Split a composite public key into (X25519 public, ML-KEM public). */
    fun splitPublic(compositePublic: ByteArray): Pair<ByteArray, ByteArray> {
        require(compositePublic.size == COMPOSITE_PUB_LEN) {
            "composite public key must be $COMPOSITE_PUB_LEN bytes, got ${compositePublic.size}"
        }
        return compositePublic.copyOfRange(0, X25519_KEY_LEN) to
            compositePublic.copyOfRange(X25519_KEY_LEN, COMPOSITE_PUB_LEN)
    }

    /** Split a composite secret key into (X25519 secret, ML-KEM seed). */
    fun splitSecret(compositeSecret: ByteArray): Pair<ByteArray, ByteArray> {
        require(compositeSecret.size == COMPOSITE_SECRET_LEN) {
            "composite secret key must be $COMPOSITE_SECRET_LEN bytes, got ${compositeSecret.size}"
        }
        return compositeSecret.copyOfRange(0, X25519_KEY_LEN) to
            compositeSecret.copyOfRange(X25519_KEY_LEN, COMPOSITE_SECRET_LEN)
    }

    /** The ML-KEM-768 public key a seed expands to. */
    fun mlkemPublicFromSeed(seed: ByteArray): ByteArray =
        mlkemPrivate(seed).publicKeyParameters.encoded

    /** Encapsulate to a recipient's composite public key. */
    fun encapsulate(compositePublic: ByteArray, random: SecureRandom = SecureRandom()): Encapsulation {
        val (recipientX, recipientM) = splitPublic(compositePublic)

        val eph = X25519KeyPairGenerator()
            .apply { init(X25519KeyGenerationParameters(random)) }
            .generateKeyPair()
        val ephPub = (eph.public as X25519PublicKeyParameters).encoded
        val ephSec = (eph.private as X25519PrivateKeyParameters).encoded
        val ecdhShare = x25519(ephSec, recipientX)

        val enc = MLKEMGenerator(random)
            .generateEncapsulated(MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, recipientM))

        val kek = combine(enc.secret, ecdhShare, ephPub, recipientX)
        return Encapsulation(ephPub, enc.encapsulation, kek)
    }

    /**
     * Recover the KEK from an X25519 ephemeral public and an ML-KEM ciphertext
     * with the recipient's composite secret key. [recipientX25519Public] is the
     * X25519 half of the recipient's own public key, which the KDF binds in.
     */
    fun decapsulate(
        ephemeralPublic: ByteArray,
        mlkemCiphertext: ByteArray,
        compositeSecret: ByteArray,
        recipientX25519Public: ByteArray
    ): ByteArray {
        require(ephemeralPublic.size == X25519_KEY_LEN) { "ephemeral public must be $X25519_KEY_LEN bytes" }
        require(mlkemCiphertext.size == MLKEM768_CT_LEN) { "ML-KEM ciphertext must be $MLKEM768_CT_LEN bytes" }
        require(recipientX25519Public.size == X25519_KEY_LEN) { "X25519 public must be $X25519_KEY_LEN bytes" }
        val (xSec, seed) = splitSecret(compositeSecret)
        val ecdhShare = x25519(xSec, ephemeralPublic)
        val mlkemShare = MLKEMExtractor(mlkemPrivate(seed)).extractSecret(mlkemCiphertext)
        return combine(mlkemShare, ecdhShare, ephemeralPublic, recipientX25519Public)
    }

    /** RFC 3394 AES-256 key wrap of the session key under [kek]. */
    fun wrapSessionKey(kek: ByteArray, sessionKey: ByteArray): ByteArray =
        RFC3394WrapEngine(AESEngine.newInstance()).run {
            init(true, KeyParameter(kek))
            wrap(sessionKey, 0, sessionKey.size)
        }

    /** RFC 3394 AES-256 key unwrap. Throws if the integrity check fails. */
    fun unwrapSessionKey(kek: ByteArray, wrapped: ByteArray): ByteArray =
        RFC3394WrapEngine(AESEngine.newInstance()).run {
            init(false, KeyParameter(kek))
            unwrap(wrapped, 0, wrapped.size)
        }

    /**
     * KEK = SHA3-256( mlkemShare || ecdhShare || ephemeralPublic ||
     *                 recipientX25519Public || algorithm id || DOM_SEP ||
     *                 len(DOM_SEP) ).
     * Internal so the test can pin the byte layout with a known-answer vector.
     */
    internal fun combine(
        mlkemShare: ByteArray,
        ecdhShare: ByteArray,
        ephemeralPublic: ByteArray,
        recipientX25519Public: ByteArray
    ): ByteArray {
        val d = SHA3Digest(256)
        d.update(mlkemShare, 0, mlkemShare.size)
        d.update(ecdhShare, 0, ecdhShare.size)
        d.update(ephemeralPublic, 0, ephemeralPublic.size)
        d.update(recipientX25519Public, 0, recipientX25519Public.size)
        d.update(ALGORITHM_ID.toByte())
        d.update(DOM_SEP, 0, DOM_SEP.size)
        d.update(DOM_SEP.size.toByte())
        val out = ByteArray(KEK_LEN)
        d.doFinal(out, 0)
        return out
    }

    private fun mlkemPrivate(seed: ByteArray): MLKEMPrivateKeyParameters {
        require(seed.size == MLKEM_SEED_LEN) { "ML-KEM seed must be $MLKEM_SEED_LEN bytes" }
        return MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, seed)
    }

    /** X25519(secret, peer public). An all-zero share (a low-order peer point) is refused. */
    private fun x25519(secret: ByteArray, peerPublic: ByteArray): ByteArray {
        val agreement = X25519Agreement().apply { init(X25519PrivateKeyParameters(secret, 0)) }
        val out = ByteArray(agreement.agreementSize)
        try {
            agreement.calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), out, 0)
        } catch (e: IllegalStateException) {
            throw CPCryptoError.DecryptionFailed("X25519 agreement failed")
        }
        return out
    }
}

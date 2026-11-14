// CryptoEngine.kt
// CarrierPony Android
//
// The crypto contract the messaging stack builds on, ported 1:1 from iOS
// Core/Crypto/CryptoEngine.swift. Fingerprint is the primary-key fingerprint:
// 40 hex characters for a v4 key (SHA-1), 64 for a v6 key (RFC 9580, SHA-256).
// It IS a contact's identity throughout the app and the relay protocol.

package com.carrierpony.app.crypto

/** An OpenPGP primary-key fingerprint in uppercase hex: 40 characters (v4) or 64 (v6). */
@ConsistentCopyVisibility
data class Fingerprint private constructor(val hex: String) {

    /** True for a v6 (RFC 9580) fingerprint, false for v4. */
    val isV6: Boolean get() = hex.length == V6_LENGTH

    companion object {
        const val V4_LENGTH = 40
        const val V6_LENGTH = 64

        /** Returns null unless the string is exactly 40 or 64 hex characters. */
        fun from(string: String): Fingerprint? {
            val upper = string.uppercase()
            val valid = (upper.length == V4_LENGTH || upper.length == V6_LENGTH) &&
                upper.all { it in '0'..'9' || it in 'A'..'F' }
            return if (valid) Fingerprint(upper) else null
        }
    }
}

/** A contact's public key: its fingerprint and the armored key material. */
data class PublicKey(
    val fingerprint: Fingerprint,
    val armored: String
)

/** A decrypted message whose inline signature verified: who sent it and what they sent. */
class VerifiedMessage(
    val sender: Fingerprint,
    val plaintext: ByteArray
)

/** The engine failures the messaging layer distinguishes, mirroring iOS CryptoError. */
sealed class CryptoException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    class NoValidSignature(detail: String? = null, cause: Throwable? = null) : CryptoException(detail, cause)
    class DecryptionFailed(detail: String? = null, cause: Throwable? = null) : CryptoException(detail, cause)
    class NotImplemented : CryptoException()
}

/**
 * Everything the app needs from an identity's cryptography. One production
 * implementation exists (PonyCryptoEngine over CarrierPonyCore); tests may
 * substitute fakes.
 */
interface CryptoEngine {

    /** The identity's own fingerprint. */
    val fingerprint: Fingerprint

    /** The identity's armored public key (shared during pairing). */
    fun armoredPublicKey(): String

    /** Armored detached signature over the data (relay challenge auth). */
    fun detachedSignature(over: ByteArray): String

    /** Verify an armored detached signature over [over] against a signer's
     *  public key. True only if it parses, was made by that key, and is valid
     *  over exactly these bytes. Used for group-message sender authentication. */
    fun verifyDetached(signature: String, over: ByteArray, signerPublicKey: PublicKey): Boolean

    /** Sign with our key, encrypt to the recipient. Returns a binary OpenPGP message. */
    fun signAndEncrypt(plaintext: ByteArray, to: PublicKey): ByteArray

    /**
     * Decrypt and verify an envelope. Throws CryptoException.NoValidSignature
     * when the sender cannot be verified, CryptoException.DecryptionFailed when
     * the message cannot be opened at all. Plaintext is never returned unverified.
     */
    fun decryptAndVerify(message: ByteArray): VerifiedMessage

    /**
     * Decrypt to our key and enforce the integrity gate, WITHOUT verifying the
     * signer. Returns the raw plaintext. Only for self-authenticating control
     * ops (channel-subscribe) whose payload carries the sender's own public
     * key; the caller must check fingerprint == hash(pubkey). Never use for
     * message content. Throws CryptoException.DecryptionFailed if it cannot be
     * opened to our key.
     */
    fun decryptOnly(message: ByteArray): ByteArray
}

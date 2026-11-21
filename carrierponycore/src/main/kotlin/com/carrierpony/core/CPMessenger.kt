// CPMessenger.kt
// CarrierPonyCore
//
// The three operations CarrierPony's CryptoEngine contract needs, in the
// exact flow the iOS PGPonyCryptoEngine implements:
//
//   detachedSignature   — armored detached signature over relay challenge
//                         nonces; the relay verifies it with GnuPG.
//   signAndEncrypt      — one binary OpenPGP message per recipient: PKESK for
//                         the recipient's Cv25519 subkey, SEIPD container
//                         holding one-pass-signature + literal data +
//                         signature. The signature carries the issuer
//                         fingerprint as a hashed subpacket — that is how the
//                         receiving side learns who claims to have sent it.
//   decryptAndVerify    — decrypt, read the claimed signer fingerprint from
//                         the inline signature, resolve that sender's public
//                         key through the caller's resolver, verify, and only
//                         then hand back plaintext + sender.
//
// Wire-format decisions for cross-implementation safety:
//   - No compression on send. Compression is optional in OpenPGP; an
//     uncompressed OPS + literal + signature sequence is the base case every
//     parser handles, including a from-scratch packet parser like the Swift
//     core's. BouncyCastle transparently decompresses on receive, so
//     iOS-built envelopes work regardless of what iOS chooses.
//   - SEIPDv1 (AES-256-CFB + MDC) whenever the recipient is a v4 key — the
//     live CarrierPony fleet. A v6 recipient gets SEIPDv2 (AEAD/OCB),
//     mirroring PGPony Android's all-v6 gate.
//   - Signature version follows the signing key automatically: the two-arg
//     PGPSignatureGenerator(builder, publicKey) constructor makes
//     BouncyCastle emit a v6 signature for a v6 key and a v4 signature for a
//     v4 key, so there are no separate v4/v6 entry points here — the version
//     routing the Swift core does by hand happens inside BC.
//   - Decryption requires integrity protection. A legacy unprotected packet,
//     a failed MDC, or a failed AEAD tag rejects the message and the
//     plaintext is never returned.

package com.carrierpony.core

import org.bouncycastle.bcpg.AEADAlgorithmTags
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.BCPGOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPKeyFlags
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Date

/**
 * A successfully decrypted and signature-verified message: who signed it
 * (primary fingerprint, uppercase hex, from the signature's issuer
 * fingerprint subpacket) and the recovered plaintext.
 */
data class CPVerifiedMessage(
    val senderFingerprint: String,
    val plaintext: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CPVerifiedMessage) return false
        return senderFingerprint == other.senderFingerprint && plaintext.contentEquals(other.plaintext)
    }

    override fun hashCode(): Int = 31 * senderFingerprint.hashCode() + plaintext.contentHashCode()
}

object CPMessenger {

    // ── Detached signature (relay challenge auth) ──────────────────────

    /**
     * Produce an armored detached signature over the given bytes with the
     * identity's signing key. v4 identities yield a v4 EdDSA-legacy
     * signature (verifiable by the relay's GnuPG); a v6 identity yields a v6
     * (RFC 9580) signature via BouncyCastle's automatic version routing.
     */
    fun detachedSignature(
        data: ByteArray,
        secretKey: ByteArray,
        passphrase: String? = null
    ): String {
        try {
            val ring = loadSecretRing(secretKey)
            val signingKey = pickSigningSecretKey(ring)
                ?: throw CPCryptoError.MalformedKey("No signing key in identity")
            val privateKey = unlockPrivateKey(signingKey, passphrase)

            val generator = PGPSignatureGenerator(
                BcPGPContentSignerBuilder(signingKey.publicKey.algorithm, HashAlgorithmTags.SHA256),
                signingKey.publicKey
            )
            generator.init(PGPSignature.BINARY_DOCUMENT, privateKey)

            val subpackets = PGPSignatureSubpacketGenerator()
            subpackets.setIssuerFingerprint(false, signingKey.publicKey)
            generator.setHashedSubpackets(subpackets.generate())

            generator.update(data)
            val signature = generator.generate()

            val out = ByteArrayOutputStream()
            val armored = ArmoredOutputStream(out).clean()
            val bcpgOut = BCPGOutputStream(armored)
            signature.encode(bcpgOut)
            bcpgOut.close()
            armored.close()
            return String(out.toByteArray(), Charsets.UTF_8)
        } catch (e: CPCryptoError) {
            throw e
        } catch (e: Exception) {
            throw CPCryptoError.SigningFailed(e.message ?: "Unknown error")
        }
    }

    /**
     * Verify an armored detached signature over [data] against the signer's
     * armored public key. Returns true only when the signature parses, was made
     * by a key in that ring, and is valid over exactly these bytes; any parse or
     * verification failure returns false. Added for group-message sender
     * authentication (the Android twin of the iOS core's verifyDetachedEd25519).
     */
    fun verifyDetached(
        armoredSignature: String,
        data: ByteArray,
        signerArmoredPublicKey: String
    ): Boolean {
        return try {
            val sigBytes = CPArmor.dearmor(armoredSignature) ?: return false
            val factory = BcPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(sigBytes)))
            val sigList = factory.nextObject() as? PGPSignatureList ?: return false
            if (sigList.isEmpty) return false
            val signature = sigList[0]
            val ringBytes = CPArmor.dearmor(signerArmoredPublicKey) ?: return false
            val ring = loadPublicRing(ringBytes)
            // Resolve by the signature's issuer key ID, then fall back to the ring's
            // primary key. A v4 CarrierPony identity signs with its primary key, and
            // a producer whose issuer-keyID subpacket the strict lookup won't match
            // would otherwise fail to verify even though the signature is valid.
            val pub = ring.getPublicKey(signature.keyID) ?: ring.publicKey ?: return false
            if (!CPKeyTrust.isBoundSigner(ring, pub)) return false
            signature.init(BcPGPContentVerifierBuilderProvider(), pub)
            signature.update(data)
            signature.verify()
        } catch (e: Exception) {
            false
        }
    }

    // ── Sign and encrypt ───────────────────────────────────────────────

    /**
     * Sign the plaintext with the identity's signing key and encrypt it to
     * the recipient's encryption subkey. Returns a binary (unarmored)
     * OpenPGP message, matching the iOS engine's armor:false behavior — the
     * relay carries it base64-encoded.
     */
    fun signAndEncrypt(
        plaintext: ByteArray,
        secretKey: ByteArray,
        passphrase: String? = null,
        recipientKey: ByteArray
    ): ByteArray {
        try {
            val ring = loadSecretRing(secretKey)
            val signingKey = pickSigningSecretKey(ring)
                ?: throw CPCryptoError.MalformedKey("No signing key in identity")
            val privateKey = unlockPrivateKey(signingKey, passphrase)

            val recipientRing = loadPublicRing(recipientKey)
            // A v6 identity's encryption subkey is ML-KEM-768 + X25519 (algorithm
            // 35), which BouncyCastle does not know. When the recipient has one
            // it is always used, so the post-quantum part is never left out.
            val compositeKey = CPCompositeKeys.encryptionSubkey(recipientRing)
            // The recipient is pinned by their primary key. A subkey that the
            // primary has not signed is not theirs, whoever handed us the ring.
            // No fallback to another subkey here: that would be a quiet downgrade.
            if (compositeKey != null && !CPKeyTrust.isBound(recipientRing, compositeKey)) {
                throw CPCryptoError.MalformedKey("Recipient's encryption subkey is not signed by their key")
            }
            val keyEncryption: org.bouncycastle.openpgp.operator.PGPKeyEncryptionMethodGenerator =
                if (compositeKey != null) {
                    CPCompositeMessage.PkeskGenerator(compositeKey)
                } else {
                    val classicalKey = findEncryptionKey(recipientRing)
                        ?: throw CPCryptoError.MalformedKey(
                            "No encryption subkey for ${CPKeyInfo.bytesToHex(recipientRing.publicKey.fingerprint)}"
                        )
                    BcPublicKeyKeyEncryptionMethodGenerator(classicalKey)
                }

            // Container version by recipient key version: SEIPDv2 (AEAD/OCB)
            // only when the recipient is v6; SEIPDv1 + MDC for the v4 fleet.
            val encBuilder = BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
            val recipientIsV6 = recipientRing.publicKey.version == 6
            val configured = if (recipientIsV6) {
                encBuilder
                    .setWithAEAD(AEADAlgorithmTags.OCB, 6)
                    .setUseV6AEAD()
                    .setSecureRandom(SecureRandom())
            } else {
                encBuilder
                    .setWithIntegrityPacket(true)
                    .setSecureRandom(SecureRandom())
            }

            val encryptedGen = PGPEncryptedDataGenerator(configured)
            encryptedGen.addMethod(keyEncryption)

            val out = ByteArrayOutputStream()
            val encryptedOut = encryptedGen.open(out, ByteArray(1 shl 16))

            // One-pass signature header. No compression layer — see the wire
            // format notes at the top of this file.
            val sigGen = PGPSignatureGenerator(
                BcPGPContentSignerBuilder(signingKey.publicKey.algorithm, HashAlgorithmTags.SHA256),
                signingKey.publicKey
            )
            sigGen.init(PGPSignature.BINARY_DOCUMENT, privateKey)
            val subpackets = PGPSignatureSubpacketGenerator()
            subpackets.setIssuerFingerprint(false, signingKey.publicKey)
            sigGen.setHashedSubpackets(subpackets.generate())
            sigGen.generateOnePassVersion(false).encode(encryptedOut)

            // Literal data — the CPN1 container is opaque binary.
            val literalGen = PGPLiteralDataGenerator()
            val literalOut = literalGen.open(
                encryptedOut,
                PGPLiteralData.BINARY,
                "",
                plaintext.size.toLong(),
                Date()
            )
            literalOut.write(plaintext)
            literalGen.close()

            // Trailing signature over the same bytes.
            sigGen.update(plaintext)
            sigGen.generate().encode(encryptedOut)

            encryptedOut.close()
            encryptedGen.close()
            return out.toByteArray()
        } catch (e: CPCryptoError) {
            throw e
        } catch (e: Exception) {
            throw CPCryptoError.EncryptionFailed(e.message ?: "Unknown error")
        }
    }

    // ── Decrypt and verify ─────────────────────────────────────────────

    /**
     * Decrypt an envelope with the identity's encryption key, extract the
     * claimed signer fingerprint from the inline signature's issuer
     * fingerprint subpacket, resolve that fingerprint to key material via
     * [senderPublicKey], and verify the signature. Fails with
     * NoValidSignature if the message is unsigned, the fingerprint is
     * missing, the sender is unknown to the resolver, or the signature does
     * not verify — plaintext is never returned in any of those cases.
     */
    fun decryptAndVerify(
        message: ByteArray,
        secretKey: ByteArray,
        passphrase: String? = null,
        senderPublicKey: (String) -> ByteArray?
    ): CPVerifiedMessage {
        try {
            val ring = loadSecretRing(secretKey)
            val opened = openEnvelope(message, ring, passphrase)
            val encryptedData = opened.integrity
            val clearStream = opened.stream
            val contents = readSignedContents(clearStream)

            // INTEGRITY GATE — after the plaintext stream is fully consumed.
            // Legacy unprotected packets are rejected outright; SEIPDv1's MDC
            // (and SEIPDv2's AEAD tag) must verify.
            val isProtected = encryptedData.isIntegrityProtected
            val intact = isProtected && try {
                encryptedData.verify()
            } catch (e: PGPException) {
                false
            }
            if (!intact) {
                throw CPCryptoError.IntegrityCheckFailed(
                    if (!isProtected) "Message has no integrity protection and was rejected"
                    else "Integrity check failed - the message may have been tampered with"
                )
            }

            val signature = contents.signature
                ?: throw CPCryptoError.NoValidSignature("Message carries no signature")
            val claimedFingerprint = signature.hashedSubPackets?.issuerFingerprint
                ?.let { CPKeyInfo.bytesToHex(it.fingerprint) }
                ?: throw CPCryptoError.NoValidSignature("Signature has no issuer fingerprint")

            val senderKeyData = senderPublicKey(claimedFingerprint)
                ?: throw CPCryptoError.NoValidSignature("Unknown sender $claimedFingerprint")
            val senderRing = loadPublicRing(senderKeyData)
            val verificationKey = senderRing.getPublicKey(signature.keyID)
                ?: throw CPCryptoError.NoValidSignature("Sender key does not contain the signing key")
            if (!CPKeyTrust.isBoundSigner(senderRing, verificationKey)) {
                throw CPCryptoError.NoValidSignature("Signing key is not bound to the sender's key")
            }

            signature.init(BcPGPContentVerifierBuilderProvider(), verificationKey)
            signature.update(contents.literal)
            if (!signature.verify()) {
                throw CPCryptoError.NoValidSignature("Signature verification failed")
            }

            // The sender is the identity, named by its primary key, also when a
            // signing subkey made the signature (an imported v6 key). For a key
            // that signs with its primary the two are the same.
            return CPVerifiedMessage(CPKeyInfo.bytesToHex(senderRing.publicKey.fingerprint), contents.literal)
        } catch (e: CPCryptoError) {
            throw e
        } catch (e: Exception) {
            throw CPCryptoError.DecryptionFailed(e.message ?: "Unknown error")
        }
    }

    /**
     * Decrypt a message to our key and enforce the SEIPD integrity gate (MDC /
     * AEAD), but do NOT resolve or verify the signer. Returns the literal
     * plaintext only.
     *
     * This exists solely for self-authenticating control ops — specifically a
     * channel-subscribe from a stranger, whose payload carries the sender's own
     * public key and whose identity is the fingerprint of that key. The caller
     * MUST check that the enclosed public key hashes to the claimed fingerprint
     * before trusting anything in the payload. Never use this for message
     * content or any op that grants a privilege: those require a verified
     * signer via decryptAndVerify, which never returns unverified plaintext.
     */
    fun decryptOnly(
        message: ByteArray,
        secretKey: ByteArray,
        passphrase: String? = null
    ): ByteArray {
        try {
            val ring = loadSecretRing(secretKey)
            val opened = openEnvelope(message, ring, passphrase)
            val encryptedData = opened.integrity
            val clearStream = opened.stream
            val contents = readSignedContents(clearStream)

            val isProtected = encryptedData.isIntegrityProtected
            val intact = isProtected && try {
                encryptedData.verify()
            } catch (e: PGPException) {
                false
            }
            if (!intact) {
                throw CPCryptoError.IntegrityCheckFailed(
                    if (!isProtected) "Message has no integrity protection and was rejected"
                    else "Integrity check failed - the message may have been tampered with"
                )
            }

            return contents.literal
        } catch (e: CPCryptoError) {
            throw e
        } catch (e: Exception) {
            throw CPCryptoError.DecryptionFailed(e.message ?: "Unknown error")
        }
    }

    // ── Opening the envelope ───────────────────────────────────────────

    private class OpenedEnvelope(val stream: java.io.InputStream, val integrity: org.bouncycastle.openpgp.PGPEncryptedData)

    /**
     * Recover the plaintext packet stream of [message] with the identity's
     * encryption key. A message addressed to an ML-KEM-768 + X25519 subkey
     * (algorithm 35) is opened by CPCompositeMessage, because BouncyCastle's
     * reader throws on that session-key packet; everything else goes through
     * BouncyCastle as before.
     */
    private fun openEnvelope(message: ByteArray, ring: PGPSecretKeyRing, passphrase: String?): OpenedEnvelope {
        CPCompositeMessage.open(message, ring, passphrase)?.let { return OpenedEnvelope(it.stream, it.integrity) }

        val factory = BcPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(message)))
        val encryptedList = findEncryptedDataList(factory)
            ?: throw CPCryptoError.DecryptionFailed("No encrypted data found in message")

        // First PKESK we hold the key for.
        for (obj in encryptedList.encryptedDataObjects) {
            if (obj !is PGPPublicKeyEncryptedData) continue
            val candidate = ring.getSecretKey(obj.keyID) ?: continue
            val privateKey = unlockPrivateKey(candidate, passphrase)
            return OpenedEnvelope(obj.getDataStream(BcPublicKeyDataDecryptorFactory(privateKey)), obj)
        }
        throw CPCryptoError.DecryptionFailed("No matching decryption key found")
    }

    // ── Inner-packet walk ──────────────────────────────────────────────

    private class SignedContents(val literal: ByteArray, val signature: PGPSignature?)

    /** The most a message's trailing signature packets may take. One signature is about 120 octets. */
    private const val MAX_TRAILING_SIGNATURE_BYTES = 1 shl 16

    /**
     * Walk the decrypted packet sequence: an optional compression layer
     * (recursed into, since iOS or other producers may compress even though
     * this core never does), an optional one-pass-signature list, the literal
     * data, and the trailing signature.
     *
     * BouncyCastle reads everything up to and including the literal data. What
     * follows it, the trailing signature, is read here as raw octets and passed
     * through CPSignatureRepair before BouncyCastle parses it, because an older
     * iOS build mislabels about one signature in 128 and BouncyCastle cannot
     * read those as written. Every stream is then read to its end, so the
     * integrity check that follows has seen the whole message; an error while
     * reading is never swallowed.
     *
     * A signature placed before the literal data is used only when there is no
     * trailing one.
     */
    private fun readSignedContents(clearStream: java.io.InputStream): SignedContents {
        val streams = mutableListOf(clearStream)
        var factory = BcPGPObjectFactory(clearStream)
        var literal: ByteArray? = null
        var leadingSignature: PGPSignature? = null

        while (literal == null) {
            val obj = factory.nextObject() ?: break
            when (obj) {
                is PGPCompressedData -> {
                    val inner = obj.dataStream
                    streams.add(inner)
                    factory = BcPGPObjectFactory(inner)
                }
                is PGPOnePassSignatureList -> {
                    // Noted and skipped: the trailing signature carries
                    // everything verification needs.
                }
                is PGPSignatureList -> {
                    if (!obj.isEmpty) leadingSignature = obj[0]
                }
                is PGPLiteralData -> {
                    literal = obj.inputStream.readBytes()
                }
            }
        }
        val bytes = literal ?: throw CPCryptoError.DecryptionFailed("No literal data found")

        // The rest of the innermost stream is the trailing signature.
        val rest = readToEnd(streams.last(), MAX_TRAILING_SIGNATURE_BYTES)
        // Finish any outer stream too.
        for (index in streams.size - 2 downTo 0) readToEnd(streams[index], MAX_TRAILING_SIGNATURE_BYTES)

        val trailingSignature = if (rest.isEmpty()) null else try {
            val list = BcPGPObjectFactory(CPSignatureRepair.normalize(rest)).nextObject() as? PGPSignatureList
            if (list == null || list.isEmpty) null else list[0]
        } catch (e: Exception) {
            null
        }
        return SignedContents(bytes, trailingSignature ?: leadingSignature)
    }

    /** Read [stream] to its end. More than [limit] octets is not a message this core produces. */
    private fun readToEnd(stream: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > limit) throw CPCryptoError.DecryptionFailed("Unexpected data after the message")
        }
        return out.toByteArray()
    }

    // ── Key loading and selection ──────────────────────────────────────

    // Both loaders pass the key through CPSignatureRepair first, so a self
    // signature or subkey binding that an older iOS build mislabelled reads
    // correctly here and everywhere downstream.

    internal fun loadSecretRing(secretKey: ByteArray): PGPSecretKeyRing {
        return try {
            PGPSecretKeyRing(ByteArrayInputStream(binaryKey(secretKey)), BcKeyFingerprintCalculator())
        } catch (e: Exception) {
            throw CPCryptoError.MalformedKey("Secret key did not parse: ${e.message}")
        }
    }

    internal fun loadPublicRing(publicKey: ByteArray): PGPPublicKeyRing {
        return try {
            PGPPublicKeyRing(ByteArrayInputStream(binaryKey(publicKey)), BcKeyFingerprintCalculator())
        } catch (e: Exception) {
            throw CPCryptoError.MalformedKey("Public key did not parse: ${e.message}")
        }
    }

    /** Key material, armored or binary, as binary packets with mislabelled signatures repaired. */
    private fun binaryKey(keyData: ByteArray): ByteArray =
        CPSignatureRepair.normalize(PGPUtil.getDecoderStream(ByteArrayInputStream(keyData)).readBytes())

    /**
     * The signing key of an identity: prefer a signing-capable subkey (the
     * v6 layout), else the primary when it is signing-capable (the v4
     * layout). Mirrors the version routing the Swift core does explicitly.
     */
    internal fun pickSigningSecretKey(ring: PGPSecretKeyRing): PGPSecretKey? {
        var master: PGPSecretKey? = null
        for (candidate in ring.secretKeys) {
            val pub = candidate.publicKey
            if (!isSigningAlgorithm(pub.algorithm)) continue
            if (pub.isMasterKey) {
                if (master == null) master = candidate
            } else if (hasKeyFlag(pub, PGPKeyFlags.CAN_SIGN)) {
                return candidate
            }
        }
        return master
    }

    /** The recipient's encryption key: prefer an encryption subkey, else an
     *  encryption-capable primary. A subkey counts only when the primary key
     *  has signed its binding (CPKeyTrust), so a subkey slipped into the ring
     *  by whoever delivered it is never chosen. */
    internal fun findEncryptionKey(ring: PGPPublicKeyRing): PGPPublicKey? {
        var primaryCandidate: PGPPublicKey? = null
        for (key in ring.publicKeys) {
            if (!key.isEncryptionKey) continue
            if (!key.isMasterKey) {
                if (CPKeyTrust.isBound(ring, key)) return key
                continue
            }
            if (primaryCandidate == null) primaryCandidate = key
        }
        return primaryCandidate
    }

    private fun isSigningAlgorithm(algorithm: Int): Boolean = when (algorithm) {
        PublicKeyAlgorithmTags.EDDSA_LEGACY,
        PublicKeyAlgorithmTags.Ed25519,
        PublicKeyAlgorithmTags.Ed448,
        PublicKeyAlgorithmTags.ECDSA,
        PublicKeyAlgorithmTags.DSA,
        PublicKeyAlgorithmTags.RSA_GENERAL,
        PublicKeyAlgorithmTags.RSA_SIGN -> true
        else -> false
    }

    private fun hasKeyFlag(key: PGPPublicKey, flag: Int): Boolean {
        val signatures = key.signatures
        while (signatures.hasNext()) {
            val sig = signatures.next() as? PGPSignature ?: continue
            val flags = sig.hashedSubPackets?.keyFlags ?: continue
            if (flags and flag != 0) return true
        }
        return false
    }

    /**
     * Unlock a secret key's private half, translating BouncyCastle's generic
     * checksum failure into the typed passphrase errors: a key whose
     * s2KUsage is 0 stores its material unencrypted, so a failure there
     * cannot be passphrase-related.
     */
    private fun unlockPrivateKey(secretKey: PGPSecretKey, passphrase: String?): PGPPrivateKey {
        // Unlocking a protected key runs its passphrase KDF. That ran for every
        // message opened and every relay login signed, and the relay is polled
        // every few seconds, so the unlocked key is kept for the process. What
        // is held is no more than the passphrase the app already holds would
        // give. An unprotected key costs nothing to read and is not kept.
        val cacheKey = if (secretKey.s2KUsage.toInt() != 0) unlockedKeyName(secretKey, passphrase) else null
        if (cacheKey != null) unlockedKeys[cacheKey]?.let { return it }
        val unlocked = extractPrivateKey(secretKey, passphrase)
        if (cacheKey != null) unlockedKeys[cacheKey] = unlocked
        return unlocked
    }

    private val unlockedKeys = java.util.concurrent.ConcurrentHashMap<String, PGPPrivateKey>()

    /** Drop every unlocked private key held in memory. The app calls this when
     *  it locks, next to CPCompositeKeys.forgetUnlocked(). */
    fun forgetUnlocked() {
        unlockedKeys.clear()
    }

    /** Names one key under one passphrase, so a changed passphrase never finds
     *  a key unlocked under the old one. */
    private fun unlockedKeyName(secretKey: PGPSecretKey, passphrase: String?): String {
        val hex = { bytes: ByteArray -> bytes.joinToString("") { "%02x".format(it) } }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((passphrase ?: "").toByteArray(Charsets.UTF_8))
        return hex(secretKey.publicKey.fingerprint) + ":" + hex(digest)
    }

    private fun extractPrivateKey(secretKey: PGPSecretKey, passphrase: String?): PGPPrivateKey {
        return try {
            secretKey.extractPrivateKey(
                BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider())
                    .build((passphrase ?: "").toCharArray())
            )
        } catch (e: PGPException) {
            if (secretKey.s2KUsage.toInt() != 0) {
                if (passphrase.isNullOrEmpty()) throw CPCryptoError.PassphraseRequired()
                throw CPCryptoError.InvalidPassphrase()
            }
            throw CPCryptoError.MalformedKey(e.message ?: "Failed to unlock secret key")
        }
    }

    private fun findEncryptedDataList(factory: BcPGPObjectFactory): PGPEncryptedDataList? {
        var obj = factory.nextObject()
        while (obj != null) {
            if (obj is PGPEncryptedDataList) return obj
            obj = factory.nextObject()
        }
        return null
    }
}

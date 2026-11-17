// PonyCryptoEngine.kt
// CarrierPony Android
//
// The production CryptoEngine: a thin adapter binding one identity's key
// material to CarrierPonyCore's CPMessenger, the Android twin of iOS
// PGPonyCryptoEngine. The publicKeyResolver is how decryptAndVerify turns a
// claimed signer fingerprint into key material — in the app it is backed by
// the ContactStore, plus the identity's own key so self-copies from other
// devices verify. The passphrase is a provider (not a fixed value) because a
// protected imported identity's passphrase lives behind the app lock and can
// change per session; generated identities supply null.

package com.carrierpony.app.crypto

import com.carrierpony.core.CPArmor
import com.carrierpony.core.CPCryptoError
import com.carrierpony.core.CPKeyInfo
import com.carrierpony.core.CPMessenger
import java.util.concurrent.ConcurrentHashMap

class PonyCryptoEngine(
    override val fingerprint: Fingerprint,
    private val secretKey: ByteArray,
    private val armored: String,
    private val passphrase: () -> String? = { null },
    /** Every key a message may be signed by: the contacts and the identity's
     *  own key. Looked through only when a signature names a key that is not
     *  an identity's primary key, which is what an imported identity with a
     *  signing subkey produces. */
    private val signerKeys: () -> List<PublicKey> = { emptyList() },
    private val publicKeyResolver: (Fingerprint) -> PublicKey?
) : CryptoEngine {

    /** The key fingerprints inside each public key seen so far, so a ring is
     *  parsed once and not for every message. */
    private val ringKeys = ConcurrentHashMap<String, Set<String>>()

    private fun keysOf(key: PublicKey): Set<String> =
        ringKeys.getOrPut(key.fingerprint.hex + ":" + key.armored.hashCode()) {
            CPKeyInfo.keyFingerprints(key.armored.toByteArray(Charsets.UTF_8)).toSet()
        }

    /** The identity whose key ring holds the key a signature names. The core
     *  still checks that the key is a properly bound signing subkey. */
    private fun ownerOfSubkey(claimedHex: String): PublicKey? {
        val wanted = claimedHex.uppercase()
        return signerKeys().firstOrNull { wanted in keysOf(it) }
    }

    override fun armoredPublicKey(): String = armored

    override fun detachedSignature(over: ByteArray): String =
        CPMessenger.detachedSignature(over, secretKey, passphrase())

    override fun verifyDetached(signature: String, over: ByteArray, signerPublicKey: PublicKey): Boolean =
        CPMessenger.verifyDetached(signature, over, signerPublicKey.armored)

    override fun signAndEncrypt(plaintext: ByteArray, to: PublicKey): ByteArray {
        val recipientKey = CPArmor.dearmor(to.armored)
            ?: throw CPCryptoError.MalformedKey("Recipient key for ${to.fingerprint.hex} did not dearmor")
        return CPMessenger.signAndEncrypt(plaintext, secretKey, passphrase(), recipientKey)
    }

    override fun decryptAndVerify(message: ByteArray): VerifiedMessage {
        val verified = try {
            CPMessenger.decryptAndVerify(message, secretKey, passphrase()) { claimedHex ->
                (Fingerprint.from(claimedHex)?.let(publicKeyResolver) ?: ownerOfSubkey(claimedHex))
                    ?.let { CPArmor.dearmor(it.armored) }
            }
        } catch (e: CPCryptoError.NoValidSignature) {
            throw CryptoException.NoValidSignature(e.message, e)
        } catch (e: CPCryptoError) {
            // The message names the failing layer (PassphraseRequired vs
            // InvalidPassphrase vs no-matching-key vs integrity) and whether a
            // passphrase was available at call time — never its value.
            val pass = passphrase()
            val state = if (pass == null) "absent" else "present(len=" + pass.length + ")"
            throw CryptoException.DecryptionFailed(e.message + " [passphrase " + state + "]", e)
        }
        // A sender fingerprint that is neither 40 hex (v4) nor 64 hex (v6) is
        // not a usable identity. Refuse, matching iOS.
        val sender = Fingerprint.from(verified.senderFingerprint)
            ?: throw CryptoException.NoValidSignature()
        return VerifiedMessage(sender, verified.plaintext)
    }

    override fun decryptAndVerify(message: ByteArray, signer: PublicKey): VerifiedMessage {
        val verified = try {
            CPMessenger.decryptAndVerify(message, secretKey, passphrase()) { claimedHex ->
                // The signer's primary key, or a signing subkey in its ring.
                if (Fingerprint.from(claimedHex) == signer.fingerprint || claimedHex.uppercase() in keysOf(signer)) {
                    CPArmor.dearmor(signer.armored)
                } else {
                    null
                }
            }
        } catch (e: CPCryptoError.NoValidSignature) {
            throw CryptoException.NoValidSignature(e.message, e)
        } catch (e: CPCryptoError) {
            throw CryptoException.DecryptionFailed(e.message, e)
        }
        val sender = Fingerprint.from(verified.senderFingerprint)
        if (sender == null || sender != signer.fingerprint) throw CryptoException.NoValidSignature()
        return VerifiedMessage(sender, verified.plaintext)
    }

    override fun decryptOnly(message: ByteArray): ByteArray =
        try {
            CPMessenger.decryptOnly(message, secretKey, passphrase())
        } catch (e: CPCryptoError) {
            throw CryptoException.DecryptionFailed(e.message, e)
        }
}

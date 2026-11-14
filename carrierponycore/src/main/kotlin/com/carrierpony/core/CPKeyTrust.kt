// CPKeyTrust.kt
// CarrierPonyCore
//
// Whether a subkey really belongs to the key it travels with.
//
// A contact is pinned by the fingerprint of their PRIMARY key. The key ring
// that carries it also carries subkeys, and BouncyCastle does not check a
// subkey's binding signature when it parses a ring. Without a check here, a
// ring with the right primary and someone else's subkey would be accepted: the
// fingerprint still matches, and messages would be encrypted to a key the
// contact does not hold. The relay hands out contact keys at pairing, so that
// "someone else" could be the relay.
//
// A subkey is accepted only when the primary key has signed a subkey binding
// over it and has not revoked it. A subkey used to SIGN must also carry the
// back-signature (primary key binding, made by the subkey itself) inside that
// binding, which proves the subkey's holder agreed to be bound to this primary.
// The primary key itself needs no binding: it is the pinned key.

package com.carrierpony.core

import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureSubpacketVector
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider

object CPKeyTrust {

    /**
     * True when [key] is the primary key of [ring], or a subkey that the
     * primary has bound and not revoked. Use this before encrypting to a key.
     */
    fun isBound(ring: PGPPublicKeyRing, key: PGPPublicKey): Boolean {
        val primary = ring.publicKey ?: return false
        if (key.isMasterKey) return key.fingerprint.contentEquals(primary.fingerprint)
        if (isRevoked(primary, key)) return false
        return validBindings(primary, key).isNotEmpty()
    }

    /**
     * True when [key] may sign on behalf of [ring]'s primary key: it is the
     * primary, or a bound subkey whose binding carries a valid back-signature.
     * Use this before trusting a signature.
     */
    fun isBoundSigner(ring: PGPPublicKeyRing, key: PGPPublicKey): Boolean {
        val primary = ring.publicKey ?: return false
        if (key.isMasterKey) return key.fingerprint.contentEquals(primary.fingerprint)
        if (isRevoked(primary, key)) return false
        return validBindings(primary, key).any { hasValidBackSignature(it, primary, key) }
    }

    // ── Internals ──────────────────────────────────────────────────────

    /** The subkey binding signatures on [subkey] that verify under [primary]. */
    private fun validBindings(primary: PGPPublicKey, subkey: PGPPublicKey): List<PGPSignature> {
        val valid = mutableListOf<PGPSignature>()
        val bindings = subkey.getSignaturesOfType(PGPSignature.SUBKEY_BINDING)
        while (bindings.hasNext()) {
            val signature = bindings.next()
            val ok = try {
                signature.init(BcPGPContentVerifierBuilderProvider(), primary)
                signature.verifyCertification(primary, subkey)
            } catch (e: Exception) {
                false
            }
            if (ok) valid.add(signature)
        }
        return valid
    }

    /** True when [primary] has validly revoked [subkey]. */
    private fun isRevoked(primary: PGPPublicKey, subkey: PGPPublicKey): Boolean {
        val revocations = subkey.getSignaturesOfType(PGPSignature.SUBKEY_REVOCATION)
        while (revocations.hasNext()) {
            val signature = revocations.next()
            val ok = try {
                signature.init(BcPGPContentVerifierBuilderProvider(), primary)
                signature.verifyCertification(primary, subkey)
            } catch (e: Exception) {
                false
            }
            if (ok) return true
        }
        return false
    }

    /**
     * True when [binding] embeds a primary key binding signature made by
     * [subkey] over the same pair of keys. Implementations put the embedded
     * signature in either the hashed or the unhashed area, so both are read.
     */
    private fun hasValidBackSignature(binding: PGPSignature, primary: PGPPublicKey, subkey: PGPPublicKey): Boolean {
        for (area in listOf(binding.hashedSubPackets, binding.unhashedSubPackets)) {
            for (embedded in embeddedSignatures(area)) {
                if (embedded.signatureType != PGPSignature.PRIMARYKEY_BINDING) continue
                val ok = try {
                    embedded.init(BcPGPContentVerifierBuilderProvider(), subkey)
                    embedded.verifyCertification(primary, subkey)
                } catch (e: Exception) {
                    false
                }
                if (ok) return true
            }
        }
        return false
    }

    private fun embeddedSignatures(area: PGPSignatureSubpacketVector?): List<PGPSignature> {
        if (area == null) return emptyList()
        return try {
            val list = area.embeddedSignatures ?: return emptyList()
            (0 until list.size()).map { list[it] }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

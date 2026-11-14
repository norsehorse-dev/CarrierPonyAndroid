// CPV6IdentityTest.kt
// CarrierPonyCore
//
// A generated v6 identity: an Ed25519 primary that certifies and signs, and an
// ML-KEM-768 + X25519 encryption subkey (algorithm 35). Checks the shape, that
// BouncyCastle verifies the hand-emitted subkey's binding, that the stored key
// material drives the KEM, and that the identity signs a relay challenge. The
// last test also writes a request file for the relay's cp-verify, so the Go
// verifier can be run against a key this core made.

package com.carrierpony.core

import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPKeyFlags
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class CPV6IdentityTest {

    private fun generate() = CPIdentityGenerator.generateV6Identity("Test Pony", "test@carrierpony.com")

    private fun publicRing(identity: CPGeneratedIdentity): PGPPublicKeyRing =
        CPMessenger.loadPublicRing(CPArmor.dearmor(identity.armoredPublicKey)!!)

    @Test
    fun fingerprintIs64HexAndTheKeyIsV6() {
        val identity = generate()
        assertEquals(64, identity.fingerprint.length)
        assertTrue(identity.fingerprint.all { it in '0'..'9' || it in 'A'..'F' })
        assertEquals(6, CPKeyInfo.keyVersion(identity.secretKey))
        assertEquals(identity.fingerprint, CPKeyInfo.primaryFingerprint(identity.secretKey))
        assertEquals(identity.fingerprint, CPKeyInfo.primaryFingerprint(identity.armoredPublicKey))
        assertEquals("Test Pony", CPKeyInfo.userIDName(identity.secretKey))
    }

    @Test
    fun twoIdentitiesDiffer() {
        assertFalse(generate().fingerprint == generate().fingerprint)
    }

    @Test
    fun ringIsASigningPrimaryAndACompositeSubkey() {
        val keys = publicRing(generate()).publicKeys.asSequence().toList()
        assertEquals(2, keys.size)
        assertTrue(keys[0].isMasterKey)
        assertEquals(PublicKeyAlgorithmTags.Ed25519, keys[0].algorithm)
        assertFalse(keys[1].isMasterKey)
        assertEquals(CPCompositeKem.ALGORITHM_ID, keys[1].algorithm)
        assertTrue(keys.all { it.version == 6 })
        // Nothing on the key expires.
        assertTrue(keys.all { it.validSeconds == 0L })
    }

    @Test
    fun primaryIsFlaggedToCertifyAndSign() {
        val primary = publicRing(generate()).publicKey
        val wanted = PGPKeyFlags.CAN_CERTIFY or PGPKeyFlags.CAN_SIGN
        val flagSets = primary.signatures.asSequence()
            .mapNotNull { (it as? PGPSignature)?.hashedSubPackets }
            .filter { it.hasSubpacket(org.bouncycastle.bcpg.SignatureSubpacketTags.KEY_FLAGS) }
            .map { it.keyFlags }
            .toList()
        assertTrue("the primary must carry key flags", flagSets.isNotEmpty())
        assertTrue("every self-signature must say certify + sign, got $flagSets", flagSets.all { it == wanted })
    }

    @Test
    fun compositeSubkeyBindingVerifies() {
        val ring = publicRing(generate())
        val primary = ring.publicKey
        val subkey = CPCompositeKeys.encryptionSubkey(ring)
        assertNotNull(subkey)
        val bindings = subkey!!.getSignaturesOfType(PGPSignature.SUBKEY_BINDING).asSequence().toList()
        assertEquals(1, bindings.size)
        val binding = bindings[0]
        assertEquals(6, binding.version)
        binding.init(BcPGPContentVerifierBuilderProvider(), primary)
        assertTrue(binding.verifyCertification(primary, subkey))
    }

    @Test
    fun bindingFromAnotherPrimaryDoesNotVerify() {
        val ring = publicRing(generate())
        val otherPrimary = publicRing(generate()).publicKey
        val subkey = CPCompositeKeys.encryptionSubkey(ring)!!
        val binding = subkey.getSignaturesOfType(PGPSignature.SUBKEY_BINDING).next()
        val ok = try {
            binding.init(BcPGPContentVerifierBuilderProvider(), otherPrimary)
            binding.verifyCertification(otherPrimary, subkey)
        } catch (e: Exception) {
            false
        }
        assertFalse(ok)
    }

    @Test
    fun storedKeyMaterialDrivesTheKem() {
        val identity = generate()
        val publicMaterial = CPCompositeKeys.publicMaterial(CPCompositeKeys.encryptionSubkey(publicRing(identity))!!)
        assertNotNull(publicMaterial)
        assertEquals(1216, publicMaterial!!.size)

        val secretRing = CPMessenger.loadSecretRing(identity.secretKey)
        val secretMaterial = CPCompositeKeys.secretMaterial(CPCompositeKeys.encryptionSubkey(secretRing)!!)
        assertEquals(96, secretMaterial.size)

        val (xPublic, _) = CPCompositeKem.splitPublic(publicMaterial)
        val enc = CPCompositeKem.encapsulate(publicMaterial)
        val kek = CPCompositeKem.decapsulate(enc.ephemeralPublic, enc.mlkemCiphertext, secretMaterial, xPublic)
        assertArrayEquals(enc.kek, kek)
    }

    @Test
    fun v4IdentityHasNoCompositeSubkey() {
        val v4 = CPIdentityGenerator.generateV4Identity("Test Pony", "test@carrierpony.com")
        assertNull(CPCompositeKeys.encryptionSubkey(CPMessenger.loadSecretRing(v4.secretKey)))
        assertNull(CPCompositeKeys.encryptionSubkey(CPMessenger.loadPublicRing(CPArmor.dearmor(v4.armoredPublicKey)!!)))
    }

    @Test
    fun secretMaterialRejectsAKeyThatIsNotComposite() {
        val secretRing = CPMessenger.loadSecretRing(generate().secretKey)
        try {
            CPCompositeKeys.secretMaterial(secretRing.secretKey)
            fail("the Ed25519 primary is not a composite key")
        } catch (e: CPCryptoError.MalformedKey) {
            // expected
        }
    }

    @Test
    fun exportedPublicKeyMatchesTheGeneratedOne() {
        val identity = generate()
        assertEquals(identity.armoredPublicKey, CPIdentityGenerator.exportArmoredPublicKey(identity.secretKey))
        assertFalse(identity.armoredPublicKey.contains("Version:"))
        assertFalse(identity.armoredPublicKey.contains("Comment:"))
    }

    @Test
    fun signsARelayChallengeWithThePrimaryKey() {
        val identity = generate()
        val nonce = "5b1e0c7a9f3d48e2a6c1b7d09e4f2a8c5b1e0c7a9f3d48e2a6c1b7d09e4f2a8c"
        val signature = CPMessenger.detachedSignature(nonce.toByteArray(Charsets.UTF_8), identity.secretKey)
        assertTrue(signature.contains("-----BEGIN PGP SIGNATURE-----"))
        assertTrue(CPMessenger.verifyDetached(signature, nonce.toByteArray(Charsets.UTF_8), identity.armoredPublicKey))
        assertFalse(CPMessenger.verifyDetached(signature, (nonce + "x").toByteArray(Charsets.UTF_8), identity.armoredPublicKey))

        val secretRing = CPMessenger.loadSecretRing(identity.secretKey)
        val signer = CPMessenger.pickSigningSecretKey(secretRing)
        assertNotNull(signer)
        assertTrue("v6 identities sign with the primary key", signer!!.publicKey.isMasterKey)

        // For the relay verifier: go run ./cmd/cp-verify < build/v6-login-request.json
        fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n") + "\""
        val request = "{\"pubkey\":" + json(identity.armoredPublicKey) + ",\"fpr\":" + json(identity.fingerprint) +
            ",\"data\":" + json(nonce) + ",\"sig\":" + json(signature) + "}\n"
        File("build").mkdirs()
        File("build/v6-login-request.json").writeText(request)
    }
}

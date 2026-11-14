// CPIosV6IdentityTest.kt
// CarrierPonyCore
//
// A v6 identity made by the iOS core has to be a key this core accepts and can
// use: same shape as CPIdentityGenerator's (a primary that certifies and signs,
// one ML-KEM-768 + X25519 subkey), a binding that verifies, stored key
// material that drives the KEM, and a relay challenge signature that verifies.
//
// The files under /ios were written by the iOS key generator
// (V6IdentityTests.emitV6IdentityForAndroid in the iOS project). The key
// belongs to nobody.

package com.carrierpony.core

import org.bouncycastle.bcpg.SignatureSubpacketTags
import org.bouncycastle.openpgp.PGPKeyFlags
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CPIosV6IdentityTest {

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/ios/$name")!!.use { it.readBytes() }
    private fun text(name: String): String = String(resource(name), Charsets.UTF_8)

    private val armoredPublic: String get() = text("ios-v6-public.asc")
    private val publicKey: ByteArray get() = CPArmor.dearmor(armoredPublic)!!
    private val secretKey: ByteArray get() = resource("ios-v6-secret.asc")
    private val fingerprint: String get() = text("ios-v6-fingerprint.txt").trim()
    private val ring: PGPPublicKeyRing get() = CPMessenger.loadPublicRing(publicKey)

    @Test
    fun fingerprintMatchesAndTheKeyIsV6() {
        assertEquals(64, fingerprint.length)
        assertEquals(6, ring.publicKey.version)
        assertEquals(fingerprint, CPKeyInfo.bytesToHex(ring.publicKey.fingerprint))
    }

    @Test
    fun ringIsASigningPrimaryAndACompositeSubkey() {
        val keys = ring.publicKeys.asSequence().toList()
        assertEquals(2, keys.size)
        assertTrue(keys[0].isMasterKey)
        assertEquals(27, keys[0].algorithm)
        assertEquals(35, keys[1].algorithm)
        assertEquals(6, keys[1].version)
        assertTrue(keys.all { it.validSeconds == 0L })
        assertTrue(ring.publicKey.userIDs.asSequence().toList().isNotEmpty())
    }

    @Test
    fun primaryIsFlaggedToCertifyAndSign() {
        val wanted = PGPKeyFlags.CAN_CERTIFY or PGPKeyFlags.CAN_SIGN
        val flagSets = ring.publicKey.signatures.asSequence()
            .mapNotNull { (it as? PGPSignature)?.hashedSubPackets }
            .filter { it.hasSubpacket(SignatureSubpacketTags.KEY_FLAGS) }
            .map { it.keyFlags }
            .toList()
        assertTrue("the primary must carry key flags", flagSets.isNotEmpty())
        assertTrue("every self-signature must say certify + sign, got $flagSets", flagSets.all { it == wanted })
    }

    @Test
    fun primaryAndSubkeyAreBound() {
        val ring = ring
        val subkey = CPCompositeKeys.encryptionSubkey(ring)
        assertNotNull(subkey)
        assertTrue(CPKeyTrust.isBound(ring, subkey!!))
        assertTrue(CPKeyTrust.isBound(ring, ring.publicKey))
        assertTrue(CPKeyTrust.isBoundSigner(ring, ring.publicKey))
        assertFalse("an encryption subkey may not sign", CPKeyTrust.isBoundSigner(ring, subkey))
    }

    @Test
    fun storedKeyMaterialDrivesTheKem() {
        val publicMaterial = CPCompositeKeys.publicMaterial(CPCompositeKeys.encryptionSubkey(ring)!!)
        assertNotNull(publicMaterial)
        assertEquals(1216, publicMaterial!!.size)

        val secretRing = CPMessenger.loadSecretRing(secretKey)
        val secretMaterial = CPCompositeKeys.secretMaterial(CPCompositeKeys.encryptionSubkey(secretRing)!!)
        assertEquals(96, secretMaterial.size)

        val (xPublic, mlkemPublic) = CPCompositeKem.splitPublic(publicMaterial)
        assertArrayEquals(mlkemPublic, CPCompositeKem.mlkemPublicFromSeed(CPCompositeKem.splitSecret(secretMaterial).second))
        val sent = CPCompositeKem.encapsulate(publicMaterial)
        val kek = CPCompositeKem.decapsulate(sent.ephemeralPublic, sent.mlkemCiphertext, secretMaterial, xPublic)
        assertArrayEquals(sent.kek, kek)
    }

    // What the import flow does with a secret key: derive the public key from it.
    @Test
    fun publicKeyDerivedFromTheSecretKeyIsTheSameIdentity() {
        val exported = CPIdentityGenerator.exportArmoredPublicKey(CPArmor.dearmor(text("ios-v6-secret.asc"))!!)
        val derived = CPMessenger.loadPublicRing(CPArmor.dearmor(exported)!!)
        assertEquals(fingerprint, CPKeyInfo.bytesToHex(derived.publicKey.fingerprint))
        assertEquals(fingerprint, CPKeyInfo.primaryFingerprint(secretKey))
        assertEquals(6, CPKeyInfo.keyVersion(secretKey))
        val subkey = CPCompositeKeys.encryptionSubkey(derived)
        assertNotNull(subkey)
        assertTrue(CPKeyTrust.isBound(derived, subkey!!))
        assertArrayEquals(
            CPCompositeKeys.publicMaterial(CPCompositeKeys.encryptionSubkey(ring)!!),
            CPCompositeKeys.publicMaterial(subkey)
        )
    }

    @Test
    fun relayChallengeSignatureVerifies() {
        val nonce = resource("ios-v6-login-nonce.txt")
        val signature = text("ios-v6-login-signature.asc")
        assertTrue(CPMessenger.verifyDetached(signature, nonce, armoredPublic))
        assertFalse(CPMessenger.verifyDetached(signature, nonce + 0x21.toByte(), armoredPublic))
        val other = CPIdentityGenerator.generateV6Identity("Other", "test@carrierpony.com")
        assertFalse(CPMessenger.verifyDetached(signature, nonce, other.armoredPublicKey))
    }

    // The Android core runs both ends here, using the iOS-made secret key for
    // the iOS side. It shows the key works for messages in both directions with
    // a v4 and a v6 peer. The iOS core opening and writing these messages itself
    // is the next stage.
    @Test
    fun messagesFlowBothWaysWithV4AndV6Peers() {
        val peers = listOf(
            CPIdentityGenerator.generateV4Identity("Android v4", "test@carrierpony.com"),
            CPIdentityGenerator.generateV6Identity("Android v6", "test@carrierpony.com")
        )
        for (peer in peers) {
            val peerPublic = CPArmor.dearmor(peer.armoredPublicKey)!!

            val toIos = "android to ios".toByteArray()
            val inbound = CPMessenger.signAndEncrypt(toIos, peer.secretKey, null, publicKey)
            val openedByIos = CPMessenger.decryptAndVerify(inbound, secretKey, null) { fpr ->
                if (fpr == peer.fingerprint) peerPublic else null
            }
            assertArrayEquals(toIos, openedByIos.plaintext)
            assertEquals(peer.fingerprint, openedByIos.senderFingerprint)

            val toAndroid = "ios to android".toByteArray()
            val outbound = CPMessenger.signAndEncrypt(toAndroid, secretKey, null, peerPublic)
            val openedByAndroid = CPMessenger.decryptAndVerify(outbound, peer.secretKey, null) { fpr ->
                if (fpr == fingerprint) publicKey else null
            }
            assertArrayEquals(toAndroid, openedByAndroid.plaintext)
            assertEquals(fingerprint, openedByAndroid.senderFingerprint)
        }
    }
}

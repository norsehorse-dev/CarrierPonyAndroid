// CPV6KeyImportTest.kt
// CarrierPonyCore
//
// Importing a v6 key made by another app as a CarrierPony identity. The two
// fixtures are throwaway keys exported from PGPony Android, one unprotected and
// one under the passphrase "a". Their shape is not the one this core makes: a
// certify-only Ed25519 primary, an Ed25519 signing subkey, a plain X25519
// subkey and the ML-KEM-768 + X25519 subkey. The protected one keeps its
// classic keys under CFB and its ML-KEM subkey under AEAD with Argon2.

package com.carrierpony.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CPV6KeyImportTest {

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/pgpony/$name")!!.use { it.readBytes() }

    private val unprotected: ByteArray get() = resource("pgpony-v6-unprotected-secret.asc")
    private val protectedKey: ByteArray get() = resource("pgpony-v6-protected-secret.asc")

    private val unprotectedFingerprint = "DA92AD1ADD5E10FD5135559FBD05CB8B9CD3B9AB704CBA482688AD12C3024F13"
    private val protectedFingerprint = "874AABA8729AEF776BDB8221D28B3B15EEB7FB3CCF37D866102A5EBE3C5C2F83"
    private val pass = "a"

    private fun publicBytes(secret: ByteArray): ByteArray =
        CPArmor.dearmor(CPKeyImport.transferablePublicFromSecret(secret))!!

    /** What the app's contact list does: find the key a signature names,
     *  whether that is an identity's primary key or one of its subkeys. */
    private fun resolver(vararg publicKeys: ByteArray): (String) -> ByteArray? = { fingerprint ->
        publicKeys.firstOrNull { key ->
            CPMessenger.loadPublicRing(key).publicKeys.asSequence()
                .any { CPKeyInfo.bytesToHex(it.fingerprint) == fingerprint }
        }
    }

    private fun secretRing(secret: ByteArray) = org.bouncycastle.openpgp.PGPSecretKeyRing(
        org.bouncycastle.openpgp.PGPUtil.getDecoderStream(java.io.ByteArrayInputStream(secret)),
        org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator()
    )

    // ── Inspecting ─────────────────────────────────────────────────────

    @Test
    fun unprotectedKeyNeedsNoPassphraseAndValidates() {
        assertFalse(CPKeyImport.needsPassphrase(unprotected))
        CPKeyImport.validate(unprotected, null)
    }

    @Test
    fun publicKeyAndNameComeOutOfTheSecretKey() {
        val ring = CPMessenger.loadPublicRing(publicBytes(unprotected))
        assertEquals(6, ring.publicKey.version)
        assertEquals(unprotectedFingerprint, CPKeyInfo.bytesToHex(ring.publicKey.fingerprint))
        assertEquals("carrierpony test", CPKeyImport.userIDName(unprotected))
        assertNotNull(CPCompositeKeys.encryptionSubkey(ring))
    }

    @Test
    fun importedPublicKeyIsUsableAsAContact() {
        val result = CPContactKey.inspect(CPKeyImport.transferablePublicFromSecret(protectedKey))
        assertTrue(result is CPContactKey.Result.Usable)
        assertEquals(protectedFingerprint, (result as CPContactKey.Result.Usable).fingerprint)
    }

    @Test
    fun protectedKeyNeedsItsPassphrase() {
        assertTrue(CPKeyImport.needsPassphrase(protectedKey))
        CPKeyImport.validate(protectedKey, pass)
    }

    @Test
    fun wrongOrMissingPassphraseIsRefused() {
        for (attempt in listOf<String?>("b", "", null)) {
            try {
                CPKeyImport.validate(protectedKey, attempt)
                fail("passphrase ${attempt ?: "none"} should not validate")
            } catch (e: CPKeyImportError.WrongPassphrase) {
            }
        }
    }

    // ── The ML-KEM subkey's own protection ─────────────────────────────

    @Test
    fun compositeSubkeyUnlocksWithThePassphrase() {
        val subkey = CPCompositeKeys.encryptionSubkey(secretRing(protectedKey))!!
        assertTrue(CPCompositeKeys.isProtected(subkey))
        CPCompositeKeys.forgetUnlocked()
        val first = CPCompositeKeys.secretMaterial(subkey, pass)
        assertEquals(CPCompositeKem.COMPOSITE_SECRET_LEN, first.size)
        // The second read comes from memory and must be the same bytes.
        assertArrayEquals(first, CPCompositeKeys.secretMaterial(subkey, pass))
        CPCompositeKeys.forgetUnlocked()
        assertArrayEquals(first, CPCompositeKeys.secretMaterial(subkey, pass))
    }

    @Test
    fun compositeSubkeyRefusesAWrongPassphraseEvenAfterAGoodOne() {
        val subkey = CPCompositeKeys.encryptionSubkey(secretRing(protectedKey))!!
        CPCompositeKeys.secretMaterial(subkey, pass)
        try {
            CPCompositeKeys.secretMaterial(subkey, "b")
            fail("a wrong passphrase must not be answered from memory")
        } catch (e: CPCryptoError.InvalidPassphrase) {
        }
        try {
            CPCompositeKeys.secretMaterial(subkey, null)
            fail("no passphrase must not be answered from memory")
        } catch (e: CPCryptoError.PassphraseRequired) {
        }
    }

    @Test
    fun unprotectedCompositeSubkeyIsNotMarkedProtected() {
        val subkey = CPCompositeKeys.encryptionSubkey(secretRing(unprotected))!!
        assertFalse(CPCompositeKeys.isProtected(subkey))
        assertEquals(CPCompositeKem.COMPOSITE_SECRET_LEN, CPCompositeKeys.secretMaterial(subkey).size)
    }

    // ── Messages with an imported identity ─────────────────────────────

    @Test
    fun messageToTheProtectedIdentityOpensWithItsPassphrase() {
        val friend = CPIdentityGenerator.generateV6Identity("Friend", "friend@carrierpony.com")
        val friendPublic = CPArmor.dearmor(friend.armoredPublicKey)!!
        val plaintext = "to an imported identity".toByteArray(Charsets.UTF_8)
        val envelope = CPMessenger.signAndEncrypt(plaintext, friend.secretKey, null, publicBytes(protectedKey))

        val opened = CPMessenger.decryptAndVerify(envelope, protectedKey, pass, resolver(friendPublic))
        assertArrayEquals(plaintext, opened.plaintext)
        assertEquals(friend.fingerprint, opened.senderFingerprint)

        try {
            CPMessenger.decryptAndVerify(envelope, protectedKey, null, resolver(friendPublic))
            fail("a protected identity must not open a message without its passphrase")
        } catch (e: CPCryptoError.PassphraseRequired) {
        }
    }

    @Test
    fun messageFromAnImportedIdentityNamesItsPrimaryKeyAsTheSender() {
        val friend = CPIdentityGenerator.generateV6Identity("Friend", "friend@carrierpony.com")
        val friendPublic = CPArmor.dearmor(friend.armoredPublicKey)!!
        val plaintext = "from an imported identity".toByteArray(Charsets.UTF_8)

        for ((secret, passphrase, fingerprint) in listOf(
            Triple(unprotected, null, unprotectedFingerprint),
            Triple(protectedKey, pass, protectedFingerprint)
        )) {
            // Signed by the signing subkey, since the primary only certifies.
            val envelope = CPMessenger.signAndEncrypt(plaintext, secret, passphrase, friendPublic)
            val opened = CPMessenger.decryptAndVerify(envelope, friend.secretKey, null, resolver(publicBytes(secret)))
            assertArrayEquals(plaintext, opened.plaintext)
            assertEquals("the sender is the identity, not its signing subkey", fingerprint, opened.senderFingerprint)
        }
    }

    @Test
    fun twoImportedIdentitiesTalkToEachOther() {
        val plaintext = "imported to imported".toByteArray(Charsets.UTF_8)
        val envelope = CPMessenger.signAndEncrypt(plaintext, unprotected, null, publicBytes(protectedKey))
        val opened = CPMessenger.decryptAndVerify(envelope, protectedKey, pass, resolver(publicBytes(unprotected)))
        assertArrayEquals(plaintext, opened.plaintext)
        assertEquals(unprotectedFingerprint, opened.senderFingerprint)
    }

    @Test
    fun loginSignatureFromAnImportedIdentityVerifies() {
        val nonce = "relay challenge".toByteArray(Charsets.UTF_8)
        val signature = CPMessenger.detachedSignature(nonce, protectedKey, pass)
        assertTrue(CPMessenger.verifyDetached(signature, nonce, CPKeyImport.transferablePublicFromSecret(protectedKey)))
    }

    // ── Existing shapes still import ───────────────────────────────────

    @Test
    fun generatedV6IdentityStillValidates() {
        val own = CPIdentityGenerator.generateV6Identity("Own", "own@carrierpony.com")
        assertFalse(CPKeyImport.needsPassphrase(own.secretKey))
        CPKeyImport.validate(own.secretKey, null)
    }

    @Test
    fun generatedV4IdentityStillValidates() {
        val own = CPIdentityGenerator.generateV4Identity("Own", "own@carrierpony.com")
        assertFalse(CPKeyImport.needsPassphrase(own.secretKey))
        CPKeyImport.validate(own.secretKey, null)
    }
}

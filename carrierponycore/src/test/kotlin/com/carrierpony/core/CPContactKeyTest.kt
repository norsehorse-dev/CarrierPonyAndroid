// CPContactKeyTest.kt
// CarrierPony core
//
// A public key pasted or opened from a file, to add its owner as a contact.
// Both kinds of key the apps make have to come through, however the text
// around them looks, and everything else has to be turned away with the
// right reason: a secret key, a key the app cannot encrypt to, and text that
// is not a key.

package com.carrierpony.core

import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.api.KeyPairGeneratorCallback
import org.bouncycastle.openpgp.api.OpenPGPKeyGenerator
import org.bouncycastle.openpgp.api.bc.BcOpenPGPImplementation
import org.bouncycastle.bcpg.PublicKeyPacket
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Date

class CPContactKeyTest {

    private fun v4(name: String = "Sea Biscuit") = CPIdentityGenerator.generateV4Identity(name, "test@carrierpony.com")
    private fun v6(name: String = "Sea Biscuit") = CPIdentityGenerator.generateV6Identity(name, "test@carrierpony.com")

    private fun binary(identity: CPGeneratedIdentity): ByteArray = CPArmor.dearmor(identity.armoredPublicKey)!!

    private fun usable(result: CPContactKey.Result): CPContactKey.Result.Usable {
        assertTrue("expected a usable key, got ${result::class.simpleName}", result is CPContactKey.Result.Usable)
        return result as CPContactKey.Result.Usable
    }

    @Test
    fun bothKindsOfKeyComeThroughAsText() {
        for (identity in listOf(v4(), v6())) {
            val key = usable(CPContactKey.inspect(identity.armoredPublicKey))
            assertEquals(identity.fingerprint.uppercase(), key.fingerprint)
            assertEquals("Sea Biscuit", key.name)
            // What comes back is the same key, and still one the app can encrypt to.
            assertArrayEquals(binary(identity), CPArmor.dearmor(key.armoredPublicKey))
            assertEquals(identity.fingerprint.uppercase(), CPKeyInfo.primaryFingerprint(key.armoredPublicKey))
        }
    }

    @Test
    fun bothKindsOfKeyComeThroughAsABinaryFile() {
        for (identity in listOf(v4(), v6())) {
            val key = usable(CPContactKey.inspect(binary(identity)))
            assertEquals(identity.fingerprint.uppercase(), key.fingerprint)
            assertArrayEquals(binary(identity), CPArmor.dearmor(key.armoredPublicKey))
        }
    }

    @Test
    fun aMessageCanBeEncryptedToTheKeyThatComesBack() {
        val me = v6("Me")
        for (them in listOf(v4("Them"), v6("Them"))) {
            val key = usable(CPContactKey.inspect("Here is my key:\n\n${them.armoredPublicKey}\nThanks"))
            val envelope = CPMessenger.signAndEncrypt(
                "hello".toByteArray(), me.secretKey, null, CPArmor.dearmor(key.armoredPublicKey)!!
            )
            val opened = CPMessenger.decryptOnly(envelope, them.secretKey, null)
            assertArrayEquals("hello".toByteArray(), opened)
        }
    }

    @Test
    fun textAroundTheKeyAndCommentHeadersAreIgnored() {
        val identity = v4()
        val withHeaders = identity.armoredPublicKey.replaceFirst(
            "-----BEGIN PGP PUBLIC KEY BLOCK-----",
            "-----BEGIN PGP PUBLIC KEY BLOCK-----\nComment: sent from somewhere\nVersion: Something 1.0"
        )
        val pasted = "On Monday they wrote:\r\n> key below\r\n\r\n$withHeaders\r\n-- \r\nsignature line"
        val key = usable(CPContactKey.inspect(pasted))
        assertEquals(identity.fingerprint.uppercase(), key.fingerprint)
        assertFalse(key.armoredPublicKey.contains("Comment"))
        assertFalse(key.armoredPublicKey.contains("Version"))
        assertFalse(key.armoredPublicKey.contains("signature line"))
        assertTrue(key.armoredPublicKey.trim().startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----"))
        assertTrue(key.armoredPublicKey.trim().endsWith("-----END PGP PUBLIC KEY BLOCK-----"))
    }

    @Test
    fun onlyTheFirstKeyOfSeveralIsTaken() {
        val first = v6("First")
        val second = v4("Second")
        for ((a, b) in listOf(first to second, second to first)) {
            val both = binary(a) + binary(b)
            val key = usable(CPContactKey.inspect(both))
            assertEquals(a.fingerprint.uppercase(), key.fingerprint)
            assertArrayEquals(binary(a), CPArmor.dearmor(key.armoredPublicKey))
            // The same two keys as one armored block.
            val out = ByteArrayOutputStream()
            ArmoredOutputStream(out).use { it.write(both) }
            val fromArmor = usable(CPContactKey.inspect(out.toByteArray()))
            assertEquals(a.fingerprint.uppercase(), fromArmor.fingerprint)
            assertArrayEquals(binary(a), CPArmor.dearmor(fromArmor.armoredPublicKey))
        }
    }

    @Test
    fun aSecretKeyIsNamedAsOne() {
        for (identity in listOf(v4(), v6())) {
            assertEquals(CPContactKey.Result.SecretKey, CPContactKey.inspect(identity.secretKey))
        }
        val armoredSecret = ByteArrayOutputStream().also { out ->
            ArmoredOutputStream(out).use { it.write(v4().secretKey) }
        }.toByteArray()
        assertEquals(CPContactKey.Result.SecretKey, CPContactKey.inspect(armoredSecret))
        // Even when it does not decode, text that says it is a private key is treated as one.
        val broken = "-----BEGIN PGP PRIVATE KEY BLOCK-----\n\nnot base64 at all\n-----END PGP PRIVATE KEY BLOCK-----"
        assertEquals(CPContactKey.Result.SecretKey, CPContactKey.inspect(broken))
    }

    @Test
    fun thingsThatAreNotKeysAreTurnedAway() {
        assertEquals(CPContactKey.Result.NotAKey, CPContactKey.inspect(""))
        assertEquals(CPContactKey.Result.NotAKey, CPContactKey.inspect("hello there"))
        assertEquals(CPContactKey.Result.NotAKey, CPContactKey.inspect("CPPAIR1:eyJ2IjoxfQ"))
        assertEquals(CPContactKey.Result.NotAKey, CPContactKey.inspect(ByteArray(64) { 0x41 }))
        assertEquals(CPContactKey.Result.NotAKey, CPContactKey.inspect(ByteArray(64) { 0xC6.toByte() }))
        assertEquals(CPContactKey.Result.NotAKey, CPContactKey.inspect(ByteArray(CPContactKey.MAX_INPUT_BYTES + 1) { 0x41 }))
        val truncated = v4().armoredPublicKey.let { it.substring(0, it.length / 2) }
        assertEquals(CPContactKey.Result.NotAKey, CPContactKey.inspect(truncated))
        // A signed message is armor, but not a public key block.
        val me = v4()
        val signature = CPMessenger.detachedSignature("x".toByteArray(), me.secretKey, null)
        assertEquals(CPContactKey.Result.NotAKey, CPContactKey.inspect(signature))
    }

    @Test
    fun aKeyOfAnotherKindIsUnsupported() {
        // Ed25519 with an X25519 subkey in the v6 format, made by another
        // implementation: a real key, but with no ML-KEM subkey to encrypt to.
        val generator = OpenPGPKeyGenerator(BcOpenPGPImplementation(), PublicKeyPacket.VERSION_6, false, Date())
        val other = generator
            .withPrimaryKey(KeyPairGeneratorCallback { it.generateEd25519KeyPair() })
            .addEncryptionSubkey(KeyPairGeneratorCallback { it.generateX25519KeyPair() })
            .addUserId("Other <other@example.com>")
            .build()
        val publicBytes = PGPPublicKeyRing(other.getPGPSecretKeyRing().publicKeys.asSequence().toList()).encoded
        assertEquals(CPContactKey.Result.Unsupported, CPContactKey.inspect(publicBytes))
    }

    @Test
    fun aSubkeyThePrimaryHasNotSignedIsUnsupported() {
        // The victim's primary key and user ID, with somebody else's encryption subkey.
        for ((victim, attacker) in listOf(v6("Victim") to v6("Attacker"), v4("Victim") to v4("Attacker"))) {
            val victimRing = CPMessenger.loadPublicRing(binary(victim))
            val attackerRing = CPMessenger.loadPublicRing(binary(attacker))
            val grafted = PGPPublicKeyRing(
                listOf(victimRing.publicKey) + attackerRing.publicKeys.asSequence().filter { !it.isMasterKey }.toList()
            )
            assertEquals(CPContactKey.Result.Unsupported, CPContactKey.inspect(grafted.encoded))
        }
    }
}

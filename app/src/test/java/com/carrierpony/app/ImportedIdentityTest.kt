package com.carrierpony.app

import com.carrierpony.app.crypto.CryptoException
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PonyCryptoEngine
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.identity.Identity
import com.carrierpony.app.identity.KeyImport
import com.carrierpony.app.identity.KeyImportException
import com.carrierpony.core.CPGeneratedIdentity
import com.carrierpony.core.CPIdentityGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// An identity imported from another app's v6 key (throwaway keys exported from
// PGPony). Such a key signs with a signing subkey, so a receiver has to find
// the contact from the subkey a signature names.
class ImportedIdentityTest {

    private fun armored(name: String): String =
        String(javaClass.getResourceAsStream("/pgpony/$name")!!.use { it.readBytes() }, Charsets.UTF_8)

    private val unprotectedFingerprint = "DA92AD1ADD5E10FD5135559FBD05CB8B9CD3B9AB704CBA482688AD12C3024F13"
    private val protectedFingerprint = "874AABA8729AEF776BDB8221D28B3B15EEB7FB3CCF37D866102A5EBE3C5C2F83"

    private fun imported(protected: Boolean): Identity {
        val inspection = KeyImport.inspect(
            armored(if (protected) "pgpony-v6-protected-secret.asc" else "pgpony-v6-unprotected-secret.asc")
        )
        return KeyImport.makeIdentity(inspection, if (protected) "a" else null)
    }

    private fun publicKey(identity: Identity) = PublicKey(identity.fingerprint, identity.armoredPublicKey)
    private fun publicKey(identity: CPGeneratedIdentity) =
        PublicKey(Fingerprint.from(identity.fingerprint)!!, identity.armoredPublicKey)

    private fun engine(own: Identity, passphrase: String?, contacts: List<PublicKey>) = PonyCryptoEngine(
        fingerprint = own.fingerprint,
        secretKey = own.secretKey,
        armored = own.armoredPublicKey,
        passphrase = { passphrase },
        signerKeys = { contacts },
        publicKeyResolver = { fingerprint -> contacts.firstOrNull { it.fingerprint == fingerprint } }
    )

    private fun engine(own: CPGeneratedIdentity, contacts: List<PublicKey>, lookThroughSubkeys: Boolean = true) =
        PonyCryptoEngine(
            fingerprint = Fingerprint.from(own.fingerprint)!!,
            secretKey = own.secretKey,
            armored = own.armoredPublicKey,
            signerKeys = { if (lookThroughSubkeys) contacts else emptyList() },
            publicKeyResolver = { fingerprint -> contacts.firstOrNull { it.fingerprint == fingerprint } }
        )

    @Test
    fun unprotectedKeyBecomesAnIdentity() {
        val inspection = KeyImport.inspect(armored("pgpony-v6-unprotected-secret.asc"))
        assertFalse(inspection.needsPassphrase)
        val identity = KeyImport.makeIdentity(inspection, null)
        assertEquals(unprotectedFingerprint, identity.fingerprint.hex)
        assertTrue(identity.fingerprint.isV6)
        assertFalse(identity.protected)
    }

    @Test
    fun protectedKeyBecomesAnIdentityWithItsPassphrase() {
        val inspection = KeyImport.inspect(armored("pgpony-v6-protected-secret.asc"))
        assertTrue(inspection.needsPassphrase)
        val identity = KeyImport.makeIdentity(inspection, "a")
        assertEquals(protectedFingerprint, identity.fingerprint.hex)
        assertTrue(identity.protected)
    }

    @Test
    fun wrongPassphraseIsRefusedWithTheUsualMessage() {
        val inspection = KeyImport.inspect(armored("pgpony-v6-protected-secret.asc"))
        try {
            KeyImport.makeIdentity(inspection, "b")
            fail("a wrong passphrase should not import")
        } catch (e: KeyImportException) {
            assertEquals(KeyImportException.wrongPassphrase().message, e.message)
        }
    }

    @Test
    fun aContactFindsTheSenderFromTheSigningSubkey() {
        val me = imported(protected = true)
        val friend = CPIdentityGenerator.generateV6Identity("Friend", "friend@carrierpony.com")
        val plaintext = "hello".toByteArray(Charsets.UTF_8)

        val envelope = engine(me, "a", listOf(publicKey(friend))).signAndEncrypt(plaintext, publicKey(friend))
        val opened = engine(friend, listOf(publicKey(me))).decryptAndVerify(envelope)
        assertArrayEquals(plaintext, opened.plaintext)
        assertEquals("the sender is the contact, not its subkey", me.fingerprint, opened.sender)
    }

    @Test
    fun withoutTheSubkeyLookupTheSenderIsUnknown() {
        val me = imported(protected = false)
        val friend = CPIdentityGenerator.generateV6Identity("Friend", "friend@carrierpony.com")
        val envelope = engine(me, null, listOf(publicKey(friend)))
            .signAndEncrypt(byteArrayOf(1, 2, 3), publicKey(friend))
        try {
            engine(friend, listOf(publicKey(me)), lookThroughSubkeys = false).decryptAndVerify(envelope)
            fail("this is the failure the subkey lookup exists to fix")
        } catch (e: CryptoException.NoValidSignature) {
        }
    }

    @Test
    fun aStrangerWithNoContactEntryIsStillUnknown() {
        val me = imported(protected = false)
        val friend = CPIdentityGenerator.generateV6Identity("Friend", "friend@carrierpony.com")
        val envelope = engine(me, null, listOf(publicKey(friend)))
            .signAndEncrypt(byteArrayOf(1, 2, 3), publicKey(friend))
        try {
            engine(friend, emptyList()).decryptAndVerify(envelope)
            fail("an imported identity that is not a contact must not verify")
        } catch (e: CryptoException.NoValidSignature) {
        }
    }

    @Test
    fun aKnownSignerCheckAcceptsTheSigningSubkey() {
        val me = imported(protected = false)
        val friend = CPIdentityGenerator.generateV6Identity("Friend", "friend@carrierpony.com")
        val plaintext = "contact request".toByteArray(Charsets.UTF_8)
        val envelope = engine(me, null, listOf(publicKey(friend))).signAndEncrypt(plaintext, publicKey(friend))
        val opened = engine(friend, emptyList()).decryptAndVerify(envelope, publicKey(me))
        assertArrayEquals(plaintext, opened.plaintext)
        assertEquals(me.fingerprint, opened.sender)
    }

    @Test
    fun theImportedIdentityReadsItsContactsMessages() {
        val me = imported(protected = true)
        val friend = CPIdentityGenerator.generateV6Identity("Friend", "friend@carrierpony.com")
        val plaintext = "reply".toByteArray(Charsets.UTF_8)
        val envelope = engine(friend, listOf(publicKey(me))).signAndEncrypt(plaintext, publicKey(me))
        val opened = engine(me, "a", listOf(publicKey(friend))).decryptAndVerify(envelope)
        assertArrayEquals(plaintext, opened.plaintext)
        assertEquals(Fingerprint.from(friend.fingerprint), opened.sender)
    }
}

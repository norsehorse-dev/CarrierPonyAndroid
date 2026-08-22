// KeyImportTest.kt
// CarrierPony Android
//
// The app-level import wrapper: inspection, identity construction, protected
// flag, and the exact user-facing error copy from iOS. A gpg-generated key
// exported from your real keyring exercises the same path manually through
// the Import a Key screen.

package com.carrierpony.app

import com.carrierpony.app.identity.KeyImport
import com.carrierpony.app.identity.KeyImportException
import com.carrierpony.core.CPArmor
import com.carrierpony.core.CPIdentityGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class KeyImportTest {

    private fun armoredSecret(): Pair<String, String> {
        val generated = CPIdentityGenerator.generateV4Identity("Ponyo Importer", "ponyo@carrierpony.com")
        // Armor the raw secret bytes the way gpg --export-secret-keys --armor
        // would. (The Version header is irrelevant here; core's header-free
        // clean() helper is internal to that module.)
        val out = ByteArrayOutputStream()
        val armor = org.bouncycastle.bcpg.ArmoredOutputStream(out)
        armor.write(generated.secretKey)
        armor.close()
        return out.toString(Charsets.UTF_8) to generated.fingerprint
    }

    @Test
    fun importsAnUnprotectedKeyEndToEnd() {
        val (armored, fpr) = armoredSecret()
        val inspection = KeyImport.inspect(armored)
        assertFalse(inspection.needsPassphrase)
        val identity = KeyImport.makeIdentity(inspection, null)
        assertEquals(fpr, identity.fingerprint.hex)
        assertFalse(identity.protected)
        assertNotNull(CPArmor.dearmor(identity.armoredPublicKey))
        assertEquals("Ponyo Importer", KeyImport.userIDName(identity.secretKey))
    }

    @Test
    fun garbageGetsTheMalformedCopy() {
        try {
            KeyImport.inspect("this is not a key")
            fail("Expected KeyImportException")
        } catch (e: KeyImportException) {
            assertEquals("That doesn't look like a valid private key.", e.message)
        }
    }

    @Test
    fun publicKeyGetsRefused() {
        val generated = CPIdentityGenerator.generateV4Identity("Pub Only", "pub@carrierpony.com")
        try {
            KeyImport.inspect(generated.armoredPublicKey)
            fail("Expected KeyImportException")
        } catch (e: KeyImportException) {
            // Malformed or not-supported are both acceptable refusals here;
            // the point is a public key never becomes an identity.
        }
    }
}

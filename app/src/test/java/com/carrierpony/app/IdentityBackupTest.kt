// IdentityBackupTest.kt
// CarrierPony Android
//
// Backup round trips against a real generated identity, plus the refusal
// paths. The iOS conformance test is resource-gated: export a backup on the
// iPhone (Settings -> Back Up Identity), then drop three files into
// app/src/test/resources/backup/ — blob.txt (the pasted backup), pass.txt
// (its passphrase), fpr.txt (the expected 40-hex fingerprint) — and this
// test proves the cross-platform restore claim.

package com.carrierpony.app

import com.carrierpony.app.identity.BackupException
import com.carrierpony.app.identity.Identity
import com.carrierpony.app.identity.IdentityBackup
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.core.CPIdentityGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

class IdentityBackupTest {

    private fun realIdentity(): Identity {
        val generated = CPIdentityGenerator.generateV4Identity("Backup Test", "backup@carrierpony.com")
        return Identity(
            fingerprint = Fingerprint.from(generated.fingerprint)!!,
            secretKey = generated.secretKey,
            armoredPublicKey = generated.armoredPublicKey
        )
    }

    @Test
    fun backupRoundTripsARealIdentity() {
        val identity = realIdentity()
        val blob = IdentityBackup.export(identity, "correct horse battery staple")
        val restored = IdentityBackup.restore(blob, "correct horse battery staple")
        assertEquals(identity.fingerprint, restored.fingerprint)
        assertArrayEquals(identity.secretKey, restored.secretKey)
        assertEquals(identity.armoredPublicKey, restored.armoredPublicKey)
    }

    @Test
    fun wrongPassphraseIsRefusedWithTheUserFacingMessage() {
        val blob = IdentityBackup.export(realIdentity(), "right")
        try {
            IdentityBackup.restore(blob, "wrong")
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertEquals("This backup is not valid, or the passphrase is wrong.", e.message)
        }
    }

    @Test
    fun malformedBlobIsRefused() {
        try {
            IdentityBackup.restore("not a backup at all", "pass")
            fail("Expected BackupException")
        } catch (e: BackupException) {
            // Correct.
        }
    }

    @Test
    fun iosBackupRestoresHere() {
        val blob = javaClass.getResourceAsStream("/backup/blob.txt")
        val pass = javaClass.getResourceAsStream("/backup/pass.txt")
        val fpr = javaClass.getResourceAsStream("/backup/fpr.txt")
        assumeTrue("iOS backup vector not present; skipping", blob != null && pass != null && fpr != null)
        val restored = IdentityBackup.restore(
            String(blob!!.readBytes(), Charsets.UTF_8).trim(),
            String(pass!!.readBytes(), Charsets.UTF_8).trim()
        )
        assertEquals(String(fpr!!.readBytes(), Charsets.UTF_8).trim().uppercase(), restored.fingerprint.hex)
    }
}

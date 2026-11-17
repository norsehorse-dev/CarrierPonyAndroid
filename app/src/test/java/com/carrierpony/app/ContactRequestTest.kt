// ContactRequestTest.kt
// CarrierPony Android
//
// The contact-request control message: what someone who added a person by
// public key sends them. It has to carry the sender's own key, because the
// receiver does not hold it yet, along with the sender's name and what their
// app supports. iOS writes and reads the same fields.

package com.carrierpony.app

import com.carrierpony.app.messaging.ChatStore
import com.carrierpony.app.pairing.PairingCaps
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ContactRequestTest {

    private val key = "-----BEGIN PGP PUBLIC KEY BLOCK-----\n\nxjMEZ\n-----END PGP PUBLIC KEY BLOCK-----\n"

    @Test
    fun theRequestCarriesKeyNameAndCaps() {
        val op = ChatStore.ControlOp(
            op = "contact-request", targetMessageID = "", at = 1_800_000_000,
            name = "Sea Biscuit", pubkey = key, caps = PairingCaps.current
        )
        val written = JSONObject(String(op.encoded(), Charsets.UTF_8))
        assertEquals("contact-request", written.getString("op"))
        assertEquals(key, written.getString("pubkey"))
        assertEquals("Sea Biscuit", written.getString("name"))
        assertEquals("v6", written.getJSONArray("caps").getString(0))

        val read = ChatStore.ControlOp.decode(op.encoded())
        assertNotNull(read)
        assertEquals("contact-request", read!!.op)
        assertEquals(key, read.pubkey)
        assertEquals("Sea Biscuit", read.name)
        assertEquals(listOf("v6"), read.caps)
    }

    @Test
    fun aRequestWithoutANameLeavesTheFieldOut() {
        val op = ChatStore.ControlOp(
            op = "contact-request", targetMessageID = "", at = 1_800_000_000,
            pubkey = key, caps = PairingCaps.current
        )
        val written = JSONObject(String(op.encoded(), Charsets.UTF_8))
        assertFalse(written.has("name"))
        assertNull(ChatStore.ControlOp.decode(op.encoded())!!.name)
    }

    @Test
    fun aRequestAsIosWritesItDecodes() {
        val json = """{"op":"contact-request","target_message_id":"","at":1800000000,"name":"Sea Biscuit","pubkey":"KEY","caps":["v6"]}"""
        val read = ChatStore.ControlOp.decode(json.toByteArray())
        assertNotNull(read)
        assertEquals("KEY", read!!.pubkey)
        assertEquals("Sea Biscuit", read.name)
        assertEquals(listOf("v6"), read.caps)
    }
}

class PublicKeyFileNameTest {

    private fun key(name: String) =
        com.carrierpony.core.CPIdentityGenerator.generateV4Identity(name, "test@carrierpony.com")

    @Test
    fun theFileIsNamedAfterTheKey() {
        val identity = key("Sea Biscuit")
        assertEquals(
            "Sea Biscuit.asc",
            com.carrierpony.app.ui.publicKeyFileName(identity.armoredPublicKey, identity.fingerprint)
        )
    }

    @Test
    fun charactersThatDoNotBelongInAFileNameAreDropped() {
        val identity = key("../Sea: Biscuit?*")
        assertEquals(
            "Sea Biscuit.asc",
            com.carrierpony.app.ui.publicKeyFileName(identity.armoredPublicKey, identity.fingerprint)
        )
    }

    @Test
    fun aKeyWithNoUsableNameFallsBackToItsFingerprint() {
        val identity = key("///")
        assertEquals(
            "carrierpony-${identity.fingerprint.takeLast(8)}.asc",
            com.carrierpony.app.ui.publicKeyFileName(identity.armoredPublicKey, identity.fingerprint)
        )
        assertEquals("carrierpony-89ABCDEF.asc", com.carrierpony.app.ui.publicKeyFileName("not a key", "0123456789ABCDEF"))
    }
}

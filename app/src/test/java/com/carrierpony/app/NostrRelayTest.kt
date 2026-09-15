package com.carrierpony.app

import com.carrierpony.app.nostr.NostrRelayClient
import com.carrierpony.app.nostr.NostrWebSocket
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrRelayTest {

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    // The RFC6455 example: key "dGhlIHNhbXBsZSBub25jZQ==" -> this accept token.
    @Test fun handshakeAcceptMatchesRfcVector() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            NostrWebSocket.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    // Client-masked text frame for "hi" with mask 01 02 03 04 (validated in Python).
    @Test fun encodeFrameMatchesVector() {
        val f = NostrWebSocket.encodeFrame(0x1, "hi".toByteArray(Charsets.UTF_8), byteArrayOf(1, 2, 3, 4))
        assertEquals("818201020304696b", f.hex())
    }

    // A payload past the 125-byte boundary uses the 16-bit length; masking round-trips.
    @Test fun encodeFrameExtendedLengthUnmasks() {
        val payload = ByteArray(130) { (it and 0xFF).toByte() }
        val mask = byteArrayOf(9, 8, 7, 6)
        val f = NostrWebSocket.encodeFrame(0x1, payload, mask)
        assertEquals(0x81.toByte(), f[0])
        assertEquals((0x80 or 126).toByte(), f[1])
        assertEquals(0, f[2].toInt() and 0xFF)   // length high byte
        assertEquals(130, f[3].toInt() and 0xFF) // length low byte
        val body = f.copyOfRange(8, f.size)    // 2 header + 2 len + 4 mask = 8
        val un = ByteArray(body.size) { (body[it].toInt() xor mask[it % 4].toInt()).toByte() }
        assertTrue(payload.contentEquals(un))
    }

    @Test fun filtersAreWellFormed() {
        val idF = JSONObject(NostrRelayClient.idFilter("abc123"))
        assertEquals("abc123", idF.getJSONArray("ids").getString(0))

        val mbF = JSONObject(NostrRelayClient.mailboxFilter(listOf("aa", "bb")))
        assertEquals(1314, mbF.getJSONArray("kinds").getInt(0))
        assertEquals("aa", mbF.getJSONArray("#t").getString(0))
        assertEquals("bb", mbF.getJSONArray("#t").getString(1))
    }
}

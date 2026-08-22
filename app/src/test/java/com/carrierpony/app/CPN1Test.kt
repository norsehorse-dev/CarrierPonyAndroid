// CPN1Test.kt
// CarrierPony Android
//
// Port of iOS Tests/CPN1Tests.swift, including the harness thread-ID vector —
// the same constant both platforms must reproduce.

package com.carrierpony.app

import com.carrierpony.app.envelope.CPN1
import com.carrierpony.app.envelope.CPN1Exception
import com.carrierpony.app.envelope.Manifest
import com.carrierpony.app.envelope.Threading
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID
import kotlin.random.Random

class CPN1Test {

    @Test
    fun containerRoundTrip() {
        val text = "hello CPN1".toByteArray()
        val blob = ByteArray(257).also { Random.nextBytes(it) }
        val manifest = "{\"v\":1}".toByteArray()

        val container = CPN1.encode(manifest, listOf(text, blob))
        val decoded = CPN1.decode(container)

        assertArrayEquals(manifest, decoded.manifest)
        assertEquals(2, decoded.parts.size)
        assertArrayEquals(text, decoded.parts[0])
        assertArrayEquals(blob, decoded.parts[1])
    }

    @Test
    fun emptyAndLargePartsRoundTrip() {
        val empty = ByteArray(0)
        val large = ByteArray(200_000) { 0xAB.toByte() }
        val manifest = "{}".toByteArray()

        val container = CPN1.encode(manifest, listOf(empty, large))
        val decoded = CPN1.decode(container)

        assertEquals(0, decoded.parts[0].size)
        assertArrayEquals(large, decoded.parts[1])
    }

    @Test
    fun manifestRoundTrip() {
        val manifest = Manifest(
            v = 1,
            type = "message",
            messageID = UUID.randomUUID().toString().uppercase(),
            threadID = Threading.pairwise("AA", "BB"),
            sentAt = 1,
            expiresAt = 2,
            parts = listOf(
                Manifest.Part(kind = "text", mime = "text/plain; charset=utf-8"),
                Manifest.Part(kind = "file", mime = "application/octet-stream", filename = "blob.bin", size = 257)
            )
        )

        val container = CPN1.encode(manifest.encoded(), listOf("x".toByteArray()))
        val decoded = Manifest.decode(CPN1.decode(container).manifest)

        assertEquals(manifest.messageID, decoded.messageID)
        assertEquals(manifest.threadID, decoded.threadID)
        assertEquals(2, decoded.parts.size)
        assertNull(decoded.parts[0].filename)
        assertEquals("blob.bin", decoded.parts[1].filename)
        assertEquals(257L, decoded.parts[1].size)
    }

    @Test
    fun threadIDDeterministic() {
        val a = "08108C383A81C409F3E28757397AC5A52B6C5A8D"
        val b = "05299C734EA466728C71BD7ACDD6B5F7C410B233"
        assertEquals(Threading.pairwise(a, b), Threading.pairwise(b, a))
        assertEquals(64, Threading.pairwise(a, b).length)
    }

    @Test
    fun threadIDMatchesHarness() {
        val a = "08108C383A81C409F3E28757397AC5A52B6C5A8D"
        val b = "05299C734EA466728C71BD7ACDD6B5F7C410B233"
        val expected = "7bc838882b284a83f951e98d25aac64fda850784be9ca03e1062867c9c9460e4"
        assertEquals(expected, Threading.pairwise(a, b))
    }

    @Test(expected = CPN1Exception.BadMagic::class)
    fun badMagicThrows() {
        val bad = "XXXX".toByteArray() + ByteArray(4)
        CPN1.decode(bad)
    }

    @Test(expected = CPN1Exception.Truncated::class)
    fun truncatedThrows() {
        CPN1.decode("CPN".toByteArray())
    }

    @Test(expected = CPN1Exception.Truncated::class)
    fun lyingPartLengthThrows() {
        val container = CPN1.encode("{}".toByteArray(), listOf("abc".toByteArray()))
        CPN1.decode(container.copyOfRange(0, container.size - 1))
    }
}

package com.carrierpony.app

import com.carrierpony.app.ui.IncomingKey
import com.carrierpony.core.CPContactKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class IncomingKeyTest {

    @Test
    fun smallInputIsReadWhole() {
        val bytes = ByteArray(1000) { (it % 251).toByte() }
        assertArrayEquals(bytes, IncomingKey.readCapped(ByteArrayInputStream(bytes)))
    }

    @Test
    fun emptyInputGivesNothing() {
        assertEquals(0, IncomingKey.readCapped(ByteArrayInputStream(ByteArray(0))).size)
    }

    @Test
    fun inputAtTheLimitIsReadWhole() {
        val bytes = ByteArray(CPContactKey.MAX_INPUT_BYTES) { 0x41 }
        assertEquals(CPContactKey.MAX_INPUT_BYTES, IncomingKey.readCapped(ByteArrayInputStream(bytes)).size)
    }

    @Test
    fun oversizedInputStopsOneByteOverAndIsRefused() {
        val bytes = ByteArray(CPContactKey.MAX_INPUT_BYTES * 3) { 0x41 }
        val read = IncomingKey.readCapped(ByteArrayInputStream(bytes))
        assertEquals(CPContactKey.MAX_INPUT_BYTES + 1, read.size)
        assertEquals(CPContactKey.Result.NotAKey, CPContactKey.inspect(read))
    }

    @Test
    fun streamThatHandsOutOneByteAtATimeIsReadWhole() {
        val bytes = ByteArray(300) { it.toByte() }
        val slow = object : InputStream() {
            private var at = 0
            override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xff else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (at >= bytes.size) return -1
                b[off] = bytes[at++]
                return 1
            }
        }
        assertArrayEquals(bytes, IncomingKey.readCapped(slow))
    }
}

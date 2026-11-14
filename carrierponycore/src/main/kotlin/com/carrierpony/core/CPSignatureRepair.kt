// CPSignatureRepair.kt
// CarrierPonyCore
//
// Reads signatures written by an encoder that gets one thing wrong.
//
// An EdDSA signature is two 32-octet values, R and S, each stored as an OpenPGP
// MPI: a two-octet bit count, then the value with its leading zero octets
// removed. The iOS core up to CarrierPony 3.2.x computes the bit count
// correctly but then writes all 32 octets, leading zeros included. When R or S
// begins with a zero octet (about one signature in 128) the count says 31
// octets and 32 follow, so a reader that trusts the count takes the wrong
// octets and the signature does not verify. BouncyCastle is such a reader, and
// so is the iOS core's own parser.
//
// The value is all there, only mislabelled, and the signature's hash does not
// cover how R and S are encoded. So such a packet can be rewritten in the
// correct form without changing what it means: if the two MPIs do not fill the
// signature area by their own counts, and the area is exactly the 68 octets
// that two full-width values take, and each count is what the full 32-octet
// value would give, then it is this encoder and R and S are the two 32-octet
// halves. Anything else is left exactly as it was.
//
// This only ever makes a mislabelled signature readable. Whether it then
// verifies is still decided by the cryptography, so nothing here can make a
// forged signature pass.

package com.carrierpony.core

import java.io.ByteArrayOutputStream

object CPSignatureRepair {

    private const val TAG_SIGNATURE = 2
    private const val EDDSA_LEGACY = 22
    private const val VALUE_LENGTH = 32
    private const val FULL_AREA = 2 * (2 + VALUE_LENGTH)

    /**
     * [data] (a binary key ring or any sequence of definite-length packets)
     * with every mislabelled v4 EdDSA signature rewritten correctly. Returns
     * [data] itself when there is nothing to repair or the bytes are not a
     * clean packet sequence.
     */
    fun normalize(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var changed = false
        var i = 0
        while (i < data.size) {
            val header = header(data, i) ?: return data
            val end = header.bodyStart + header.bodyLength
            if (header.bodyLength < 0 || end > data.size || end <= i) return data
            val repaired = if (header.tag == TAG_SIGNATURE) repairBody(data.copyOfRange(header.bodyStart, end)) else null
            if (repaired != null) {
                out.write(packet(TAG_SIGNATURE, repaired))
                changed = true
            } else {
                out.write(data, i, end - i)
            }
            i = end
        }
        return if (changed) out.toByteArray() else data
    }

    /** The corrected body of a mislabelled v4 EdDSA signature packet, or null if [body] is not one. */
    internal fun repairBody(body: ByteArray): ByteArray? {
        if (body.size < 10) return null
        if (body[0].toInt() != 4) return null
        if (body[2].toInt() and 0xFF != EDDSA_LEGACY) return null
        var i = 4
        val hashedLength = uint16(body, i)
        i += 2 + hashedLength
        if (i + 2 > body.size) return null
        val unhashedLength = uint16(body, i)
        i += 2 + unhashedLength
        i += 2
        if (i > body.size) return null
        val area = body.copyOfRange(i, body.size)

        if (fillsExactly(area)) return null
        if (area.size != FULL_AREA) return null

        val rBits = uint16(area, 0)
        val r = area.copyOfRange(2, 2 + VALUE_LENGTH)
        val sBits = uint16(area, 2 + VALUE_LENGTH)
        val s = area.copyOfRange(4 + VALUE_LENGTH, FULL_AREA)
        if (rBits != bitLength(r) || sBits != bitLength(s)) return null

        return body.copyOfRange(0, i) + mpi(r) + mpi(s)
    }

    // ── Internals ──────────────────────────────────────────────────────

    /** True when two MPIs, read by their own bit counts, use up [area] exactly. */
    private fun fillsExactly(area: ByteArray): Boolean {
        var off = 0
        repeat(2) {
            if (off + 2 > area.size) return false
            off += 2 + (uint16(area, off) + 7) / 8
            if (off > area.size) return false
        }
        return off == area.size
    }

    private fun bitLength(value: ByteArray): Int {
        for ((index, b) in value.withIndex()) {
            val v = b.toInt() and 0xFF
            if (v != 0) return (value.size - index) * 8 - (Integer.numberOfLeadingZeros(v) - 24)
        }
        return 0
    }

    private fun mpi(value: ByteArray): ByteArray {
        var first = 0
        while (first < value.size && value[first].toInt() == 0) first++
        val trimmed = value.copyOfRange(first, value.size)
        val bits = bitLength(trimmed)
        return byteArrayOf((bits ushr 8).toByte(), bits.toByte()) + trimmed
    }

    private fun uint16(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)

    private fun packet(tag: Int, body: ByteArray): ByteArray {
        val n = body.size
        val head = when {
            n < 192 -> byteArrayOf((0xC0 or tag).toByte(), n.toByte())
            n < 8384 -> byteArrayOf((0xC0 or tag).toByte(), (((n - 192) shr 8) + 192).toByte(), ((n - 192) and 0xFF).toByte())
            else -> byteArrayOf(
                (0xC0 or tag).toByte(), 0xFF.toByte(),
                (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()
            )
        }
        return head + body
    }

    internal class Header(val tag: Int, val bodyStart: Int, val bodyLength: Int)

    /** A definite-length packet header at [start], old or new format. Null for anything else. */
    internal fun header(data: ByteArray, start: Int): Header? {
        var i = start
        fun octet(): Int? = if (i < data.size) data[i++].toInt() and 0xFF else null
        fun uint32(): Int? {
            if (data.size - i < 4) return null
            val v = ((data[i].toInt() and 0xFF) shl 24) or ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            i += 4
            return v
        }
        val c = octet() ?: return null
        if (c and 0x80 == 0) return null
        val tag: Int
        val length: Int
        if (c and 0x40 != 0) {
            tag = c and 0x3F
            val l0 = octet() ?: return null
            length = when {
                l0 < 192 -> l0
                l0 < 224 -> ((l0 - 192) shl 8) + (octet() ?: return null) + 192
                l0 == 255 -> uint32() ?: return null
                else -> return null
            }
        } else {
            tag = (c shr 2) and 0x0F
            length = when (c and 0x03) {
                0 -> octet() ?: return null
                1 -> ((octet() ?: return null) shl 8) or (octet() ?: return null)
                2 -> uint32() ?: return null
                else -> return null
            }
        }
        return Header(tag, i, length)
    }
}

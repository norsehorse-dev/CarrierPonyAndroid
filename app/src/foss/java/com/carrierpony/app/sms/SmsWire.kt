package com.carrierpony.app.sms

/** Wire format shared by the SMS send and receive sides. A 9-byte frame header
 *  prefixes each data-SMS chunk: MAGIC(1) id(4) index(2) total(2), big-endian. */
object SmsWire {
    const val PORT = 51966 // 0xCAFE: app data-SMS port, stays out of the inbox
    const val CHUNK = 110   // conservative binary bytes per data SMS after the header
    const val MAGIC = 0x43  // 'C'
    const val HEADER = 9

    fun header(id: Int, index: Int, total: Int): ByteArray = byteArrayOf(
        MAGIC.toByte(),
        (id ushr 24).toByte(), (id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte(),
        (index ushr 8).toByte(), index.toByte(),
        (total ushr 8).toByte(), total.toByte(),
    )
}

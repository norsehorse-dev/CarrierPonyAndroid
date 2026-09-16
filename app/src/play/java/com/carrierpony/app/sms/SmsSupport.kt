package com.carrierpony.app.sms

import android.content.Context
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.messaging.Transport

/**
 * Play build: no SMS. SEND_SMS/RECEIVE_SMS are Google Play restricted permissions,
 * so the SMS transport ships only in the foss flavor. This stub keeps the same
 * shape as the foss SmsSupport so shared code compiles in both flavors; every
 * entry point is inert and [available] is false.
 */
class SmsSupport(@Suppress("UNUSED_PARAMETER") context: Context) {
    val available: Boolean = false
    fun transport(
        @Suppress("UNUSED_PARAMETER") numberFor: (Fingerprint) -> String?,
        @Suppress("UNUSED_PARAMETER") enabled: () -> Boolean,
    ): Transport? = null
    fun start(@Suppress("UNUSED_PARAMETER") onEnvelope: (ByteArray) -> Unit) {}
    fun stop() {}
}

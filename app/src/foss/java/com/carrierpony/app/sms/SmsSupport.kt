package com.carrierpony.app.sms

import android.content.Context
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.messaging.Transport

/**
 * Foss build: the SMS transport is available. Send goes through [SmsCarrier];
 * receive is the manifest [SmsReceiver], which captures segments even when the app
 * is dead. start() makes this instance the live sink (so completed envelopes ingest
 * immediately) and drains anything queued while the app was not running.
 */
class SmsSupport(context: Context) {
    val available: Boolean = true
    private val appContext = context.applicationContext
    private val carrier = SmsCarrier(appContext)
    private val reassembly = SmsReassembly(appContext)

    fun transport(numberFor: (Fingerprint) -> String?, enabled: () -> Boolean): Transport? =
        SmsTransport(carrier, numberFor, enabled)

    fun start(onEnvelope: (ByteArray) -> Unit) {
        SmsReceiver.liveSink = { env -> onEnvelope(env); true }
        for (env in reassembly.drain()) onEnvelope(env)
    }

    fun stop() {
        SmsReceiver.liveSink = { false }
    }
}

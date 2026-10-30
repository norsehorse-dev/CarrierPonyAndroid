// UpPushService.kt
// CarrierPony Android — FOSS FLAVOR
//
// The UnifiedPush receiving side, the counterpart of the play flavor's FCM
// PushService. A wake carries no message content: the gateway encrypts a fixed
// {"wake":1} (or {"wake":1,"silent":1}) to this install's Web Push keys. A live
// ChatStore refreshes in place; otherwise one generic local notification is
// posted, never for a silent wake.

package com.carrierpony.app.push

import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class UpPushService : PushService() {

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val p256dh = endpoint.pubKeySet?.pubKey
        val auth = endpoint.pubKeySet?.auth
        // The distributor answers every register() with the endpoint, changed or
        // not. Only a new or not-yet-accepted one needs to go to the gateway; the
        // app start submits the stored one anyway.
        val submit = PushSupport.needsSubmit(this, endpoint.url, p256dh, auth)
        PushSupport.saveEndpoint(this, endpoint.url, p256dh, auth)
        if (!submit) return
        android.util.Log.i("CarrierPony", "UnifiedPush endpoint received")
        PushSupport.storedRegistration(this)?.let { reg ->
            try {
                PushSupport.onNewToken(reg)
            } catch (e: Exception) {
                // Submitted on the next app start instead.
            }
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        // With keys registered, every wake from the gateway is encrypted. An
        // unencrypted push can only come from someone else who learned the
        // endpoint URL, so it is dropped instead of posting a notification.
        if (PushSupport.expectsEncrypted(this) && !message.decrypted) {
            android.util.Log.w("CarrierPony", "UnifiedPush: dropped an unencrypted push")
            return
        }
        val body = String(message.content, Charsets.UTF_8)
        val silent = body.contains("\"silent\":1")
        val handledLive = try {
            PushSupport.onWake()
        } catch (e: Exception) {
            false
        }
        when {
            handledLive -> android.util.Log.i("CarrierPony", "wake handled live (foreground refresh)")
            silent -> android.util.Log.i("CarrierPony", "silent wake; banner suppressed")
            else -> PushNotifier.postGenericNotification(this)
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        android.util.Log.w("CarrierPony", "UnifiedPush registration failed: $reason")
        PushSupport.clearEndpoint(this, gatewayCleanup = true)
    }

    override fun onUnregistered(instance: String) {
        android.util.Log.i("CarrierPony", "UnifiedPush unregistered by the distributor")
        PushSupport.clearEndpoint(this, gatewayCleanup = true)
    }
}

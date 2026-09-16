// PushSupport.kt
// CarrierPony Android — PLAY FLAVOR
//
// The push seam AppModel talks to. This variant is Firebase-backed: it wires
// the FCM PushService hooks and fetches the FCM token. The FOSS variant ships a
// same-signature no-op object (delivery there falls back to polling), so
// AppModel carries no Firebase reference and compiles against both flavors.

package com.carrierpony.app.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging

object PushSupport {
    /** True on flavors that can receive push wakes. */
    const val available = true

    fun ensureChannel(context: Context) = PushNotifier.ensureChannel(context)

    /** Install AppModel's token/wake hooks into the FCM service. */
    fun install(onNewToken: (String) -> Unit, onWake: () -> Boolean) {
        PushService.onNewToken = onNewToken
        PushService.onWake = onWake
    }

    /** Fetch the current FCM token, calling back on success. No-op (throws
     *  swallowed) when Firebase is not configured on this build. */
    fun fetchToken(onToken: (String) -> Unit) {
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> onToken(token) }
        } catch (e: Exception) {
            // Firebase not configured on this build; polling covers delivery.
        }
    }
}

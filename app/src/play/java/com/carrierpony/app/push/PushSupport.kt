// PushSupport.kt
// CarrierPony Android — PLAY FLAVOR
//
// The push seam AppModel talks to. This variant is Firebase-backed: it wires
// the FCM PushService hooks and fetches the FCM token. The FOSS variant has the
// same API backed by UnifiedPush and a periodic background check, so AppModel
// carries no Firebase or UnifiedPush reference and compiles against both flavors.

package com.carrierpony.app.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging

object PushSupport {
    /** True on flavors that can receive push wakes. */
    const val available = true

    /** False here: Play builds always use FCM, there is nothing to choose. */
    const val supportsChoice = false

    fun ensureChannel(context: Context) = PushNotifier.ensureChannel(context)

    /** Install AppModel's token/wake hooks into the FCM service. */
    fun install(onNewToken: (PushRegistration) -> Unit, onWake: () -> Boolean, onCleared: () -> Unit = {}) {
        PushService.onNewToken = { token -> onNewToken(PushRegistration(token, "fcm")) }
        PushService.onWake = onWake
    }

    /** Fetch the current FCM token, calling back on success. No-op (throws
     *  swallowed) when Firebase is not configured on this build. */
    fun fetchToken(context: Context, onToken: (PushRegistration) -> Unit) {
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> onToken(PushRegistration(token, "fcm")) }
        } catch (e: Exception) {
            // Firebase not configured on this build; polling covers delivery.
        }
    }

    /** FCM tokens need no confirmation step. */
    fun markGatewayRegistered(context: Context, reg: PushRegistration) {}

    /** Never set on Play builds. */
    fun pendingClear(context: Context): Boolean = false

    fun commitClear(context: Context) {}

    /** FCM needs no distributor setup or background scheduling. */
    fun refresh(context: Context, gatewayUsable: Boolean) {}

    fun mode(context: Context): PushMode = PushMode.FCM

    fun distributors(context: Context): List<PushDistributor> = emptyList()

    fun currentDistributor(context: Context): String? = null

    fun useDistributor(context: Context, packageName: String) {}

    fun useBackgroundCheck(context: Context) {}
}

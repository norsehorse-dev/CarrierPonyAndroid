// PushService.kt
// CarrierPony Android — PLAY FLAVOR ONLY
//
// The FCM receiving side, the Android counterpart of iOS Core/App/PushService
// (contentless APNs). Lives in the play source set because it extends a
// Firebase class; the FOSS build has no Firebase on the classpath and relies on
// polling instead, so nothing here is referenced from that variant.
//
// Pushes are wake signals only: the relay sends a data-only FCM message with
// zero message content, and this service either nudges a live ChatStore to
// refresh (app in foreground) or posts one generic local notification (app in
// background) via the shared PushNotifier. Message content never rides the push
// pipe — Google's servers see that *something* arrived, never what.
//
// Token changes re-register with the relay through the hook AppModel installs
// (via PushSupport.install); tokens are meaningless without the relay's
// challenge auth, and the relay scopes them to (fingerprint, device).

package com.carrierpony.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PushService : FirebaseMessagingService() {

    companion object {
        /** Installed by AppModel (through PushSupport.install): called with a
         *  fresh token so it can be re-registered with the relay. */
        @Volatile
        var onNewToken: (String) -> Unit = {}

        /** Installed by AppModel (through PushSupport.install): returns true if a
         *  live ChatStore handled the wake (app foregrounded and refreshing), in
         *  which case no notification is posted. */
        @Volatile
        var onWake: () -> Boolean = { false }
    }

    override fun onNewToken(token: String) {
        android.util.Log.i("CarrierPony", "FCM token rotated (${token.take(12)}...)")
        onNewToken.invoke(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // A silent wake (relay sets data["silent"]="1") is a control message,
        // self-copy, or profile sync — not a real message. We still let a live
        // ChatStore refresh, but we never post a banner for it. This is the
        // Android half of the phantom-notification fix.
        val silent = message.data["silent"] == "1"
        android.util.Log.i("CarrierPony", "push wake received (data=${message.data.keys}, silent=$silent)")
        // Contentless by design: ignore any payload beyond the wake itself.
        val handledLive = try {
            onWake.invoke()
        } catch (e: Exception) {
            false
        }
        when {
            handledLive -> android.util.Log.i("CarrierPony", "wake handled live (foreground refresh)")
            silent -> android.util.Log.i("CarrierPony", "silent wake; banner suppressed")
            else -> {
                android.util.Log.i("CarrierPony", "posting generic notification")
                PushNotifier.postGenericNotification(this)
            }
        }
    }
}

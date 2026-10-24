// DemoStart.kt
// CarrierPony Android
//
// Where a screenshot run asks the app to open. Built only by the debug
// Demo object (src/debug); the release Demo stub always returns null, so
// release builds never construct one.

package com.carrierpony.app.demo

data class DemoStart(
    val screen: String,       // inbox, chat, group, files, pair
    val chatPeerHex: String?, // the 1:1 conversation the "chat" screen opens
    val groupID: String?      // the group the "group" screen opens
)

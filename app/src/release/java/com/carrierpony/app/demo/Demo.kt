// Demo.kt (release source set)
// CarrierPony Android
//
// No-op twin of the debug Demo object (src/debug), so MainActivity compiles
// the same in every build type while release builds carry no demo data and
// no screenshot hooks.

package com.carrierpony.app.demo

import android.content.Context
import android.content.Intent

object Demo {
    fun isActive(context: Context): Boolean = false
    fun prepare(context: Context, intent: Intent?): DemoStart? = null
}

package com.carrierpony.app

import com.carrierpony.app.ui.KeyApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyAppsTest {

    @Test
    fun noKeysPickedGivesNothing() {
        assertNull(KeyApps.firstKeyId(null))
        assertNull(KeyApps.firstKeyId(LongArray(0)))
    }

    @Test
    fun onlyTheFirstPickedKeyIsUsed() {
        assertEquals(42L, KeyApps.firstKeyId(longArrayOf(42L, 7L)))
    }

    @Test
    fun anEmptyKeyIdIsSkipped() {
        assertEquals(7L, KeyApps.firstKeyId(longArrayOf(0L, 7L)))
        assertNull(KeyApps.firstKeyId(longArrayOf(0L)))
    }

    @Test
    fun aNegativeKeyIdIsStillAKey() {
        assertEquals(-5L, KeyApps.firstKeyId(longArrayOf(-5L)))
    }
}

// AttachmentThumbnail.kt
// CarrierPony Android
//
// Sampled bitmap decoding for attachment previews, the counterpart of iOS
// AttachmentThumbnail (ImageIO downsampling). inSampleSize keeps a 12 MP
// photo from occupying 50 MB of heap just to show a 240dp bubble.

package com.carrierpony.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object AttachmentThumbnail {

    fun make(path: String, maxPixel: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPixel || bounds.outHeight / (sample * 2) >= maxPixel) {
                sample *= 2
            }
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            null
        }
    }
}

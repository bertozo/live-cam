package com.xbertz.livecam

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/** Converts a CameraX YUV_420_888 frame to an upright JPEG, ready to broadcast. */
fun ImageProxy.toUprightJpeg(quality: Int = 80): ByteArray {
    val nv21 = yuv420ToNv21(this)
    val (upright, uprightWidth, uprightHeight) = rotateNv21(nv21, width, height, imageInfo.rotationDegrees)

    val yuvImage = YuvImage(upright, ImageFormat.NV21, uprightWidth, uprightHeight, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, uprightWidth, uprightHeight), quality, out)
    return out.toByteArray()
}

/** Packs the Y, U and V planes of a YUV_420_888 image into a single NV21 byte array. */
private fun yuv420ToNv21(image: ImageProxy): ByteArray {
    val width = image.width
    val height = image.height
    val nv21 = ByteArray(width * height * 3 / 2)

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    var offset = 0
    val yBuffer = yPlane.buffer
    val yRowStride = yPlane.rowStride
    for (row in 0 until height) {
        yBuffer.position(row * yRowStride)
        yBuffer.get(nv21, offset, width)
        offset += width
    }

    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val uRowStride = uPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    val vRowStride = vPlane.rowStride
    val vPixelStride = vPlane.pixelStride
    val chromaHeight = height / 2
    val chromaWidth = width / 2

    for (row in 0 until chromaHeight) {
        val uRowStart = row * uRowStride
        val vRowStart = row * vRowStride
        for (col in 0 until chromaWidth) {
            nv21[offset++] = vBuffer.get(vRowStart + col * vPixelStride)
            nv21[offset++] = uBuffer.get(uRowStart + col * uPixelStride)
        }
    }

    return nv21
}

/**
 * Rotates an NV21 buffer by 0/90/180/270 degrees and returns the rotated bytes plus the
 * resulting width/height (swapped for 90/270).
 *
 * This runs on the raw YUV *before* JPEG encoding, so each frame is compressed exactly once.
 * The previous approach (encode, decode back to a Bitmap, rotate, re-encode) both cost a lot
 * of CPU time per frame - the main source of the visible streaming lag - and stacked a second
 * lossy JPEG pass on top of the first, which is the main source of the soft/blocky image.
 */
private fun rotateNv21(data: ByteArray, width: Int, height: Int, rotation: Int): Triple<ByteArray, Int, Int> {
    if (rotation == 0) return Triple(data, width, height)

    val outWidth = if (rotation % 180 == 0) width else height
    val outHeight = if (rotation % 180 == 0) height else width
    val output = ByteArray(data.size)
    val frameSize = width * height
    val swap = rotation % 180 != 0
    val flipX = rotation == 90 || rotation == 180
    val flipY = rotation == 180 || rotation == 270

    for (j in 0 until height) {
        for (i in 0 until width) {
            val yIn = j * width + i
            val chromaIn = frameSize + (j shr 1) * width + (i and 1.inv())

            val iOut = if (swap) j else i
            val jOut = if (swap) i else j
            val iFinal = if (flipX) outWidth - iOut - 1 else iOut
            val jFinal = if (flipY) outHeight - jOut - 1 else jOut

            val yOut = jFinal * outWidth + iFinal
            val chromaOut = frameSize + (jFinal shr 1) * outWidth + (iFinal and 1.inv())

            output[yOut] = data[yIn]
            output[chromaOut] = data[chromaIn]
            output[chromaOut + 1] = data[chromaIn + 1]
        }
    }
    return Triple(output, outWidth, outHeight)
}

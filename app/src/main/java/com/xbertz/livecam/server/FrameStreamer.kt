package com.xbertz.livecam.server

import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Hands the latest JPEG frame to one connected viewer, one multipart chunk at a time.
 * [push] is called from the camera analyzer thread; [nextChunk] blocks (from the socket's
 * writer thread) until a new frame arrives or the stream is closed.
 */
class FrameStreamer(private val boundary: String = "livecamframe") {
    private val lock = Object()
    private var latestFrame: ByteArray? = null
    private var closed = false

    fun push(jpeg: ByteArray) {
        synchronized(lock) {
            if (closed) return
            latestFrame = jpeg
            lock.notifyAll()
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    /** Blocks until a frame is available, returning the full multipart chunk, or null once closed. */
    fun nextChunk(): ByteArray? {
        synchronized(lock) {
            while (latestFrame == null && !closed) lock.wait()
            if (closed) return null
            val jpeg = latestFrame!!
            latestFrame = null
            val header = "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
                .toByteArray(StandardCharsets.US_ASCII)
            val footer = "\r\n".toByteArray(StandardCharsets.US_ASCII)
            return header + jpeg + footer
        }
    }
}

/** Adapts [FrameStreamer] to the blocking [InputStream] NanoHTTPD's chunked response reads from. */
class MjpegInputStream(
    private val streamer: FrameStreamer,
    private val onClosed: () -> Unit,
) : InputStream() {
    private var buffer = ByteArray(0)
    private var pos = 0
    private var streamClosed = false

    override fun read(): Int {
        val single = ByteArray(1)
        val n = read(single, 0, 1)
        return if (n <= 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= buffer.size) {
            val chunk = streamer.nextChunk() ?: return -1
            buffer = chunk
            pos = 0
        }
        val n = minOf(len, buffer.size - pos)
        System.arraycopy(buffer, pos, b, off, n)
        pos += n
        return n
    }

    override fun close() {
        if (streamClosed) return
        streamClosed = true
        streamer.close()
        onClosed()
    }
}

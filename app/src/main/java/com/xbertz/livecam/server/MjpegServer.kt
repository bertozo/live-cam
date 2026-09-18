package com.xbertz.livecam.server

import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private const val BOUNDARY = "livecamframe"
private const val REMEMBER_MAX_AGE_SECONDS = 30 * 24 * 60 * 60 // 30 days

/**
 * Minimal local-network web server: a login page gated by [accessCode], and an MJPEG
 * stream ("/stream") only reachable once that code has been entered from the same browser
 * (tracked by an in-memory session cookie, not by user account).
 */
class MjpegServer(
    port: Int,
    private val accessCode: String,
    private val onViewerCountChanged: (Int) -> Unit,
) : NanoHTTPD(port) {

    init {
        // Disable Nagle's algorithm: MJPEG frames go out as several small writes per frame,
        // and batching them for a full TCP segment (Nagle's default) adds visible lag.
        setServerSocketFactory {
            object : ServerSocket() {
                override fun accept(): Socket {
                    val socket = super.accept()
                    socket.tcpNoDelay = true
                    return socket
                }
            }
        }
    }

    private val activeSessions = CopyOnWriteArrayList<String>()
    private val streamers = CopyOnWriteArrayList<FrameStreamer>()

    fun broadcastFrame(jpeg: ByteArray) {
        for (streamer in streamers) streamer.push(jpeg)
    }

    fun stopServer() {
        for (streamer in streamers.toList()) streamer.close()
        stop()
    }

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.GET && session.uri == "/" -> serveIndex(session)
            session.method == Method.POST && session.uri == "/auth" -> serveAuth(session)
            session.method == Method.GET && session.uri == "/stream" -> serveStream(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun serveIndex(session: IHTTPSession): Response {
        val authorized = sessionTokenOf(session)?.let { activeSessions.contains(it) } == true
        val html = if (authorized) viewerHtml() else loginHtml(showError = false)
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun serveAuth(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        runCatching { session.parseBody(body) }
        val submittedCode = session.parms["code"]?.trim()

        if (submittedCode != accessCode) {
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", loginHtml(showError = true))
        }

        val token = UUID.randomUUID().toString()
        activeSessions += token
        val remember = session.parms["remember"] != null
        val cookie = buildString {
            append("session=$token; Path=/; HttpOnly")
            if (remember) append("; Max-Age=$REMEMBER_MAX_AGE_SECONDS")
        }
        val response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", viewerHtml())
        response.addHeader("Set-Cookie", cookie)
        return response
    }

    private fun serveStream(session: IHTTPSession): Response {
        val token = sessionTokenOf(session)
        if (token == null || !activeSessions.contains(token)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Digite o codigo de acesso em / primeiro.")
        }

        val streamer = FrameStreamer()
        streamers += streamer
        onViewerCountChanged(streamers.size)

        val input: InputStream = MjpegInputStream(streamer) {
            streamers -= streamer
            onViewerCountChanged(streamers.size)
        }

        val response = newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=$BOUNDARY", input)
        response.addHeader("Cache-Control", "no-cache, private")
        response.addHeader("Pragma", "no-cache")
        return response
    }

    private fun sessionTokenOf(session: IHTTPSession): String? {
        val cookieHeader = session.headers["cookie"] ?: return null
        return cookieHeader.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("session=") }
            ?.substringAfter("session=")
    }

    private fun loginHtml(showError: Boolean) = """
        <!DOCTYPE html>
        <html lang="pt-br">
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>LiveCam</title>
            <style>
                body { font-family: sans-serif; background: #111; color: #eee; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }
                form { background: #1c1c1c; padding: 32px; border-radius: 12px; text-align: center; width: 260px; }
                h1 { font-size: 20px; margin-top: 0; }
                input { width: 100%; box-sizing: border-box; font-size: 24px; text-align: center; letter-spacing: 4px; padding: 12px; border-radius: 8px; border: 1px solid #444; background: #000; color: #fff; margin-bottom: 16px; }
                label { display: flex; align-items: center; gap: 8px; font-size: 14px; color: #ccc; margin-bottom: 16px; text-align: left; }
                button { width: 100%; padding: 12px; border-radius: 8px; border: none; background: #E53935; color: #fff; font-size: 16px; cursor: pointer; }
                p.error { color: #ff6b6b; margin-top: 0; }
            </style>
        </head>
        <body>
            <form method="POST" action="/auth">
                <h1>Codigo de acesso</h1>
                ${if (showError) "<p class=\"error\">Codigo invalido, tente novamente.</p>" else ""}
                <input name="code" inputmode="numeric" maxlength="6" autofocus autocomplete="off" placeholder="000000">
                <label><input type="checkbox" name="remember" style="width:auto;margin:0;"> Lembrar este dispositivo</label>
                <button type="submit">Entrar</button>
            </form>
        </body>
        </html>
    """.trimIndent()

    private fun viewerHtml() = """
        <!DOCTYPE html>
        <html lang="pt-br">
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>LiveCam</title>
            <style>
                body { margin: 0; background: #000; display: flex; align-items: center; justify-content: center; height: 100vh; }
                img { max-width: 100%; max-height: 100%; }
            </style>
        </head>
        <body>
            <img src="/stream" alt="Transmissao ao vivo">
        </body>
        </html>
    """.trimIndent()
}

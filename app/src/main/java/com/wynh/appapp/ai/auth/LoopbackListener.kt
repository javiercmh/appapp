package com.wynh.appapp.ai.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

class LoopbackListener : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 500 }
    val redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback"

    suspend fun await(attempt: OAuthAttempt, timeoutMillis: Long = 600_000): CallbackResult = withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        try {
            while (System.nanoTime() < deadline) {
                coroutineContext.ensureActive()
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                socket.use {
                    it.soTimeout = 1_000
                    try {
                        // Bound header size and read byte-by-byte to avoid readLine allocating unbounded input.
                        val header = StringBuilder()
                        val input = it.getInputStream()
                        while (header.length < 8_192 && !header.endsWith("\r\n\r\n")) {
                            val next = input.read()
                            if (next == -1) break
                            header.append(next.toChar())
                        }
                        val request = header.toString().substringBefore("\r\n").split(' ')
                        if (!header.endsWith("\r\n\r\n") || request.size != 3 || request[0] != "GET") {
                            reply(it.getOutputStream(), false)
                            return@use
                        }
                        val result = try { attempt.validateCallback(request[1]) } catch (_: AuthFailure) {
                            reply(it.getOutputStream(), false)
                            return@use
                        }
                        reply(it.getOutputStream(), true)
                        return@withContext result
                    } catch (_: SocketTimeoutException) {
                        // Ignore incomplete requests; the genuine callback may still arrive.
                    } catch (_: java.io.IOException) {
                        // A disconnected browser request must not terminate the authentication attempt.
                    }
                }
            }
            throw AuthFailure("Sign-in timed out. Return to AppApp and try again.")
        } finally { close() }
    }

    override fun close() { server.close() }

    private fun reply(output: java.io.OutputStream, accepted: Boolean) {
        val body = if (accepted) "<!doctype html><html lang=\"en\"><meta name=\"viewport\" content=\"width=device-width\"><title>AppApp</title><h1>Return to AppApp</h1><p>The browser step is complete. Open AppApp to finish checking your account.</p></html>" else "Invalid request."
        val bytes = body.toByteArray(Charsets.UTF_8)
        output.write(("HTTP/1.1 ${if (accepted) "200 OK" else "400 Bad Request"}\r\n" +
            "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nContent-Security-Policy: default-src 'none'\r\n" +
            "Referrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").toByteArray())
        output.write(bytes)
        output.flush()
    }
}

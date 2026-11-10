package com.wynh.appapp.ai.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Real sockets, so OkHttp's own read and whole-call timeouts fire as they do on a device. */
class HttpGatewayTest {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val url = "http://127.0.0.1:${server.localPort}/responses"
    @After fun close() { server.close() }

    /** Serves one connection: [write] gets the stream after the request headers are read. */
    private fun serve(write: (OutputStream) -> Unit) = thread(isDaemon = true) {
        runCatching {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                while (input.readLine()?.isNotEmpty() == true) Unit
                write(socket.getOutputStream())
            }
        }
    }

    private fun OutputStream.send(text: String) { write(text.toByteArray()); flush() }
    private fun OutputStream.headers(type: String) = send("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nConnection: close\r\n\r\n")
    private fun delta(text: String) = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"$text\"}\n\n"

    private fun gateway(readMs: Long, callMs: Long, maxOutputUnits: Int = HttpGateway.MIN_OUTPUT_UNITS) = HttpGateway(
        OkHttpClient.Builder().readTimeout(readMs, TimeUnit.MILLISECONDS).callTimeout(callMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false).build(), maxOutputUnits)

    private fun failure(block: suspend () -> Unit): ApiFailure = try { runBlocking { block() }; fail("Expected failure"); throw AssertionError() }
        catch (e: ApiFailure) { e }

    @Test fun `a stream that keeps arriving outlives the whole-call timeout`() {
        serve { out ->
            out.headers("text/event-stream")
            repeat(8) { out.send(delta("a")); Thread.sleep(100) }
            out.send("data: {\"type\":\"response.completed\"}\n\n")
        }
        // The base client would stop any call after 300 ms; generation has no such deadline.
        val text = runBlocking { gateway(readMs = 2_000, callMs = 300).textStream(Request.Builder().url(url).build()) }
        assertEquals("aaaaaaaa", text)
    }

    @Test fun `a stalled stream is a timeout, not an interruption`() {
        serve { out -> out.headers("text/event-stream"); out.send(delta("a")); Thread.sleep(3_000) }
        val e = failure { gateway(readMs = 300, callMs = 0).textStream(Request.Builder().url(url).build()) }
        assertEquals(ApiFailure.Kind.TIMEOUT, e.kind)
        assertTrue(e.userMessage.contains("stopped sending data"))
    }

    @Test fun `other requests keep their whole-call timeout and report it as a timeout`() {
        serve { out -> out.headers("application/json"); out.send("{"); Thread.sleep(3_000) }
        val e = failure { gateway(readMs = 2_000, callMs = 300).json(Request.Builder().url(url).build()) }
        assertEquals(ApiFailure.Kind.TIMEOUT, e.kind)
    }

    @Test fun `an oversized completion body is too large, not malformed`() {
        serve { out -> out.headers("application/json"); out.send("{\"pad\":\"${"p".repeat(1_000)}\"}") }
        val e = failure { gateway(readMs = 2_000, callMs = 0, maxOutputUnits = 100).completion(Request.Builder().url(url).build()) }
        assertEquals(ApiFailure.Kind.RESPONSE_TOO_LARGE, e.kind)
    }

    @Test fun `the text bound follows the heap within fixed limits`() {
        assertEquals(HttpGateway.MIN_OUTPUT_UNITS, HttpGateway.deviceOutputUnits(64L shl 20))
        assertEquals(8_388_608, HttpGateway.deviceOutputUnits(256L shl 20))
        assertEquals(16_777_216, HttpGateway.deviceOutputUnits(512L shl 20))
        assertEquals(HttpGateway.MAX_OUTPUT_UNITS, HttpGateway.deviceOutputUnits(8L shl 30))
    }
}

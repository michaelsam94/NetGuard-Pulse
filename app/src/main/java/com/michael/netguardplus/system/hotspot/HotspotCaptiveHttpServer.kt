package com.michael.netguardplus.system.hotspot

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal HTTP server on the hotspot gateway that serves the data-limit captive portal page.
 */
class HotspotCaptiveHttpServer(
    private val scope: CoroutineScope,
    private val bindIp: String,
    private val pageHtml: () -> String
) {
    private val job = AtomicReference<Job?>(null)
    @Volatile
    var isRunning = false
        private set

    fun start() {
        if (job.get()?.isActive == true) return
        job.set(scope.launch(Dispatchers.IO) { runLoop() })
    }

    fun stop() {
        job.getAndSet(null)?.cancel()
        isRunning = false
    }

    private suspend fun runLoop() {
        val server = openServer(bindIp) ?: return
        isRunning = true
        Log.i(TAG, "Captive portal HTTP server listening on $bindIp:$HTTP_PORT")
        try {
            while (scope.isActive) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    if (!scope.isActive) break
                    Log.d(TAG, "HTTP accept ended: ${e.message}")
                    break
                }
                scope.launch(Dispatchers.IO) {
                    handleClient(client)
                }
            }
        } finally {
            isRunning = false
            runCatching { server.close() }
            Log.i(TAG, "Captive portal HTTP server stopped")
        }
    }

    private fun openServer(bindIp: String): ServerSocket? {
        return try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bindIp, HTTP_PORT))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not bind HTTP on $bindIp:$HTTP_PORT (${e.message}) — trying any interface")
            try {
                ServerSocket(HTTP_PORT).apply { reuseAddress = true }
            } catch (e2: Exception) {
                Log.e(TAG, "Captive portal HTTP server failed to bind port $HTTP_PORT", e2)
                null
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                BufferedReader(InputStreamReader(client.getInputStream())).readLine()
                val body = pageHtml().toByteArray(Charsets.UTF_8)
                val headers = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/html; charset=utf-8\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                val out = client.getOutputStream()
                out.write(headers.toByteArray(Charsets.US_ASCII))
                out.write(body)
                out.flush()
            } catch (e: Exception) {
                Log.v(TAG, "HTTP client handling failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "HotspotCaptiveHttp"
        private const val HTTP_PORT = 80
    }
}

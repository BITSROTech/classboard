// core/net/WsClient.kt  (예시)
package co.kys.classboard.net

import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

class WsClient(
    private val url: String,
    private val onOpen: () -> Unit = {},
    private val onMessage: (ByteArray) -> Unit = {},
    private val onClosed: () -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {}
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null

    fun connect() {
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onOpen()
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(bytes.toByteArray())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {

            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onFailure(t)
            }
        })
    }

    fun send(bytes: ByteArray) {
        ws?.send(ByteString.of(*bytes))
    }

    fun close() {
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
    }
}

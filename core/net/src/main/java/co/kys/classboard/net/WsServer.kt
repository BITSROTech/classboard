// core/net/src/main/java/co/kys/classboard/net/WsServer.kt
package co.kys.classboard.net

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Collections
import java.util.LinkedHashSet

class WsServer(
    port: Int,
    private val onClientCount: (Int) -> Unit = {},
    private val onBinary: (WebSocket, ByteArray) -> Unit = { _, _ -> },
    private val onText: (WebSocket, String) -> Unit = { _, _ -> },
    private val onOpen: (WebSocket) -> Unit = {},
    private val onClosed: (WebSocket) -> Unit = {}
) : WebSocketServer(InetSocketAddress(port)) {

    // 타입 명시로 추론 에러 방지
    private val conns: MutableSet<WebSocket> =
        Collections.synchronizedSet(LinkedHashSet())

    override fun onStart() {
        runCatching { this.connectionLostTimeout = 30 }
    }
    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        conns.add(conn)
        onClientCount(conns.size)
        onOpen.invoke(conn)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        conns.remove(conn)
        onClientCount(conns.size)
        onClosed.invoke(conn)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        onText.invoke(conn, message)
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        val bytes = ByteArray(message.remaining())
        message.get(bytes)
        onBinary.invoke(conn, bytes)
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        ex.printStackTrace()
    }

    /** 전체(또는 [except] 제외) 바이너리 방송 */
    fun broadcast(bytes: ByteArray, except: WebSocket? = null) {
        synchronized(conns) {
            for (c in conns) {
                if (except != null && c == except) continue
                runCatching { c.send(bytes) }
            }
        }
    }

    /** 텍스트 방송 (옵션) */
    fun broadcastText(text: String, except: WebSocket? = null) {
        synchronized(conns) {
            for (c in conns) {
                if (except != null && c == except) continue
                runCatching { c.send(text) }
            }
        }
    }
}

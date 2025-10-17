// features/lobby/src/main/java/co/kys/classboard/lobby/HostViewModel.kt
package co.kys.classboard.lobby

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.runtime.mutableStateListOf
import androidx.core.graphics.scale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.kys.classboard.net.WsServer
import co.kys.classboard.proto.MsgEnvelope
import co.kys.classboard.proto.DrawPermit
import co.kys.classboard.proto.Ser
import co.kys.classboard.proto.ShortPoint
import co.kys.classboard.proto.StrokeEnd
import co.kys.classboard.proto.StrokeMoveBatch
import co.kys.classboard.proto.StrokeStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import org.java_websocket.WebSocket
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class HostViewModel : ViewModel() {
    private var server: WsServer? = null
    private val seq = AtomicLong(0)          // 서버가 부여하는 글로벌 시퀀스
    private val orderSeq = AtomicLong(0)     // 스냅샷 재생 순서 보장

    // UI
    val logs = mutableStateListOf<String>()
    val participants = mutableStateListOf<String>()
    private val connToUser = ConcurrentHashMap<WebSocket, String>()
    var clientCount: Int = 0; private set

    // ===== 스트로크 상태(서버 보관; 양자화 ShortPoint 원본 저장) =====
    private data class StrokeBuilder(
        val start: StrokeStart,
        val points: MutableList<ShortPoint>,
        var ended: Boolean = false,
        val order: Long
    )
    private val board = ConcurrentHashMap<String, StrokeBuilder>()

    // ===== 최신 배경 스냅샷(JSON bytes; {"type":"BG_SET",...,"data":"base64"}) =====
    @Volatile private var bgPayloadLatest: ByteArray? = null

    private fun uiLog(msg: String) {
        viewModelScope.launch(Dispatchers.Main) { logs += msg }
    }
    private fun addParticipant(uid: String) {
        viewModelScope.launch(Dispatchers.Main) {
            if (!participants.contains(uid)) participants += uid
        }
    }
    private fun removeParticipant(uid: String?) {
        if (uid == null) return
        viewModelScope.launch(Dispatchers.Main) { participants.remove(uid) }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 서버 시작/중지
    // ────────────────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalSerializationApi::class)
    fun start(port: Int = 8080) {
        if (server != null) { uiLog("Server already running"); return }

        val s = WsServer(
            port,
            onClientCount = { c -> clientCount = c; uiLog("Clients: $c") },
            onOpen = { conn ->
                uiLog("Client open: ${conn.remoteSocketAddress}")
                // 들어오자마자 스냅샷 제공(BG → Stroke)
                sendSnapshotTo(conn)
            },
            onBinary = { conn: WebSocket, bytes: ByteArray ->
                val env: MsgEnvelope = Ser.decode(bytes)

                when (env.type) {
                    "StrokeStart" -> {
                        val st: StrokeStart = Ser.decode(env.payload!!)
                        board.putIfAbsent(
                            st.strokeId,
                            StrokeBuilder(
                                start = st,
                                points = Collections.synchronizedList(mutableListOf()),
                                order = orderSeq.incrementAndGet()
                            )
                        )
                    }
                    "StrokeMoveBatch" -> {
                        val mb: StrokeMoveBatch = Ser.decode(env.payload!!)
                        board[mb.strokeId]?.points?.addAll(mb.points)
                    }
                    "StrokeEnd" -> {
                        val ed: StrokeEnd = Ser.decode(env.payload!!)
                        board[ed.strokeId]?.ended = true
                    }
                    "Hello" -> {
                        val uid = env.userId
                        connToUser[conn] = uid
                        addParticipant(uid)
                        uiLog("recv Hello from $uid")
                    }
                    // ✅ BG_*도 서버가 캐시/중계
                    "BG_SET" -> {
                        bgPayloadLatest = env.payload
                        uiLog("BG_SET cached (${bgPayloadLatest?.size ?: 0} bytes)")
                    }
                    "BG_CLEAR" -> {
                        bgPayloadLatest = null
                        uiLog("BG_CLEAR cached")
                    }
                    "BG_GOTO" -> {
                        // 페이지 숫자만 전달되는 경우(이미지는 BG_SET로만 유지)
                    }
                }

                // 전체 브로드캐스트(글로벌 시퀀스 부여)
                val confirmed = env.copy(globalSeq = seq.incrementAndGet())
                server?.broadcast(Ser.encode(confirmed))
            },
            onText = { _, text ->
                // 혹시 텍스트로 BG_*가 오는 환경을 위한 패스스루
                try {
                    when {
                        text.contains("\"type\":\"BG_SET\"") -> {
                            val payload = text.toByteArray(Charsets.UTF_8)
                            bgPayloadLatest = payload
                            val env = Ser.pack("BG_SET", "server", payload)
                            server?.broadcast(Ser.encode(env))
                            uiLog("BG_SET(text) cached+broadcast")
                        }
                        text.contains("\"type\":\"BG_CLEAR\"") -> {
                            bgPayloadLatest = null
                            val env = Ser.pack("BG_CLEAR", "server", """{"type":"BG_CLEAR"}""".toByteArray())
                            server?.broadcast(Ser.encode(env))
                            uiLog("BG_CLEAR(text) cached+broadcast")
                        }
                        text.contains("\"type\":\"BG_GOTO\"") -> {
                            val env = Ser.pack("BG_GOTO", "server", text.toByteArray(Charsets.UTF_8))
                            server?.broadcast(Ser.encode(env))
                            uiLog("BG_GOTO(text) broadcast")
                        }
                        else -> uiLog("text frame ignored: len=${text.length}")
                    }
                } catch (_: Throwable) {
                    uiLog("text frame parse error")
                }
            },
            onClosed = { conn ->
                val uid = connToUser.remove(conn)
                removeParticipant(uid)
                uiLog("Client closed: ${conn.remoteSocketAddress} ($uid)")
            }
        ).apply {
            runCatching { this.connectionLostTimeout = 30 }
        }

        s.start()
        server = s
        uiLog("Server started on :$port")
    }

    /** 외부에서 서버 중지 */
    fun stop(clearBoard: Boolean = true) {
        runCatching { server?.stop() }
        server = null
        clientCount = 0
        viewModelScope.launch(Dispatchers.Main) {
            logs += "Server stopped"
            participants.clear()
        }
        connToUser.clear()

        if (clearBoard) {
            board.clear()
            bgPayloadLatest = null
            seq.set(0)
            orderSeq.set(0)
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 스냅샷(신규 참가자에게 전송): BG_SET → Stroke 들
    // ────────────────────────────────────────────────────────────────────────────
    @OptIn(ExperimentalSerializationApi::class)
    private fun sendSnapshotTo(conn: WebSocket) {
        // 1) 배경 먼저
        bgPayloadLatest?.let { bg ->
            val bgEnv = Ser.pack("BG_SET", "server", bg)
            runCatching { conn.send(Ser.encode(bgEnv)) }
            uiLog("sendSnapshot: BG_SET sent (${bg.size} bytes)")
        }

        // 2) 스트로크 재생(생성 순서)
        val items = board.values.sortedBy { it.order }
        for (b in items) {
            runCatching {
                val startEnv = Ser.pack("StrokeStart", "server", b.start)
                conn.send(Ser.encode(startEnv))
            }
            val copyPoints = synchronized(b.points) { b.points.toList() }
            for (chunk in copyPoints.chunked(64)) {
                val moveEnv = Ser.pack("StrokeMoveBatch", "server", StrokeMoveBatch(b.start.strokeId, chunk))
                runCatching { conn.send(Ser.encode(moveEnv)) }
            }
            if (b.ended) {
                val endEnv = Ser.pack("StrokeEnd", "server", StrokeEnd(b.start.strokeId))
                runCatching { conn.send(Ser.encode(endEnv)) }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // ✅ 교사용: 서버에 직접 BG_* 밀어넣는 API (교사 소켓 연결 유무와 무관)
    // ────────────────────────────────────────────────────────────────────────────

    /** 교사가 PDF 페이지를 렌더한 비트맵을 서버로 즉시 브로드캐스트 + 캐시 */
    @SuppressLint("UseKtx")
    @OptIn(ExperimentalSerializationApi::class)
    fun pushBgSetFromBitmap(
        docId: String,
        page: Int,
        bmp: Bitmap,
        pageCount: Int? = null,
        jpegQuality: Int = 72,       // 용량/성능 절충 (ANR 방지에 도움)
        maxWidth: Int = 1920         // 과도한 해상도 제한
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // 1) 필요시 크기 축소
            val scale = if (bmp.width > maxWidth) maxWidth.toFloat() / bmp.width else 1f
            val scaled = if (scale < 1f) {
                bmp.scale((bmp.width * scale).toInt(), (bmp.height * scale).toInt())
            } else bmp

            // 2) JPEG로 압축
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
            val bytes = baos.toByteArray()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            // 3) JSON payload 구성
            val w = scaled.width
            val h = scaled.height
            val pageCountJson = pageCount?.let { ",\"pageCount\":$it" } ?: ""
            val json = """{"type":"BG_SET","docId":"$docId","page":$page,"w":$w,"h":$h$pageCountJson,"fmt":"jpg","data":"$b64"}"""
            val payload = json.toByteArray(Charsets.UTF_8)

            // 4) 캐시 + 브로드캐스트
            bgPayloadLatest = payload
            val env = Ser.pack("BG_SET", "server", payload)
            server?.broadcast(Ser.encode(env))
            if (server == null) uiLog("ERROR: Server is null in pushBgSetFromBitmap!")

            uiLog("pushBgSetFromBitmap: page=$page ${w}x$h bytes=${bytes.size} (cached+broadcast)")
        }
    }

    /** 배경 비우기 */
    @OptIn(ExperimentalSerializationApi::class)
    fun pushBgClear() {
        bgPayloadLatest = null
        val env = Ser.pack("BG_CLEAR", "server", """{"type":"BG_CLEAR"}""".toByteArray())
        server?.broadcast(Ser.encode(env))
        uiLog("pushBgClear: broadcast")
    }

    /** (선택) 페이지 숫자만 알림 — 이미지는 BG_SET로만 동기화 */
    @OptIn(ExperimentalSerializationApi::class)
    fun pushBgGoto(page: Int) {
        val env = Ser.pack("BG_GOTO", "server", """{"type":"BG_GOTO","page":$page}""".toByteArray())
        server?.broadcast(Ser.encode(env))
        uiLog("pushBgGoto: page=$page")
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { server?.stop() }
        participants.clear()
        connToUser.clear()
        board.clear()
        bgPayloadLatest = null
    }
}
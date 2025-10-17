package co.kys.classboard.lobby

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.kys.classboard.net.EventBus
import co.kys.classboard.net.WsClient
import co.kys.classboard.proto.Hello
import co.kys.classboard.proto.MsgEnvelope
import co.kys.classboard.proto.Ser
import co.kys.classboard.proto.DrawPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.random.Random

class JoinViewModel : ViewModel() {
    private var client: WsClient? = null

    // ===== UI / 연결 상태 =====
    val logs = mutableStateListOf<String>()
    val isConnected = mutableStateOf(false)
    val canDraw = mutableStateOf(false)

    // ===== 문서 배경(학생/교사 공통 바인딩용) =====
    val bgBitmap = mutableStateOf<Bitmap?>(null)
    val bgPage = mutableStateOf(0)
    val bgPageCount = mutableStateOf(0)

    // ===== 자동 재연결 =====
    private var autoReconnect: Boolean = true
    private var lastUrl: String? = null
    private var lastUserId: String? = null
    private var reconnectJob: Job? = null
    private var retryAttempt: Int = 0

    private val baseDelayMs = 500L
    private val maxDelayMs = 10_000L

    private fun uiLog(msg: String) {
        viewModelScope.launch(Dispatchers.Main) { logs += msg }
    }
    private fun setConnected(value: Boolean) {
        viewModelScope.launch(Dispatchers.Main) { isConnected.value = value }
    }

    fun setAutoReconnect(enabled: Boolean) {
        autoReconnect = enabled
        if (!enabled) {
            reconnectJob?.cancel()
            reconnectJob = null
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 연결 수명주기
    // ────────────────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalSerializationApi::class)
    fun connect(url: String, userId: String) {
        lastUrl = url
        lastUserId = userId
        autoReconnect = true

        runCatching { client?.close() }
        reconnectJob?.cancel()
        reconnectJob = null

        retryAttempt = 0
        openConnection(url, userId)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun openConnection(url: String, userId: String) {
        try {
            client = WsClient(
                url = url,
                onOpen = {
                    uiLog("Connected: $url")
                    setConnected(true)
                    retryAttempt = 0
                    reconnectJob?.cancel()
                    reconnectJob = null

                    // 보드 스트로크 전송 파이프 연결
                    EventBus.send = { env ->
                        val uidNow = lastUserId ?: userId
                        val env2 = env.copy(userId = uidNow)
                        client?.send(Ser.encode(env2))
                    }

                    // 핸드셰이크
                    val env = Ser.pack("Hello", userId, Hello("Hi from $userId"))
                    client?.send(Ser.encode(env))
                },
                onMessage = { bytes ->
                    // 1) Envelope(바이너리) 시도
                    val handled = runCatching {
                        val env: MsgEnvelope = Ser.decode(bytes)
                        // 스트로크 동기화 파이프 유지
                        EventBus.incoming.tryEmit(env)

                        if (env.type == "DrawPermit") {
                                env.payload?.let { p ->
                                    val dp: DrawPermit = Ser.decode(p)
                                    val myId = lastUserId ?: userId
                                    if (dp.userId == myId) {
                                        viewModelScope.launch(Dispatchers.Main) {
                                            canDraw.value = dp.allowed
                                            uiLog("DrawPermit: allowed=${dp.allowed}")
                                        }
                                    }
                                }
                                true
                            } else when (env.type) {
                            "Hello" -> {
                                env.payload?.let { p ->
                                    val hello: Hello = Ser.decode(p)
                                    uiLog("broadcast Hello: ${hello.message} (gSeq=${env.globalSeq})")
                                }
                                true
                            }
                            // === BG_* 수신 처리 =====================================
                            "BG_SET" -> { handleBgSetPayload(env.payload); true }
                            "BG_CLEAR" -> { handleBgClear(); true }
                            "BG_GOTO" -> { handleBgGotoPayload(env.payload); true }
                            else -> {
                                uiLog("msg ${env.type} gSeq=${env.globalSeq}")
                                true
                            }
                        }
                    }.getOrElse { false }

                    // 2) 실패 시: raw JSON 프레임 처리(상대가 텍스트로 보낸 경우)
                    if (!handled) {
                        val ok = handleRawJsonFrame(bytes)
                        if (!ok) uiLog("decode error: unknown frame format")
                    }
                },
                onClosed = {
                    uiLog("Closed")
                    setConnected(false)
                    EventBus.send = null
                    canDraw.value = false
                    scheduleReconnect()
                }
            ).also { it.connect() }
        } catch (t: Throwable) {
            uiLog("connect error: ${t.message ?: t.javaClass.simpleName}")
            setConnected(false)
            EventBus.send = null
                    canDraw.value = false
            scheduleReconnect()
        }
    }

    /** 지수 백오프 재연결 예약 */
    private fun scheduleReconnect() {
        if (!autoReconnect) return
        val url = lastUrl ?: return
        val userId = lastUserId ?: return
        if (isConnected.value) return
        if (reconnectJob?.isActive == true) return

        val exp = 1 shl min(retryAttempt, 6)
        val rawDelay = baseDelayMs * exp
        val jitterFactor = 0.8 + Random.nextDouble(0.0, 0.4)
        val delayMs = min((rawDelay * jitterFactor).toLong(), maxDelayMs)

        reconnectJob = viewModelScope.launch {
            uiLog("reconnect in ${delayMs}ms (attempt ${retryAttempt + 1})")
            delay(delayMs)
            if (!autoReconnect || isConnected.value) return@launch
            retryAttempt++
            openConnection(url, userId)
        }
    }

    fun disconnect() {
        autoReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        runCatching { client?.close() }
        setConnected(false)
        EventBus.send = null
                    canDraw.value = false
        uiLog("Disconnected (manual)")
    }

    override fun onCleared() {
        super.onCleared()
        autoReconnect = false
        reconnectJob?.cancel()
        runCatching { client?.close() }
        EventBus.send = null
                    canDraw.value = false
    }

    // ────────────────────────────────────────────────────────────────────────────
    // BG_* 전송 (교사용 헬퍼) — BoardScreen에서 사용
    // ────────────────────────────────────────────────────────────────────────────

    /** 선생님 단말에서 PDF 페이지 렌더 후 호출 */
    fun sendBgSetFromBitmap(docId: String, page: Int, bmp: Bitmap) {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        val bytes = baos.toByteArray()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val w = bmp.width
        val h = bmp.height
        val json = """{"type":"BG_SET","docId":"$docId","page":$page,"w":$w,"h":$h,"fmt":"jpg","data":"$b64"}"""
        val uid = lastUserId ?: "teacher"
        val env = Ser.pack("BG_SET", uid, json.toByteArray(Charsets.UTF_8))
        client?.send(Ser.encode(env))
    }

    fun sendBgClear() {
        val uid = lastUserId ?: "teacher"
        val env = Ser.pack("BG_CLEAR", uid, """{"type":"BG_CLEAR"}""".toByteArray())
        client?.send(Ser.encode(env))
    }

    fun sendBgGoto(page: Int) {
        val uid = lastUserId ?: "teacher"
        val env = Ser.pack("BG_GOTO", uid, """{"type":"BG_GOTO","page":$page}""".toByteArray())
        client?.send(Ser.encode(env))
    }

    // ────────────────────────────────────────────────────────────────────────────
    // BG_* 수신 처리
    // ────────────────────────────────────────────────────────────────────────────

    /** Envelope(payload) 기반 BG_SET 처리 */
    private fun handleBgSetPayload(payload: ByteArray?) {
        if (payload == null) return
        val raw = runCatching { Ser.decode<ByteArray>(payload) }.getOrElse { payload }
        val jsonStr = runCatching { String(raw, Charsets.UTF_8) }.getOrNull() ?: return
        val obj = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return
        if (obj.optString("type") != "BG_SET") return

        val page = obj.optInt("page", 0)
        val pageCountMaybe = if (obj.has("pageCount")) obj.optInt("pageCount", 0) else bgPageCount.value
        val dataB64 = obj.optString("data", "")
        val data = runCatching { Base64.decode(dataB64, Base64.DEFAULT) }.getOrNull() ?: return
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: return

        viewModelScope.launch(Dispatchers.Main) {
            bgBitmap.value = bmp
            bgPage.value = page
            bgPageCount.value = pageCountMaybe
            uiLog("BG_SET received: page=$page, pageCount=$pageCountMaybe")
        }
    }

    /** Envelope(payload) 기반 BG_GOTO 처리 */
    private fun handleBgGotoPayload(payload: ByteArray?) {
        if (payload == null) return
        val raw = runCatching { Ser.decode<ByteArray>(payload) }.getOrElse { payload }
        val jsonStr = runCatching { String(raw, Charsets.UTF_8) }.getOrNull() ?: return
        val obj = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return
        if (obj.optString("type") != "BG_GOTO") return

        val page = obj.optInt("page", bgPage.value)
        viewModelScope.launch(Dispatchers.Main) {
            bgPage.value = page
            // 이미지는 BG_SET로 갱신되는 것을 권장
        }
    }

    /** BG_CLEAR 처리 */
    private fun handleBgClear() {
        viewModelScope.launch(Dispatchers.Main) {
            bgBitmap.value = null
            bgPage.value = 0
            // pageCount 정책에 따라 유지/리셋 가능 — 여기선 유지
        }
    }

    /**
     * Raw JSON 프레임 fallback (상대가 Envelope 없이 텍스트로 보낼 때)
     */
    private fun handleRawJsonFrame(bytes: ByteArray): Boolean {
        val jsonStr = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return false
        val obj = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return false
        when (obj.optString("type")) {
            "BG_SET" -> {
                val page = obj.optInt("page", 0)
                val pageCountMaybe = obj.optInt("pageCount", bgPageCount.value)
                val dataB64 = obj.optString("data", "")
                val imgBytes = runCatching { Base64.decode(dataB64, Base64.DEFAULT) }.getOrNull()
                val bmp = imgBytes?.let {
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                    BitmapFactory.decodeByteArray(it, 0, it.size, opts)
                }
                if (bmp != null) {
                    viewModelScope.launch(Dispatchers.Main) {
                        bgBitmap.value = bmp
                        bgPage.value = page
                        bgPageCount.value = pageCountMaybe
                    }
                }
                return true
            }
            "BG_CLEAR" -> { handleBgClear(); return true }
            "BG_GOTO" -> {
                val page = obj.optInt("page", bgPage.value)
                viewModelScope.launch(Dispatchers.Main) { bgPage.value = page }
                return true
            }
            else -> return false
        }
    }
}
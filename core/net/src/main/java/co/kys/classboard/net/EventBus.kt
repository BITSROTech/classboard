package co.kys.classboard.net

import co.kys.classboard.proto.MsgEnvelope
import kotlinx.coroutines.flow.MutableSharedFlow

object EventBus {
    // 네트워크로부터 들어오는 메시지 스트림
    val incoming = MutableSharedFlow<MsgEnvelope>(extraBufferCapacity = 64)

    // 네트워크로 내보내는 함수 (JoinViewModel가 설정)
    @Volatile
    var send: ((MsgEnvelope) -> Unit)? = null
}

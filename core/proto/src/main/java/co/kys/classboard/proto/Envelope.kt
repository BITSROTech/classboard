package co.kys.classboard.proto

import kotlinx.serialization.Serializable

@Serializable
data class MsgEnvelope(
    val type: String,            // "Hello", "StrokeStart" ...
    val userId: String,          // null 허용 X (pack 과 일치)
    val localSeq: Long? = null,  // client temp seq
    val globalSeq: Long? = null, // server-ordered seq
    val payload: ByteArray? = null
)

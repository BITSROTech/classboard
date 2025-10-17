package co.kys.classboard.proto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

object Ser {
    @OptIn(ExperimentalSerializationApi::class)
    val cbor: Cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T> encode(value: T): ByteArray =
        cbor.encodeToByteArray(value)

    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T> decode(bytes: ByteArray): T =
        cbor.decodeFromByteArray(bytes)

    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified V> pack(
        type: String,
        userId: String,
        value: V,
        localSeq: Long? = null,
        globalSeq: Long? = null
    ): MsgEnvelope =
        MsgEnvelope(
            type = type,
            userId = userId,
            localSeq = localSeq,
            globalSeq = globalSeq,
            payload = encode(value)
        )
}

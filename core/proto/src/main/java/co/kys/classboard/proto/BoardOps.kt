package co.kys.classboard.proto

import kotlinx.serialization.Serializable

@Serializable
data class RequestSnapshot(val sinceGlobalSeq: Long? = null)

@Serializable
data class SnapshotState(
    val globalSeq: Long,
    val objects: List<BoardObject>
)

@Serializable
sealed class BoardObject {
    @Serializable
    data class Stroke(
        val id: String,
        val color: Long,
        val widthQ: Short,            // Short 양자화 두께
        val points: List<ShortPoint>  // Short 양자화 좌표
    ) : BoardObject()
}

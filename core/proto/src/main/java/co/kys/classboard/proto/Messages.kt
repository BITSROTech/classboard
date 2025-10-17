package co.kys.classboard.proto

import kotlinx.serialization.Serializable

/** 양자화 스케일(정규화 0..1 → Short) */
object Quant {
    const val POS: Int = 10_000   // 좌표 스케일
    const val WIDTH: Int = 1_000  // 두께 스케일
}

@Serializable
data class Hello(val message: String)

@Serializable
data class ShortPoint(val x: Short, val y: Short)

/** 선 시작: 정규화 두께를 Short 로 전송 */
@Serializable
data class StrokeStart(
    val strokeId: String,
    val color: Long,
    val widthQ: Short,           // width(0..1f) * Quant.WIDTH → Short
    val tool: String = "pen"
)

/** 이동 배치: 포인트 리스트를 ShortPoint 로 전송 */
@Serializable
data class StrokeMoveBatch(
    val strokeId: String,
    val points: List<ShortPoint>
)

@Serializable
data class StrokeEnd(val strokeId: String)

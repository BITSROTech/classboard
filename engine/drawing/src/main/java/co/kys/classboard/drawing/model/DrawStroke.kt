// engine/drawing/src/main/java/co/kys/classboard/drawing/model/DrawStroke.kt
package co.kys.classboard.drawing.model

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * 하나의 스트로크(선).
 * - width: 정규화 두께(0..1) — 렌더 시 캔버스 최소 변에 곱해서 px로 환산
 * - tool: "pen" | "eraser" (지우개는 BlendMode.Clear로 렌더)
 * - points: 정규화 좌표 목록(0..1). Compose 상태 리스트여야 손을 떼도 화면에 남습니다.
 */
data class DrawStroke(
    val id: String,
    val color: Long,
    val width: Float,
    val tool: String = "pen",
    val points: SnapshotStateList<FloatArray> = mutableStateListOf()
)

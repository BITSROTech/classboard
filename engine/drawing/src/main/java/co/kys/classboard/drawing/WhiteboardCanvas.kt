// engine/drawing/src/main/java/co/kys/classboard/drawing/WhiteboardCanvas.kt
package co.kys.classboard.drawing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import co.kys.classboard.drawing.model.DrawStroke
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Composable
fun WhiteboardCanvas(
    modifier: Modifier = Modifier,
    strokes: List<DrawStroke>,                 // 정규화 좌표/두께 저장
    currentColor: Long,
    currentWidth: Float,                       // px (슬라이더 값)
    currentTool: String,                       // "pen" | "eraser"
    onStrokeStart: (id: String, color: Long, widthNorm: Float, tool: String) -> Unit,
    onStrokeMoveBatch: (id: String, pointsNorm: List<FloatArray>) -> Unit,
    onStrokeEnd: (id: String) -> Unit
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val previewPoints = remember { mutableStateListOf<FloatArray>() }

    // 최신 UI 상태를 제스처 루프 안에서 읽기 위한 래퍼
    val colorState = rememberUpdatedState(currentColor)
    val widthPxState = rememberUpdatedState(currentWidth)
    val toolState = rememberUpdatedState(currentTool)

    // 적응형 코알레싱 파라미터
    val baseCountSlow = 14
    val baseCountFast = 8
    val maxMsHard = 16.0
    val distFlushThresh = 0.06f   // 정규화 거리 6% 이상 이동 시 즉시 flush

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(canDraw) {
                awaitEachGesture {
                    val down = awaitFirstDown()

                    val cw = canvasSize.width.toFloat()
                    val ch = canvasSize.height.toFloat()
                    if (cw <= 0f || ch <= 0f) return@awaitEachGesture
                    val minDim = min(cw, ch)

                    var activeTool = toolState.value
                    var activeWidthPx = widthPxState.value
                    var strokeId = UUID.randomUUID().toString()

                    val nx0 = down.position.x / cw
                    val ny0 = down.position.y / ch
                    var lastPt = floatArrayOf(nx0, ny0)

                    previewPoints.clear()
                    if (activeTool == "pen") previewPoints += lastPt

                    val widthNorm0 = activeWidthPx / max(1f, minDim)
                    onStrokeStart(strokeId, colorState.value, widthNorm0, activeTool)

                    // 첫 포인트 즉시 전송(지우개도 효과 주기 위해 동일)
                    onStrokeMoveBatch(strokeId, listOf(lastPt, lastPt))

                    var batch = mutableListOf<FloatArray>()
                    var lastFlushNs = System.nanoTime()
                    var lastX = nx0
                    var lastY = ny0
                    var distAcc = 0f

                    do {
                        val e = awaitPointerEvent()
                        val p = e.changes.first().position
                        val nx = p.x / cw
                        val ny = p.y / ch

                        // ── 도구/두께 실시간 변경 감지 ──────────────────────────
                        val newTool = toolState.value
                        val newWidthPx = widthPxState.value
                        val toolChanged = newTool != activeTool
                        val widthChanged = abs(newWidthPx - activeWidthPx) >= 0.1f // 더 민감하게

                        if (toolChanged || widthChanged) {
                            // 남은 배치 flush 후 종료
                            if (batch.isNotEmpty()) onStrokeMoveBatch(strokeId, batch)
                            onStrokeEnd(strokeId)

                            // 새 스트로크로 분할
                            strokeId = UUID.randomUUID().toString()
                            activeTool = newTool
                            activeWidthPx = newWidthPx

                            // 프리뷰 리셋(펜일 때만 보이게)
                            previewPoints.clear()
                            if (activeTool == "pen") previewPoints += lastPt

                            val wNorm = activeWidthPx / max(1f, minDim)
                            onStrokeStart(strokeId, colorState.value, wNorm, activeTool)

                            // 지우개도 즉시 반영되도록 동일 포인트 2번 전송(포인터가 안 움직여도 반영)
                            onStrokeMoveBatch(strokeId, listOf(lastPt, lastPt))

                            lastFlushNs = System.nanoTime()
                            batch = mutableListOf()
                            distAcc = 0f
                        }
                        // ───────────────────────────────────────────────────────

                        lastPt = floatArrayOf(nx, ny)
                        if (activeTool == "pen") previewPoints += lastPt

                        val dx = nx - lastX
                        val dy = ny - lastY
                        val seg = sqrt(dx * dx + dy * dy)
                        distAcc += seg
                        lastX = nx; lastY = ny

                        batch.add(lastPt)

                        val now = System.nanoTime()
                        val elapsedMs = (now - lastFlushNs) / 1_000_000.0
                        val isFast = (seg > 0.01f && elapsedMs < 12.0) || distAcc > distFlushThresh
                        val targetCount = if (isFast) baseCountFast else baseCountSlow

                        val needFlush = batch.size >= targetCount ||
                                elapsedMs >= maxMsHard ||
                                distAcc >= distFlushThresh

                        if (needFlush) {
                            onStrokeMoveBatch(strokeId, batch)
                            batch = mutableListOf()
                            lastFlushNs = now
                            distAcc = 0f
                        }
                    } while (e.changes.any { it.pressed })

                    if (batch.isNotEmpty()) onStrokeMoveBatch(strokeId, batch)
                    onStrokeEnd(strokeId)

                    previewPoints.clear()
                }
            }
    ) {
        val cw = size.width
        val ch = size.height
        val minDim = min(cw, ch)

        // 저장된 스트로크(정규화 → 픽셀)
        for (s in strokes) {
            val pts = s.points
            if (pts.isEmpty()) continue

            val widthPx = max(1f, s.width * minDim)
            if (pts.size == 1) {
                val x = pts[0][0] * cw
                val y = pts[0][1] * ch
                drawCircle(
                    color = Color(s.color),
                    radius = widthPx / 2f,
                    center = Offset(x, y)
                )
            } else {
                val path = buildSmoothPathPx(pts, cw, ch)
                drawPath(
                    path = path,
                    color = Color(s.color),
                    style = Stroke(
                        width = widthPx,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        miter = 1f
                    )
                )
            }
        }

        // 진행 중 미리보기(펜일 때만 표시)
        if (currentTool == "pen" && previewPoints.size >= 2) {
            val path = buildSmoothPathPx(previewPoints, cw, ch)
            drawPath(
                path = path,
                color = Color(currentColor),
                style = Stroke(
                    width = max(1f, currentWidth),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    miter = 1f
                )
            )
        }
    }
}

private fun buildSmoothPathPx(pointsNorm: List<FloatArray>, cw: Float, ch: Float): Path {
    val path = Path()
    if (pointsNorm.isEmpty()) return path

    val p0 = pointsNorm[0]
    var x0 = p0[0] * cw
    var y0 = p0[1] * ch
    path.moveTo(x0, y0)

    for (i in 1 until pointsNorm.size) {
        val pn = pointsNorm[i]
        val x = pn[0] * cw
        val y = pn[1] * ch
        val mx = (x0 + x) / 2f
        val my = (y0 + y) / 2f
        path.quadraticTo(x0, y0, mx, my)
        x0 = x
        y0 = y
    }
    path.lineTo(x0, y0)
    return path
}

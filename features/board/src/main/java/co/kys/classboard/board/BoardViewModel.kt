// features/board/src/main/java/co/kys/classboard/board/BoardViewModel.kt
package co.kys.classboard.board

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.kys.classboard.drawing.model.DrawStroke
import co.kys.classboard.net.EventBus
import co.kys.classboard.proto.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

class BoardViewModel : ViewModel() {

    val strokes = mutableStateListOf<DrawStroke>()
    private val strokeIndex = mutableMapOf<String, Int>()
    private val localSeq = AtomicLong(0)
    private var collectJob: Job? = null

    private val localStrokeIds = mutableSetOf<String>()

    // 툴/두께 기록 (정규화)
    private val toolById = mutableMapOf<String, String>()
    private val widthNormById = mutableMapOf<String, Float>()

    fun startCollect() {
        if (collectJob != null) return
        collectJob = viewModelScope.launch {
            EventBus.incoming.collect { env: MsgEnvelope ->
                when (env.type) {
                    "StrokeStart" -> {
                        val s: StrokeStart = Ser.decode(env.payload!!)
                        if (localStrokeIds.contains(s.strokeId)) return@collect

                        val widthNorm = s.widthQ.toFloat() / Quant.WIDTH
                        toolById[s.strokeId] = s.tool
                        widthNormById[s.strokeId] = widthNorm

                        if (s.tool == "pen") {
                            if (!strokeIndex.containsKey(s.strokeId)) {
                                strokeIndex[s.strokeId] = strokes.size
                                strokes.add(
                                    DrawStroke(
                                        id = s.strokeId,
                                        color = s.color,
                                        width = widthNorm
                                    )
                                )
                            }
                        }
                        // eraser는 시작 시 화면에 스트로크를 추가하지 않음
                    }

                    "StrokeMoveBatch" -> {
                        val m: StrokeMoveBatch = Ser.decode(env.payload!!)
                        if (localStrokeIds.contains(m.strokeId)) return@collect

                        val tool = toolById[m.strokeId] ?: "pen"
                        val ptsNorm = m.points.map { sp ->
                            floatArrayOf(
                                sp.x.toFloat() / Quant.POS,
                                sp.y.toFloat() / Quant.POS
                            )
                        }

                        if (tool == "eraser") {
                            val eraserR = (widthNormById[m.strokeId] ?: 0.03f) / 2f
                            applyEraserPartial(ptsNorm, eraserR)
                        } else {
                            strokeIndex[m.strokeId]?.let { idx ->
                                strokes[idx].points.addAll(ptsNorm)
                            }
                        }
                    }

                    "StrokeEnd" -> {
                        val e: StrokeEnd = Ser.decode(env.payload!!)
                        localStrokeIds.remove(e.strokeId)
                        toolById.remove(e.strokeId)
                        widthNormById.remove(e.strokeId)
                    }
                }
            }
        }
    }

    /**
     * 부분 지우개:
     * - 각 기존 스트로크의 포인트열(정규화)에서 지우개 경로/반경과 닿는 지점들을 마스크 처리
     * - 마스크되지 않은 연속 구간만 추려 여러 새 스트로크로 분할
     * - 원본 스트로크는 제거하고, 분할된 새 스트로크들을 같은 위치에 삽입
     */
    private fun applyEraserPartial(eraserPtsNorm: List<FloatArray>, eraserRadius: Float) {
        if (eraserPtsNorm.isEmpty() || strokes.isEmpty()) return

        var changed = false
        for (i in strokes.lastIndex downTo 0) {
            val s = strokes[i]
            val keptRuns: List<List<FloatArray>> = splitStrokeByErasePoints(
                strokePts = s.points,
                eraserPts = eraserPtsNorm,
                eraseR = eraserRadius,
                strokeR = s.width / 2f
            ) ?: continue // null -> 변경 없음

            // 원본 제거
            strokes.removeAt(i)

            // 남은 구간 삽입
            var insertOffset = 0
            var segIdx = 0
            for (run in keptRuns) {
                if (run.size < 2) continue
                val newPoints = mutableStateListOf<FloatArray>().apply { addAll(run) }
                val newId = "${s.id}#seg${segIdx++}"
                strokes.add(
                    i + insertOffset,
                    DrawStroke(
                        id = newId,
                        color = s.color,
                        width = s.width,
                        points = newPoints
                    )
                )
                insertOffset++
            }
            changed = true
        }
        if (changed) rebuildIndex()
    }

    /**
     * 지워지지 않은 연속 구간들만 반환.
     * @return null 이면 변경 없음, 빈 리스트면 전부 지워짐.
     */
    private fun splitStrokeByErasePoints(
        strokePts: List<FloatArray>,
        eraserPts: List<FloatArray>,
        eraseR: Float,
        strokeR: Float
    ): List<List<FloatArray>>? {
        if (strokePts.isEmpty()) return emptyList()

        val thr = eraseR + strokeR
        val thr2 = thr * thr

        val erased = BooleanArray(strokePts.size) { false }

        outer@ for (i in strokePts.indices) {
            val sp = strokePts[i]
            val sx = sp[0]; val sy = sp[1]
            for (ep in eraserPts) {
                val dx = sx - ep[0]
                val dy = sy - ep[1]
                if (dx * dx + dy * dy <= thr2) {
                    erased[i] = true
                    continue@outer
                }
            }
        }

        if (erased.none { it }) return null

        val runs = mutableListOf<List<FloatArray>>()
        var cur = mutableListOf<FloatArray>()
        for (i in strokePts.indices) {
            if (!erased[i]) {
                cur.add(strokePts[i])
            } else {
                if (cur.size >= 2) runs += cur.toList()
                cur = mutableListOf()
            }
        }
        if (cur.size >= 2) runs += cur.toList()

        return runs
    }

    private fun rebuildIndex() {
        strokeIndex.clear()
        strokes.forEachIndexed { idx, s -> strokeIndex[s.id] = idx }
    }

    // ===== 로컬 → 네트워크 전송 =====
    fun onLocalStrokeStart(id: String, color: Long, widthNorm: Float, tool: String = "pen") {
        localStrokeIds.add(id)

        toolById[id] = tool
        widthNormById[id] = widthNorm

        if (tool == "pen") {
            if (!strokeIndex.containsKey(id)) {
                strokeIndex[id] = strokes.size
                strokes.add(
                    DrawStroke(
                        id = id,
                        color = color,
                        width = widthNorm
                    )
                )
            }
        }
        val wq: Short = (widthNorm.coerceIn(0f, 1f) * Quant.WIDTH)
            .roundToInt().coerceIn(0, 32767).toShort()

        val env = Ser.pack(
            type = "StrokeStart",
            userId = "local",
            value = StrokeStart(id, color, wq, tool),
            localSeq = localSeq.incrementAndGet()
        )
        EventBus.send?.invoke(env)
    }

    fun onLocalStrokeMoveBatch(id: String, pointsNorm: List<FloatArray>) {
        // 로컬 즉시 반영
        if (toolById[id] != "eraser") {
            strokeIndex[id]?.let { strokes[it].points.addAll(pointsNorm) }
        } else {
            val eraserR = (widthNormById[id] ?: 0.03f) / 2f
            applyEraserPartial(pointsNorm, eraserR)
        }

        val ptsQ = pointsNorm.map { p ->
            val xq = (p[0].coerceIn(0f, 1f) * Quant.POS)
                .roundToInt().coerceIn(-32768, 32767).toShort()
            val yq = (p[1].coerceIn(0f, 1f) * Quant.POS)
                .roundToInt().coerceIn(-32768, 32767).toShort()
            ShortPoint(xq, yq)
        }

        val env = Ser.pack(
            type = "StrokeMoveBatch",
            userId = "local",
            value = StrokeMoveBatch(id, ptsQ),
            localSeq = localSeq.incrementAndGet()
        )
        EventBus.send?.invoke(env)
    }

    fun onLocalStrokeEnd(id: String) {
        val env = Ser.pack(
            type = "StrokeEnd",
            userId = "local",
            value = StrokeEnd(id),
            localSeq = localSeq.incrementAndGet()
        )
        EventBus.send?.invoke(env)

        // 클린업
        localStrokeIds.remove(id)
        toolById.remove(id)
        widthNormById.remove(id)
    }

    override fun onCleared() {
        super.onCleared()
        collectJob?.cancel()
    }
}

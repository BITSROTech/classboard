// engine/drawing/src/main/java/co/kys/classboard/drawing/CanvasCache.kt
package co.kys.classboard.drawing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import android.graphics.Color as AColor

class CanvasCache {
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val paint = Paint().apply {
        isAntiAlias = true
        isDither = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND       // ✅ 둥근 붓끝
        strokeJoin = Paint.Join.ROUND     // ✅ 둥근 모서리
    }

    fun ensureSize(size: IntSize) {
        val bm = bitmap
        if (bm == null || bm.width != size.width || bm.height != size.height) {
            bitmap =  createBitmap(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
            canvas = Canvas(bitmap!!)
        }
    }

    fun getBitmap(): Bitmap? = bitmap

    // [start, endExclusive) 구간을 그립니다.
    fun drawPoly(color: Long, width: Float, pts: List<FloatArray>, start: Int, endExclusive: Int) {
        if (canvas == null || endExclusive - start < 1) return
        paint.color = colorLongToInt(color)
        paint.strokeWidth = width.coerceAtLeast(1f)
        for (i in start until endExclusive) {
            val a = pts[i]
            val b = pts[i + 1]
            canvas?.drawLine(a[0], a[1], b[0], b[1], paint)
        }
    }

    private fun colorLongToInt(argb: Long): Int {
        val a = ((argb ushr 56) and 0xFF).toInt()
        val r = ((argb ushr 40) and 0xFF).toInt()
        val g = ((argb ushr 24) and 0xFF).toInt()
        val b = ((argb ushr 8) and 0xFF).toInt()
        return AColor.argb(a, r, g, b)
    }
}

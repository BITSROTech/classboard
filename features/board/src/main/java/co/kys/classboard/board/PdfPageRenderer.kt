package co.kys.classboard.board

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.WorkerThread
import androidx.core.graphics.createBitmap
import kotlin.math.roundToInt

object PdfPageRenderer {

    data class RenderResult(
        val bitmap: Bitmap,
        val pageIndex: Int,
        val pageCount: Int
    )

    /**
     * PDF [uri]의 [pageIndex] 페이지를 [maxWidthPx] 폭에 맞춰 렌더링합니다.
     * 비율은 유지됩니다.
     */
    @WorkerThread
    fun renderPage(
        context: Context,
        uri: Uri,
        pageIndex: Int,
        maxWidthPx: Int
    ): RenderResult {
        val pfd = openPfd(context.contentResolver, uri)
        pfd.use { file ->
            PdfRenderer(file).use { pdf ->
                // 빈 PDF 방어
                if (pdf.pageCount <= 0) {
                    val empty = createBitmap(1, 1)
                    empty.eraseColor(Color.WHITE)
                    return RenderResult(empty, 0, 0)
                }

                val safeIndex = pageIndex.coerceIn(0, pdf.pageCount - 1)
                pdf.openPage(safeIndex).use { page ->
                    val pageWidth = page.width.coerceAtLeast(1)
                    val pageHeight = page.height.coerceAtLeast(1)
                    val scale = maxWidthPx.toFloat() / pageWidth
                    val bmpW = (pageWidth * scale).roundToInt().coerceAtLeast(1)
                    val bmpH = (pageHeight * scale).roundToInt().coerceAtLeast(1)

                    // 메모리 절약이 필요하면 RGB_565로 변경 가능
                    val bmp = createBitmap(bmpW, bmpH)
                    bmp.eraseColor(Color.WHITE) // 흰 바탕

                    // 비트맵 크기 자체가 스케일이므로 추가 Canvas/scale 불필요
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    return RenderResult(bmp, safeIndex, pdf.pageCount)
                }
            }
        }
    }

    private fun openPfd(cr: ContentResolver, uri: Uri): ParcelFileDescriptor {
        return cr.openFileDescriptor(uri, "r")
            ?: error("Cannot open file descriptor for $uri")
    }
}

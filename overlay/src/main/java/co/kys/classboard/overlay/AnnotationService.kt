// app/src/main/java/co/kys/classboard/overlay/AnnotationService.kt
package co.kys.classboard.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import co.kys.classboard.drawing.WhiteboardCanvas
import co.kys.classboard.drawing.model.DrawStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Suppress("DEPRECATION")
class AnnotationService :
    LifecycleService(),
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    companion object {
        private const val CH_ID = "overlay_annot"
        private const val NOTI_ID = 4101

        const val ACTION_ANNOTATION_OPENED = "co.kys.classboard.ACTION_ANNOTATION_OPENED"
        const val ACTION_ANNOTATION_CLOSED = "co.kys.classboard.ACTION_ANNOTATION_CLOSED"

        fun start(context: Context) {
            val i = Intent(context, AnnotationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }

    private lateinit var wm: WindowManager
    private var bubbleView: FrameLayout? = null
    private var canvasView: FrameLayout? = null
    private var bubbleLp: WindowManager.LayoutParams? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val board = OverlayBoardController()

    private val overlayVmStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = overlayVmStore

    private val savedStateController: SavedStateRegistryController =
        SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()

        savedStateController.performAttach()
        savedStateController.performRestore(null)

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()

        val noti = buildNotification("Annotation ready")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTI_ID, noti)
        }

        showBubble()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeBubble()
        removeCanvasView()
        overlayVmStore.clear()
        scope.cancel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CH_ID, "Annotation Overlay", NotificationManager.IMPORTANCE_MIN
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CH_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("ClassBoard")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
    }

    // ===== Bubble ==============================================================
    private fun showBubble() {
        if (bubbleView != null) return

        val dm = resources.displayMetrics
        val initialX = (dm.widthPixels * 0.80f).toInt()
        val initialY = (dm.heightPixels * 0.40f).toInt()

        bubbleLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        // ✅ 드래그 제스처를 똑똑하게 가로채는 FrameLayout
        bubbleView = DraggableFrameLayout(this).apply {
            setViewTreeLifecycleOwner(this@AnnotationService)
            setViewTreeSavedStateRegistryOwner(this@AnnotationService)
            setViewTreeViewModelStoreOwner(this@AnnotationService)

            val compose = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(this@AnnotationService)
                setViewTreeSavedStateRegistryOwner(this@AnnotationService)
                setViewTreeViewModelStoreOwner(this@AnnotationService)

                setContent {
                    MaterialTheme {
                        Surface(
                            color = Color(0xFF202124),
                            contentColor = Color.White,
                            shape = MaterialTheme.shapes.large,
                            tonalElevation = 6.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { showCanvas() }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Draw", tint = Color.White)
                                }
                                Spacer(Modifier.width(8.dp))
                                IconButton(onClick = { stopAndClose() }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }
            addView(compose)
        }

        wm.addView(bubbleView, bubbleLp)
        notifyText("Floating icon active")
    }

    private fun removeBubble() {
        bubbleView?.let { runCatching { wm.removeView(it) } }
        bubbleView = null
        bubbleLp = null
    }

    // ===== Canvas ==============================================================
    private fun showCanvas() {
        if (canvasView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        canvasView = FrameLayout(this).apply {
            setViewTreeLifecycleOwner(this@AnnotationService)
            setViewTreeSavedStateRegistryOwner(this@AnnotationService)
            setViewTreeViewModelStoreOwner(this@AnnotationService)

            val compose = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(this@AnnotationService)
                setViewTreeSavedStateRegistryOwner(this@AnnotationService)
                setViewTreeViewModelStoreOwner(this@AnnotationService)

                setContent {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize()) {
                            WhiteboardCanvas(
                                modifier = Modifier.fillMaxSize(),
                                strokes = board.strokes,
                                currentColor = 0xFF000000L,
                                currentWidth = board.toolState.currentWidthPx,
                                currentTool = board.toolState.currentTool,
                                onStrokeStart = { id, c, w, tool ->
                                    board.onLocalStrokeStart(id, c, w, tool)
                                },
                                onStrokeMoveBatch = { id, pts ->
                                    board.onLocalStrokeMoveBatch(id, pts)
                                },
                                onStrokeEnd = { id ->
                                    board.onLocalStrokeEnd(id)
                                }
                            )
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                MiniToolbar(
                                    onClose = { toBubble() },
                                    onClearAll = { board.clearAll() },
                                    toolState = board.toolState,
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
            addView(compose)
        }

        wm.addView(canvasView, params)
        removeBubble()
        sendBroadcast(Intent(ACTION_ANNOTATION_OPENED))
        notifyText("Annotating on screen")
    }

    private fun toBubble() {
        removeCanvasView()
        showBubble()
        notifyText("Floating icon active")
    }

    private fun stopAndClose() {
        removeCanvasView()
        removeBubble()

        sendBroadcast(Intent(ACTION_ANNOTATION_CLOSED))
        bringAppToForeground()

        scope.launch {
            delay(250)
            sendBroadcast(Intent(ACTION_ANNOTATION_CLOSED))
        }

        notifyText("Annotation finished")
        stopSelf()
    }

    private fun bringAppToForeground() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        if (launch != null) runCatching { startActivity(launch) }
    }

    private fun removeCanvasView() {
        canvasView?.let { runCatching { wm.removeView(it) } }
        canvasView = null
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun notifyText(t: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTI_ID, buildNotification(t))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 드래그 제스처를 슬롭 초과 시에만 가로채고, 위치를 갱신하는 FrameLayout
    private inner class DraggableFrameLayout(ctx: Context) : FrameLayout(ctx) {
        private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            val lp = bubbleLp ?: return super.onInterceptTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    return false // 아직 자식에게 전달
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        dragging = true
                        return true // 여기서부터 내가 처리
                    }
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val lp = bubbleLp ?: return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        val dx = (ev.rawX - downRawX).toInt()
                        val dy = (ev.rawY - downRawY).toInt()
                        lp.x = startX + dx
                        lp.y = startY + dy
                        runCatching { wm.updateViewLayout(this, lp) }
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        return true // 드래그 완료, 이벤트 소비
                    }
                }
            }
            return super.onTouchEvent(ev)
        }
    }
}

/** 상단 미니 툴바 */
@Composable
private fun MiniToolbar(
    onClose: () -> Unit,  // 버블로 축소
    onClearAll: () -> Unit,
    toolState: OverlayBoardController.ToolState,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xCC202124),
        tonalElevation = 6.dp,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.heightIn(min = 56.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconToggleButton(
                checked = toolState.currentTool == "pen",
                onCheckedChange = { if (it) toolState.currentTool = "pen" }
            ) { Icon(Icons.Filled.Edit, contentDescription = "Pen") }

            IconToggleButton(
                checked = toolState.currentTool == "eraser",
                onCheckedChange = { if (it) toolState.currentTool = "eraser" }
            ) { Icon(Icons.Filled.Clear, contentDescription = "Eraser") }

            Column(Modifier.weight(1f)) {
                Text(
                    if (toolState.currentTool == "pen") "펜 두께" else "지우개 두께",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
                Slider(
                    value = toolState.currentWidthPx,
                    onValueChange = { v -> toolState.currentWidthPx = v },
                    valueRange = if (toolState.currentTool == "pen") 1f..24f else 8f..72f
                )
            }

            OutlinedButton(onClick = onClearAll) { Text("모두 지우기") }

            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "버블로 축소")
            }
        }
    }
}

/** 로컬 보드 컨트롤러 */
private class OverlayBoardController {
    val strokes = mutableStateListOf<DrawStroke>()

    class ToolState {
        var currentTool by mutableStateOf("pen")
        var currentWidthPx by mutableFloatStateOf(6f)
    }
    val toolState = ToolState()

    fun onLocalStrokeStart(id: String, color: Long, widthNorm: Float, tool: String) {
        if (tool == "pen") strokes.add(DrawStroke(id, color, widthNorm))
    }

    fun onLocalStrokeMoveBatch(id: String, pointsNorm: List<FloatArray>) {
        val idx = strokes.indexOfFirst { it.id == id }
        if (idx >= 0) strokes[idx].points.addAll(pointsNorm)
    }

    fun onLocalStrokeEnd(id: String) { /* no-op */ }

    fun clearAll() = strokes.clear()
}

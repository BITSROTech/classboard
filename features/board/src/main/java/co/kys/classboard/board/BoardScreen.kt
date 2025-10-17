// app/src/main/java/co/kys/classboard/board/BoardScreen.kt
package co.kys.classboard.board

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import androidx.core.net.toUri
import co.kys.classboard.drawing.WhiteboardCanvas
import co.kys.classboard.lobby.HostViewModel
import co.kys.classboard.lobby.JoinViewModel
import co.kys.classboard.lobby.PortraitCaptureActivity
import co.kys.classboard.lobby.QrUtil
import co.kys.classboard.net.NetUtils
import co.kys.classboard.overlay.AnnotationService
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

@SuppressLint("UnrememberedMutableState", "ConfigurationScreenWidthHeight")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(
    hostVm: HostViewModel,
    joinVm: JoinViewModel,
    isTeacher: Boolean,
    onBack: () -> Unit,
    onEnterAnnotating: () -> Unit,
) {
    // ===== 판서 상태 =====
    val vm = remember { BoardViewModel() }
    LaunchedEffect(Unit) { vm.startCollect() }

    var currentTool by remember { mutableStateOf("pen") }
    var penWidthPx by remember { mutableFloatStateOf(6f) }
    var eraserWidthPx by remember { mutableFloatStateOf(28f) }
    val currentWidthPx by remember { derivedStateOf { if (currentTool == "pen") penWidthPx else eraserWidthPx } }
    val penColor = 0xFF000000L

    // ===== 주석 진행 상태(배너 바인딩) =====
    val context = LocalContext.current
    var annotating by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val filter = IntentFilter().apply {
            addAction(AnnotationService.ACTION_ANNOTATION_OPENED)
            addAction(AnnotationService.ACTION_ANNOTATION_CLOSED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    AnnotationService.ACTION_ANNOTATION_OPENED -> annotating = true
                    AnnotationService.ACTION_ANNOTATION_CLOSED -> annotating = false
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    // ===== 공유/참여 UI =====
    val scope = rememberCoroutineScope()
    var showShareSheet by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }

    var hostStarted by remember { mutableStateOf(false) }
    var hostPort by remember { mutableStateOf("8080") }
    val localIp = remember { NetUtils.localIpv4OrNull() ?: "<IP-unknown>" }
    var teacherId by remember { mutableStateOf("teacher-${(1000..9999).random()}") }

    var joinUrl by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf("student-${(1000..9999).random()}") }
    var autoReconnect by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { joinVm.setAutoReconnect(autoReconnect) }

    // ===== 권한 & 런처 (교사만) =====
    val requestNotifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val requestOverlaySettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)) {
            AnnotationService.start(context)
            annotating = true
            onEnterAnnotating()
            context.findActivity()?.moveTaskToBack(true)
        }
    }
    fun startAnnotationWithPermissions() {
        if (!isTeacher) return
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PERMISSION_GRANTED
            if (!granted) requestNotifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${context.packageName}".toUri()
            )
            requestOverlaySettings.launch(intent); return
        }
        AnnotationService.start(context)
        annotating = true
        onEnterAnnotating()
        context.findActivity()?.moveTaskToBack(true)
    }

    // ===== 문서 모드(배경 동기화) =====
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }

    var backgroundBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var docUri by remember { mutableStateOf<Uri?>(null) }
    var docId by remember { mutableStateOf<String?>(null) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(0) }
    var isLoadingPage by remember { mutableStateOf(false) }

    // 학생/교사 공통: JoinViewModel에서 수신한 배경을 반영
    LaunchedEffect(joinVm.bgBitmap.value) {
        backgroundBitmap = joinVm.bgBitmap.value
        pageIndex = joinVm.bgPage.value
        pageCount = joinVm.bgPageCount.value
    }

    // 페이지 렌더 + 전송
    suspend fun renderAndBroadcastPage(maxWidthPx: Int) {
        val u = docUri ?: return
        isLoadingPage = true
        val res = withContext(Dispatchers.IO) {
            PdfPageRenderer.renderPage(
                context, u, pageIndex, maxWidthPx = maxWidthPx.coerceAtLeast(720)
            )
        }
        backgroundBitmap = res.bitmap
        pageIndex = res.pageIndex
        pageCount = res.pageCount
        isLoadingPage = false

        if (isTeacher) {
            // 서버가 실행 중일 때만 브로드캐스트
            if (hostStarted) {
                hostVm.pushBgSetFromBitmap(
                    docId = docId ?: "doc",
                    page = pageIndex,
                    bmp = res.bitmap,
                    pageCount = pageCount
                )
            }
            // 2) (보강) 교사가 self-join 되어 있다면 클라이언트 경로도 송신
            if (joinVm.isConnected.value) {
                joinVm.sendBgSetFromBitmap(docId = docId ?: "doc", page = pageIndex, bmp = res.bitmap)
            }
        }
    }

    fun clearBackground() {
        backgroundBitmap = null
        docUri = null
        docId = null
        pageIndex = 0
        pageCount = 0
        if (isTeacher) {
            if (hostStarted) { hostVm.pushBgClear() }
            if (joinVm.isConnected.value) {
                joinVm.sendBgClear()
            }
        }
    }

    // PDF 열기
    val openPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            docUri = uri
            docId = uri.toString()
            pageIndex = 0
            scope.launch { renderAndBroadcastPage(screenWidthPx) }
        }
    }

    // ===== QR 스캐너 =====
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents?.trim()
        if (!contents.isNullOrEmpty()) {
            joinUrl = contents
            joinVm.connect(joinUrl, userId)
        }
    }

    // 교사: 서버 오픈 + self-join + QR 표시
    fun startTwoWayAndShowQr() {
        if (!isTeacher) return
        if (!hostStarted) {
            hostVm.start(hostPort.toInt())
            hostStarted = true
        }
        val urlForSelf =
            if (localIp != "<IP-unknown>") "ws://$localIp:$hostPort" else "ws://127.0.0.1:$hostPort"
        joinVm.connect(urlForSelf, teacherId)
        showQrDialog = true
    }

    // ===== 상단 드로어 =====
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    BackHandler(enabled = drawerState.currentValue == DrawerValue.Open) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("참가자", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { scope.launch { drawerState.close() } }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close drawer")
                    }
                }
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    items(hostVm.participants) { uid ->
                        ListItem(headlineContent = { Text(uid) })
                        HorizontalDivider()
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Board") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Participants")
                        }
                    },
                    actions = {
                        if (isTeacher) {
                            IconButton(onClick = { startAnnotationWithPermissions() }) {
                                Icon(Icons.Filled.Edit, contentDescription = "주석 시작")
                            }
                        }
                        TextButton(onClick = onBack) { Text("뒤로") }
                    }
                )
            },
            bottomBar = {
                BottomAppBar(tonalElevation = 3.dp) {
                    FilledIconToggleButton(
                        checked = currentTool == "pen",
                        onCheckedChange = { if (it) currentTool = "pen" }
                    ) { Icon(Icons.Filled.Edit, contentDescription = "Pen") }

                    Spacer(Modifier.width(8.dp))

                    FilledIconToggleButton(
                        checked = currentTool == "eraser",
                        onCheckedChange = { if (it) currentTool = "eraser" }
                    ) { Icon(Icons.Filled.Clear, contentDescription = "Eraser") }

                    Spacer(Modifier.width(16.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        val value = if (currentTool == "pen") penWidthPx else eraserWidthPx
                        val onValueChange: (Float) -> Unit = { v ->
                            if (currentTool == "pen") penWidthPx = v else eraserWidthPx = v
                        }
                        Text(
                            if (currentTool == "pen") "펜 두께" else "지우개 두께",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = value,
                            onValueChange = onValueChange,
                            valueRange = if (currentTool == "pen") 1f..24f else 8f..72f,
                            steps = 0
                        )
                    }

                    IconButton(onClick = { showShareSheet = true }) {
                        Icon(Icons.Filled.QrCode, contentDescription = "양방향 모드")
                    }

                    if (isTeacher) {
                        IconButton(onClick = { openPdf.launch(arrayOf("application/pdf")) }) {
                            Icon(Icons.Filled.PictureAsPdf, contentDescription = "PDF 열기")
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (annotating) {
                    Text(
                        "주석 모드 진행중...",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Box(
                    Modifier
                        .fillMaxSize()
                ) {
                    backgroundBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Background Document",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    WhiteboardCanvas(
                        modifier = Modifier.fillMaxSize(),
                        strokes = vm.strokes,
                        currentColor = penColor,
                        currentWidth = currentWidthPx,
                        currentTool = currentTool,
                        onStrokeStart = { id, c, w, tool -> vm.onLocalStrokeStart(id, c, w, tool) },
                        onStrokeMoveBatch = { id, pts -> vm.onLocalStrokeMoveBatch(id, pts) },
                        onStrokeEnd = { id -> vm.onLocalStrokeEnd(id) }
                    )

                    if (isTeacher && docUri != null) {
                        Surface(
                            tonalElevation = 6.dp,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(
                                    enabled = !isLoadingPage && pageIndex > 0,
                                    onClick = {
                                        pageIndex--
                                        scope.launch { renderAndBroadcastPage(screenWidthPx) }
                                    }
                                ) { Icon(Icons.Filled.ChevronLeft, contentDescription = "Prev") }

                                Text(
                                    if (pageCount > 0) "${pageIndex + 1} / $pageCount" else "-- / --",
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )

                                IconButton(
                                    enabled = !isLoadingPage && pageIndex < pageCount - 1,
                                    onClick = {
                                        pageIndex++
                                        scope.launch { renderAndBroadcastPage(screenWidthPx) }
                                    }
                                ) { Icon(Icons.Filled.ChevronRight, contentDescription = "Next") }

                                Spacer(Modifier.width(12.dp))

                                TextButton(
                                    enabled = !isLoadingPage,
                                    onClick = { clearBackground() }
                                ) { Text("배경 지우기") }
                            }
                        }
                    }
                }
            }
        }
    }

    // ===== “양방향 판서” 시트 =====
    if (showShareSheet) {
        ModalBottomSheet(
            onDismissRequest = { showShareSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isTeacher) {
                    ElevatedCard(
                        onClick = {
                            startTwoWayAndShowQr()
                            showShareSheet = false
                        }
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("양방향 판서 시작", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text("서버를 자동으로 열고, 교사가 자동 접속합니다.\n학생은 QR로 바로 참여할 수 있어요.")
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = hostPort,
                                onValueChange = { hostPort = it.filter { ch -> ch.isDigit() }.ifEmpty { "8080" } },
                                label = { Text("Port") }
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "예상 접속 주소: " +
                                        if (localIp != "<IP-unknown>") "ws://$localIp:$hostPort"
                                        else "IP를 확인할 수 없습니다."
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    startTwoWayAndShowQr()
                                    showShareSheet = false
                                }) { Text(if (hostStarted) "QR 다시 보기" else "서버 시작 & QR 보기") }
                                if (hostStarted) {
                                    OutlinedButton(onClick = {
                                        hostVm.stop()
                                        hostStarted = false
                                        joinVm.disconnect()
                                    }) { Text("서버 중지") }
                                }
                            }
                        }
                    }

                }

                ElevatedCard {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("양방향 판서 참여", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = userId,
                            onValueChange = { userId = it },
                            label = { Text("User ID") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (joinUrl.isEmpty()) {
                            Text(
                                "접속 정보가 없습니다. QR 스캔으로 참여 정보를 불러와 주세요.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Auto reconnect")
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = autoReconnect,
                                onCheckedChange = {
                                    autoReconnect = it
                                    joinVm.setAutoReconnect(it)
                                }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { joinVm.connect(joinUrl, userId) },
                                enabled = joinUrl.isNotEmpty()
                            ) { Text("접속") }
                            OutlinedButton(onClick = {
                                val opts = ScanOptions().apply {
                                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    setPrompt("QR 코드를 화면에 맞춰주세요")
                                    setBeepEnabled(false)
                                    setOrientationLocked(true)
                                    setCaptureActivity(PortraitCaptureActivity::class.java)
                                }
                                qrScanner.launch(opts)
                            }) { Text("QR 스캔") }
                            if (joinVm.isConnected.value) {
                                OutlinedButton(onClick = { joinVm.disconnect() }) { Text("연결 끊기") }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (isTeacher && showQrDialog && hostStarted && localIp != "<IP-unknown>") {
        val url = "ws://$localIp:$hostPort"
        val config = LocalConfiguration.current
        val densityLocal = LocalDensity.current
        val dialogWidthDp = (config.screenWidthDp * 0.95f).dp
        val dialogHeightDp = (config.screenHeightDp * 0.90f).dp
        val qrSidePx by remember(url, dialogWidthDp, dialogHeightDp) {
            mutableIntStateOf(
                with(densityLocal) { (minOf(dialogWidthDp, dialogHeightDp) - 32.dp).toPx().roundToInt() }
            )
        }
        val qrBitmap by remember(url, qrSidePx) {
            mutableStateOf(QrUtil.generate(url, size = max(512, qrSidePx)))
        }

        Dialog(onDismissRequest = { showQrDialog = false }) {
            Surface(
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .width(dialogWidthDp)
                    .height(dialogHeightDp)
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("QR 코드로 접속", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Join QR",
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(url, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showQrDialog = false }) { Text("닫기") }
                    }
                }
            }
        }
    }
}

/** 현재 Compose Context에서 Activity 안전 획득 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
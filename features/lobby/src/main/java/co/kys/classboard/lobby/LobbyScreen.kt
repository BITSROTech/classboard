// features/lobby/src/main/java/co/kys/classboard/lobby/LobbyScreen.kt
package co.kys.classboard.lobby

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import co.kys.classboard.net.NetUtils
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun LobbyScreen(
    hostVm: HostViewModel,
    joinVm: JoinViewModel,
    onEnterBoardAsTeacher: () -> Unit,
    onEnterBoardAsStudent: () -> Unit
) {
    var hostStarted by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }

    var hostPort by remember { mutableStateOf("8080") }
    val localIp = remember { NetUtils.localIpv4OrNull() ?: "<IP-unknown>" }

    var teacherId by remember { mutableStateOf("teacher-${(1000..9999).random()}") }
    var userId by remember { mutableStateOf("student-${(1000..9999).random()}") }

    // 기본은 비워두고 QR/입력으로 채움
    var joinUrl by remember { mutableStateOf("") }

    // 자동 재연결 토글
    var autoReconnect by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { joinVm.setAutoReconnect(autoReconnect) }

    // 세로 고정 QR 스캐너
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents?.trim()
        if (!contents.isNullOrEmpty()) {
            joinUrl = contents
            joinVm.connect(joinUrl, userId)
        } else {
            joinVm.logs += "QR 스캔 취소"
        }
    }

    Column(Modifier.fillMaxSize()) {

        // 상단 50% — 교사
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
        ) {
            ScrollableCard {
                Text("교사 (Host)", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = hostPort,
                    onValueChange = { hostPort = it },
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!hostStarted) {
                        Button(onClick = {
                            hostVm.start(hostPort.toInt())
                            hostStarted = true

                            // 로컬 IP 우선 self-join
                            val urlForSelf =
                                if (localIp != "<IP-unknown>") "ws://$localIp:$hostPort"
                                else "ws://127.0.0.1:$hostPort"

                            joinVm.connect(urlForSelf, teacherId)
                            joinVm.logs += "Host self-join: $urlForSelf as $teacherId"
                            showQrDialog = true
                        }) { Text("서버 시작") }
                    } else {
                        // ✅ 서버 중지 버튼 (서버만 끔)
                        OutlinedButton(onClick = {
                            hostVm.stop()
                            hostStarted = false
                            showQrDialog = false

                            // 교사 self-join 클라이언트 연결도 필요 시 끊기
                            joinVm.disconnect()
                        }) {
                            Text("서버 중지")
                        }
                    }

                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = onEnterBoardAsTeacher,
                        enabled = hostStarted && joinVm.isConnected.value
                    ) { Text("보드 화면으로") }

                    Spacer(Modifier.width(12.dp))
                    Text("IP: $localIp")
                }

                Spacer(Modifier.height(8.dp))
                val url = "ws://$localIp:$hostPort"
                Text("접속 URL: $url")

                if (hostStarted && localIp != "<IP-unknown>") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showQrDialog = true }) { Text("QR 다시 보기") }
                }

                Spacer(Modifier.height(8.dp))
                Text("클라이언트 수: ${hostVm.clientCount}")

                Spacer(Modifier.height(8.dp))
                Column { hostVm.logs.forEach { Text("• $it") } }
            }
        }

        // 하단 50% — 학생
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            ScrollableCard {
                Text("학생 (Join)", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = joinUrl,
                    onValueChange = { joinUrl = it },
                    label = { Text("ws://<host-ip>:<port>") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = userId,
                    onValueChange = { userId = it },
                    label = { Text("User ID") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { joinVm.connect(joinUrl, userId) }) {
                        Text("접속 & Hello 보내기")
                    }
                    Spacer(Modifier.width(12.dp))

                    // ✅ 학생용 보드 진입 버튼 복구
                    Button(
                        onClick = onEnterBoardAsStudent,
                        enabled = joinVm.isConnected.value
                    ) { Text("보드 화면으로") }

                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = { joinVm.disconnect() },
                        enabled = joinVm.isConnected.value
                    ) { Text("연결 끊기(클라이언트)") }
                }
                Spacer(Modifier.height(8.dp))
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
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    val opts = ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("QR 코드를 화면에 맞춰주세요")
                        setBeepEnabled(false)
                        setOrientationLocked(true)
                        setCaptureActivity(PortraitCaptureActivity::class.java)
                    }
                    qrScanner.launch(opts)
                }) { Text("QR 스캔으로 접속") }

                Spacer(Modifier.height(8.dp))
                Column { joinVm.logs.forEach { Text("• $it") } }
            }
        }
    }

    // ===== QR 다이얼로그 =====
    if (showQrDialog && hostStarted && localIp != "<IP-unknown>") {
        val url = "ws://$localIp:$hostPort"
        val config = LocalConfiguration.current
        val density = LocalDensity.current
        val dialogWidthDp = (config.screenWidthDp * 0.95f).dp
        val dialogHeightDp = (config.screenHeightDp * 0.90f).dp
        val qrSidePx by remember(url, dialogWidthDp, dialogHeightDp) {
            mutableIntStateOf(with(density) { (minOf(dialogWidthDp, dialogHeightDp) - 32.dp).toPx().roundToInt() })
        }
        val qrBitmap by remember(url, qrSidePx) {
            mutableStateOf(QrUtil.generate(url, size = max(512, qrSidePx)))
        }

        Dialog(onDismissRequest = { showQrDialog = false }) {
            Surface(
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.width(dialogWidthDp).height(dialogHeightDp)
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("QR 코드로 접속", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Join QR",
                        modifier = Modifier.fillMaxWidth().weight(1f)
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

/** 스크롤 가능한 카드 + 얇은 스크롤바 */
@Composable
private fun ScrollableCard(
    modifier: Modifier = Modifier,
    thumbWidth: Dp = 4.dp,
    thumbMinHeight: Dp = 24.dp,
    thumbColor: Color = Color.Black.copy(alpha = 0.35f),
    padding: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val state = rememberScrollState()
    val density = LocalDensity.current
    val thumbW = with(density) { thumbWidth.toPx() }
    val minThumbH = with(density) { thumbMinHeight.toPx() }
    val sidePad = with(density) { 4.dp.toPx() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(state)
            .drawWithContent {
                drawContent()
                if (state.maxValue > 0) {
                    val viewH = size.height
                    val contentH = state.maxValue + viewH
                    val proportion = viewH / contentH
                    val thumbH = max(minThumbH, viewH * proportion)
                    val available = viewH - thumbH
                    val y = if (state.maxValue == 0) 0f
                    else available * (state.value.toFloat() / state.maxValue)
                    val x = size.width - thumbW - sidePad

                    drawRoundRect(
                        color = thumbColor,
                        topLeft = Offset(x, y),
                        size = Size(thumbW, thumbH),
                        cornerRadius = CornerRadius(thumbW / 2, thumbW / 2)
                    )
                }
            }
            .padding(padding),
        content = content
    )
}

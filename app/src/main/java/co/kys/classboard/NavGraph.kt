// app/src/main/java/co/kys/classboard/NavGraph.kt
package co.kys.classboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import co.kys.classboard.board.BoardScreen
import co.kys.classboard.lobby.HostViewModel
import co.kys.classboard.lobby.JoinViewModel
import co.kys.classboard.lobby.LobbyScreen
import co.kys.classboard.overlay.AnnotationService
import co.kys.classboard.overlay.RoleSelectScreen

@Composable
fun AppNavGraph() {
    val nav = rememberNavController()

    // 액티비티 스코프에서 유지 (보드/로비/주석 간 공유)
    val hostVm = remember { HostViewModel() }
    val joinVm = remember { JoinViewModel() }

    // 시작 화면: 역할 선택
    NavHost(navController = nav, startDestination = "role") {

        composable("role") {
            RoleSelectScreen(
                onTeacher = {
                    nav.navigate("board?role=teacher") {
                        popUpTo("role") { inclusive = true }
                    }
                },
                onStudent = {
                    nav.navigate("board?role=student") {
                        popUpTo("role") { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = "board?role={role}",
            arguments = listOf(
                navArgument("role") {
                    type = NavType.StringType
                    defaultValue = "teacher"
                }
            )
        ) { backStackEntry ->
            val role = backStackEntry.arguments?.getString("role") ?: "teacher"
            val isTeacher = role == "teacher"

            BoardScreen(
                hostVm = hostVm,
                joinVm = joinVm,
                isTeacher = isTeacher,
                onBack = {
                    nav.navigate("role") { popUpTo(0) }
                },
                onEnterAnnotating = {
                    nav.navigate("annotating")
                }
            )
        }

        composable("annotating") {
            AnnotatingScreen(
                onAnnotationClosed = {
                    nav.popBackStack() // annotating 제거 → board로 복귀
                }
            )
        }

        // (선택) 기존 로비 화면
        composable("lobby") {
            LobbyScreen(
                hostVm = hostVm,
                joinVm = joinVm,
                onEnterBoardAsTeacher = { nav.navigate("board?role=teacher") },
                onEnterBoardAsStudent = { nav.navigate("board?role=student") }
            )
        }
    }
}

@Composable
private fun AnnotatingScreen(
    onAnnotationClosed: () -> Unit
) {
    val context = LocalContext.current

    // 주석 종료 브로드캐스트 수신 → 콜백
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == AnnotationService.ACTION_ANNOTATION_CLOSED) {
                    onAnnotationClosed()
                }
            }
        }
        val filter = IntentFilter(AnnotationService.ACTION_ANNOTATION_CLOSED)

        // ✅ Android 13+에서도 플래그 경고 없이 안전하게 등록
        val flags = if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.RECEIVER_NOT_EXPORTED else 0

        ContextCompat.registerReceiver(context, receiver, filter, flags)

        onDispose { context.unregisterReceiver(receiver) }
    }

    // 보드를 가리는 간단한 오버레이 화면
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        Text("주석 모드 진행 중…", color = MaterialTheme.colorScheme.onSurface)
    }
}

// app/src/main/java/co/kys/classboard/MainActivity.kt
package co.kys.classboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // 상단 타이틀바 없이 바로 NavGraph 렌더링
                AppNavGraph()
            }
        }
    }
}

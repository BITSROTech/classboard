package co.kys.classboard.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun RoleSelectScreen(
    onTeacher: () -> Unit,
    onStudent: () -> Unit
) {
    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("역할을 선택하세요", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { onTeacher() },           // <- 명시적으로 호출
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("선생님 입장")
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { onStudent() },           // <- 명시적으로 호출
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("학생 입장")
            }
        }
    }
}

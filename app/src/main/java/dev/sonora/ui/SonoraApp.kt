package dev.sonora.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.sonora.service.SonoraService

/**
 * App root. Navigation and the feature graph hang off here.
 */
@Composable
fun SonoraApp() {
    val context = LocalContext.current

    // Reflects the last action taken, not authoritative service state. This gets replaced
    // once the local API can report real backend status.
    var backendState by remember { mutableStateOf("Stopped") }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Sonora", style = MaterialTheme.typography.headlineMedium)
        Text(text = "Backend: $backendState", style = MaterialTheme.typography.bodyMedium)

        Button(
            onClick = {
                SonoraService.start(context)
                backendState = "Running"
            },
        ) {
            Text("Start")
        }

        Button(
            onClick = {
                SonoraService.stop(context)
                backendState = "Stopped"
            },
        ) {
            Text("Stop")
        }
    }
}

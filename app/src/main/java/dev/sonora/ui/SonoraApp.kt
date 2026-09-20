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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.sonora.service.SonoraService
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App root. Navigation and the feature graph hang off here.
 */
@Composable
fun SonoraApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Reflects the last action taken, not authoritative service state. This gets replaced
    // once the local API can report real backend status.
    var backendState by remember { mutableStateOf("Stopped") }
    var pingResult by remember { mutableStateOf("-") }

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

        Button(
            onClick = {
                scope.launch { pingResult = pingLocalApi() }
            },
        ) {
            Text("Ping backend")
        }

        Text(text = "Local API: $pingResult", style = MaterialTheme.typography.bodySmall)
    }
}

private suspend fun pingLocalApi(): String = withContext(Dispatchers.IO) {
    runCatching {
        val connection =
            URL("http://127.0.0.1:${SonoraService.PORT}/").openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            "${connection.responseCode} $body"
        } finally {
            connection.disconnect()
        }
    }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
}

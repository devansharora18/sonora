package dev.sonora.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.backend.BackendState
import dev.sonora.backend.SonoraBackend
import dev.sonora.backend.SonoraPlayer

/**
 * App root. Navigation and the feature graph hang off here.
 */
@Composable
fun SonoraApp() {
    val context = LocalContext.current
    val state by SonoraBackend.state.collectAsState()

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    when (val current = state) {
        is BackendState.Connected -> {
            var tab by remember { mutableStateOf(MainTab.Search) }

            // Binds to the playback service once the app is in use, so the first tap on a track
            // is not waiting on a connection.
            LaunchedEffect(Unit) { SonoraPlayer.connect(context) }

            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Sonora", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { SonoraBackend.disconnect(context) }) {
                        Text("Disconnect")
                    }
                }

                TabRow(selectedTabIndex = tab.ordinal, modifier = Modifier.fillMaxWidth()) {
                    MainTab.entries.forEach { entry ->
                        Tab(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            text = { Text(entry.label) },
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (tab) {
                        MainTab.Search -> SearchScreen()
                        MainTab.Library -> LibraryScreen()
                    }
                }

                NowPlayingBar()
            }
        }

        else -> ConnectScreen(state = current)
    }
}

private enum class MainTab(val label: String) {
    Search("Search"),
    Library("Library"),
}

/** Shown above the tabs whenever something is loaded, on either screen. */
@Composable
private fun NowPlayingBar() {
    val playback by SonoraPlayer.state.collectAsState()
    val track = playback.track ?: return

    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            rememberArtwork(track.file)?.let { artwork ->
                Image(
                    bitmap = artwork,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(MaterialTheme.shapes.small),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                listOfNotNull(track.artist, track.album).joinToString(" \u00b7 ").let {
                    if (it.isNotEmpty()) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            TextButton(onClick = { SonoraPlayer.togglePlayPause() }) {
                Text(if (playback.isPlaying) "Pause" else "Play")
            }
        }
    }
}

@Composable
private fun ConnectScreen(state: BackendState) {
    val context = LocalContext.current
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Sonora", style = MaterialTheme.typography.headlineMedium)

        when (state) {
            BackendState.Idle -> {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Soulseek username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = { SonoraBackend.connect(context, username.trim(), password) },
                    enabled = username.isNotBlank() && password.isNotBlank(),
                ) {
                    Text("Connect")
                }

                Text(
                    text = "An unknown username is registered on first sign-in.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            BackendState.Connecting -> {
                Text("Connecting\u2026", style = MaterialTheme.typography.bodyMedium)
                CircularProgressIndicator()
            }

            is BackendState.Failed -> {
                Text(
                    text = "Could not connect",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(state.reason, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { SonoraBackend.disconnect(context) }) {
                    Text("Back")
                }
            }

            // Handled by the caller.
            is BackendState.Connected -> Unit
        }
    }
}

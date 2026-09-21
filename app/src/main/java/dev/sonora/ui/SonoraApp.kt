package dev.sonora.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
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
import dev.sonora.ui.theme.accentText
import dev.sonora.backend.BackendState
import dev.sonora.backend.LibraryTrack
import dev.sonora.backend.Playlists
import dev.sonora.backend.SonoraBackend
import dev.sonora.backend.SonoraPlayer
import kotlinx.coroutines.delay

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
            var playerOpen by remember { mutableStateOf(false) }
            var addTarget by remember { mutableStateOf<LibraryTrack?>(null) }
            val playback by SonoraPlayer.state.collectAsState()
            val playlists by SonoraBackend.playlists.collectAsState()
            val likedPaths = remember(playlists) { Playlists.likedPaths(playlists) }

            // Binds to the playback service once the app is in use, so the first tap on a track
            // is not waiting on a connection.
            LaunchedEffect(Unit) { SonoraPlayer.connect(context) }

            // Playlists outlive the session, so they are read once when the app is usable rather
            // than on every visit to the Library.
            LaunchedEffect(Unit) { SonoraBackend.refreshPlaylists(context) }

            LaunchedEffect(playback.track, playback.isPlaying) {
                while (playback.track != null) {
                    SonoraPlayer.syncPosition()
                    delay(500)
                }
            }

            if (playerOpen) {
                BackHandler { playerOpen = false }
                NowPlayingScreen(
                    onClose = { playerOpen = false },
                    isLiked = playback.track?.let { it.file.absolutePath in likedPaths } == true,
                    onToggleLike = {
                        playback.track?.let { SonoraBackend.toggleLiked(context, it) }
                    },
                    onToggleShuffle = { SonoraPlayer.toggleShuffle() },
                    onCycleRepeat = { SonoraPlayer.cycleRepeat() },
                    onAddToPlaylist = { addTarget = playback.track },
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Sonora", style = MaterialTheme.typography.titleLarge)
                    TextButton(
                        onClick = { SonoraBackend.disconnect(context) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text("Disconnect")
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (tab) {
                        MainTab.Home -> HomeScreen()
                        MainTab.Search -> SearchScreen()
                        MainTab.Library -> LibraryScreen()
                    }
                }

                NowPlayingBar(onOpen = { playerOpen = true })

                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MainTab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = {
                                Icon(
                                    imageVector = entry.icon(),
                                    contentDescription = null,
                                )
                            },
                            label = { Text(entry.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.accentText,
                                selectedTextColor = MaterialTheme.colorScheme.accentText,
                                indicatorColor = MaterialTheme.colorScheme.background,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
                }
            }

            AddToPlaylistFlow(
                track = addTarget,
                playlists = playlists,
                onDismiss = { addTarget = null },
                onAdd = { playlist, track ->
                    SonoraBackend.addToPlaylist(context, playlist.id, track)
                    addTarget = null
                },
                onCreateWithTrack = { name, track ->
                    SonoraBackend.createPlaylist(context, name, track)
                    addTarget = null
                },
            )
        }

        else -> ConnectScreen(state = current)
    }
}

private enum class MainTab(val label: String) {
    Home("Home"),
    Search("Search"),
    Library("Library"),
}

private fun MainTab.icon(): ImageVector = when (this) {
    MainTab.Home -> Icons.Filled.Home
    MainTab.Search -> Icons.Filled.Search
    MainTab.Library -> Icons.AutoMirrored.Filled.List
}

/** Shown above the tabs whenever something is loaded, on either screen. */
@Composable
private fun NowPlayingBar(onOpen: () -> Unit) {
    val playback by SonoraPlayer.state.collectAsState()
    val track = playback.track ?: return

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    val artwork = rememberArtwork(track.file)
                    if (artwork != null) {
                        Image(
                            bitmap = artwork,
                            contentDescription = "Album artwork",
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { SonoraPlayer.previous() },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                }
                IconButton(
                    onClick = { SonoraPlayer.togglePlayPause() },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playback.isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.accentText,
                    )
                }
                IconButton(
                    onClick = { SonoraPlayer.next() },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                }
            }

            val progress = if (playback.durationMs > 0L) {
                (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.accentText,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
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

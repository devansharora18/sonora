package dev.sonora.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.backend.LibraryTrack
import dev.sonora.backend.Playlist

private enum class Step { Pick, Name }

/**
 * Puts a track into a playlist: pick one, or start a new one.
 *
 * Self-contained so the Library and the player share one flow rather than two copies of the same
 * two-step dance. Passing a [track] opens it; `null` renders nothing, which is how the caller
 * closes it.
 */
@Composable
internal fun AddToPlaylistFlow(
    track: LibraryTrack?,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onAdd: (Playlist, LibraryTrack) -> Unit,
    onCreateWithTrack: (String, LibraryTrack) -> Unit,
) {
    if (track == null) return

    // Keyed on the track, so pointing the flow at a different track starts back at the list.
    var step by remember(track) { mutableStateOf(Step.Pick) }

    when (step) {
        Step.Pick -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Add to playlist") },
            text = {
                if (playlists.isEmpty()) {
                    Text("No playlists yet.")
                } else {
                    Column(
                        // Bounded and scrollable: a Column of arbitrary length inside a dialog
                        // would run off the screen, and a LazyColumn here is measured with
                        // unbounded height, which throws.
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        playlists.forEach { playlist ->
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAdd(playlist, track) }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { step = Step.Name }) { Text("New playlist") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            },
        )

        Step.Name -> NameDialog(
            title = "New playlist",
            initialName = "",
            confirmLabel = "Create",
            onDismiss = onDismiss,
            onConfirm = { name -> onCreateWithTrack(name, track) },
        )
    }
}

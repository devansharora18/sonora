package dev.sonora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.backend.DownloadState
import dev.sonora.backend.SearchHit
import dev.sonora.backend.SonoraBackend
import dev.sonora.backend.SortMode
import dev.sonora.ui.theme.onSurfaceFaint

@Composable
fun SearchScreen() {
    val context = LocalContext.current
    val state by SonoraBackend.search.collectAsState()
    val download by SonoraBackend.download.collectAsState()
    var query by remember { mutableStateOf("") }

    // YT Music's sort control, and a *fastest* source is exactly what a P2P result list needs:
    // without it you pick a peer at random and wait.
    val sort = state.sort
    fun onSort(mode: SortMode) = SonoraBackend.setSort(mode)

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = query,
            onQueryChange = { query = it },
            onSearch = { SonoraBackend.search(query.trim()) },
        )

        if (state.hits.isNotEmpty() || state.searching) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SortMode.entries.forEach { mode ->
                    FilterChip(
                        selected = sort == mode,
                        onClick = { onSort(mode) },
                        label = { Text(mode.label, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Transparent,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                            selectedLabelColor = MaterialTheme.colorScheme.background,
                        ),
                        border = null,
                    )
                }
            }
        }

        DownloadStatus(download)

        when {
            state.searching -> Note("Searching\u2026 ${state.matched} match(es) so far")

            state.query.isBlank() -> Note("Search the Soulseek network to find music.")

            state.hits.isEmpty() -> Note("No results for \u201c${state.query}\u201d.")

            else -> Note("${state.matched} match(es) from ${state.peers} peer(s)")
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.hits, key = { it.peer + it.filename }) { hit ->
                ResultRow(hit, onDownload = { SonoraBackend.download(context, hit) })
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Songs, albums, artists") },
        singleLine = true,
        shape = RoundedCornerShape(percent = 50),
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ResultRow(hit: SearchHit, onDownload: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDownload)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Remote files carry no artwork — the search response has no such field — so this is a
        // deliberate placeholder rather than a missing image.
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = hit.filename.substringAfterLast('\\'),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = listOfNotNull(
                    hit.peer,
                    quality(hit).ifEmpty { null },
                    formatSize(hit.size),
                ).joinToString("  \u00b7  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = transfer(hit),
                style = MaterialTheme.typography.labelSmall,
                // Accent is reserved for the degraded case. A free slot is the norm, so marking
                // it in colour would put the accent on every row and mean nothing.
                color = if (hit.hasFreeUploadSlot) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = hit.filename.substringBeforeLast('\\', ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(onClick = onDownload) {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = "Download",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadStatus(state: DownloadState) {
    when (state) {
        DownloadState.Idle -> Unit

        is DownloadState.Downloading -> Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "Downloading ${state.filename} \u2014 " +
                    (state.fraction?.let { "${(it * 100).toInt()}%" } ?: formatSize(state.bytes)),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            LinearProgressIndicator(
                progress = { state.fraction ?: 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is DownloadState.Completed -> Text(
            text = "Saved ${state.filename} (${formatSize(state.bytes)})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        is DownloadState.Failed -> Text(
            text = "Download failed: ${state.reason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** Best-effort quality summary; which attributes exist depends on the format and the peer. */
private fun quality(hit: SearchHit): String {
    val attributes = hit.attributes

    val parts = buildList {
        attributes.bitrateKbps?.let { add("${it}kbps") }
        attributes.sampleRateHz?.let { add("${it / 1000}kHz") }
        attributes.bitDepth?.let { add("${it}bit") }
        attributes.durationSeconds?.let { add("${it / 60}:%02d".format(it % 60)) }
    }

    return parts.joinToString(" ")
}

/**
 * The signals that decide how fast a transfer from this peer would be: whether it can start now,
 * how far back we would be queued, and how quickly it has uploaded before.
 */
private fun transfer(hit: SearchHit): String = buildList {
    add(if (hit.hasFreeUploadSlot) "slot free" else "no slot")
    if (hit.queueLength > 0) add("queued ${hit.queueLength}")
    hit.averageSpeed.takeIf { it > 0 }?.let { add("${formatSize(it)}/s") }
}.joinToString("  \u00b7  ")

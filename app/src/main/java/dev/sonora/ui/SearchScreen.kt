package dev.sonora.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.backend.DownloadState
import dev.sonora.backend.SearchHit
import dev.sonora.backend.SonoraBackend
import dev.sonora.backend.SortMode

@Composable
fun SearchScreen() {
    val context = LocalContext.current
    val state by SonoraBackend.search.collectAsState()
    val download by SonoraBackend.download.collectAsState()
    var query by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Artist, track or album") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )

            Button(
                onClick = { SonoraBackend.search(query.trim()) },
                enabled = query.isNotBlank(),
            ) {
                Text("Search")
            }
        }

        when {
            state.searching -> Text(
                text = "Searching\u2026 ${state.matched} match(es) so far",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            state.query.isBlank() -> Text(
                text = "Search the Soulseek network to find music.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            state.hits.isEmpty() -> Text(
                text = "No results for \u201c${state.query}\u201d.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            else -> Text(
                text = "${state.matched} match(es) from ${state.peers} peer(s), showing ${state.hits.size}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SortMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.sort == mode,
                    onClick = { SonoraBackend.setSort(mode) },
                    label = { Text(mode.label) },
                )
            }
        }

        DownloadStatus(download)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.hits, key = { it.peer + it.filename }) { hit ->
                ResultRow(hit, onDownload = { SonoraBackend.download(context, hit) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DownloadStatus(state: DownloadState) {
    when (state) {
        DownloadState.Idle -> Unit

        is DownloadState.Downloading -> {
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
            modifier = Modifier.padding(vertical = 8.dp),
        )

        is DownloadState.Failed -> Text(
            text = "Download failed: ${state.reason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun ResultRow(hit: SearchHit, onDownload: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDownload)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = hit.filename.substringAfterLast('\\'),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // Soulseek matches on the whole path, so a name that looks unrelated often makes sense
        // in context. Hiding the folder made correct results look wrong.
        Text(
            text = hit.filename.substringBeforeLast('\\', ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = "${hit.peer}  \u00b7  ${formatSize(hit.size)}" +
                quality(hit).let { if (it.isEmpty()) "" else "  \u00b7  $it" },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = transfer(hit),
            style = MaterialTheme.typography.bodySmall,
            color = when {
                hit.hasFreeUploadSlot -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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

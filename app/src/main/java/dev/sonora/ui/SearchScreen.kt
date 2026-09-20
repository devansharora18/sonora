package dev.sonora.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.backend.SearchHit
import dev.sonora.backend.SonoraBackend

@Composable
fun SearchScreen() {
    val state by SonoraBackend.search.collectAsState()
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
                text = "${state.matched} match(es), showing ${state.hits.size}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.hits, key = { it.peer + it.filename }) { hit ->
                ResultRow(hit)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ResultRow(hit: SearchHit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
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

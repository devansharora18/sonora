package dev.sonora.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.sonora.backend.SearchFolders
import dev.sonora.backend.SonoraBackend
import dev.sonora.backend.SortMode
import dev.sonora.ui.theme.accentText
import dev.sonora.ui.theme.onSurfaceFaint

@Composable
fun SearchScreen() {
    val context = LocalContext.current
    val searchState by SonoraBackend.search.collectAsState()
    val download by SonoraBackend.download.collectAsState()
    val settings by SonoraBackend.settings.collectAsState()
    val history by SonoraBackend.searchHistory.collectAsState()

    // Keyed on the committed query so clearing the search clears the box with it, rather than
    // leaving stale text above an empty result list.
    var query by remember(searchState.query) { mutableStateOf(searchState.query) }
    fun runSearch(term: String) {
        query = term
        if (term.isNotBlank()) SonoraBackend.search(context, term.trim())
    }

    // YT Music's sort control, and a *fastest* source is exactly what a P2P result list needs:
    // without it you pick a peer at random and wait.
    val sort = searchState.sort
    fun onSort(mode: SortMode) = SonoraBackend.setSort(mode)

    // Nothing searched yet: the recent queries are the useful thing to show, rather than an
    // instruction to go and do something.
    val showingHistory = searchState.query.isBlank() && !searchState.searching

    // Asked once, before the first download. Where the files land decides whether they survive
    // uninstalling the app, so it is worth one question rather than a silent default.
    var askingWhere by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf<SearchHit?>(null) }

    val pickFolder = rememberLauncherForActivityResult(        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            SonoraBackend.setDownloadTree(context, uri.toString())
        }

        // Cancelling the picker cancels the download: the question is still unanswered, and the
        // tap is easy to repeat.
        if (uri != null) waiting?.let { SonoraBackend.download(context, it) }
        waiting = null
    }

    fun startDownload(hit: SearchHit) {
        if (settings.downloadTreeUri == null && !settings.promptedForDownloadFolder) {
            waiting = hit
            askingWhere = true
        } else {
            SonoraBackend.download(context, hit)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Search",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
        )

        SearchBar(
            query = query,
            onQueryChange = { query = it },
            onSearch = { SonoraBackend.search(context, query.trim()) },
        )

        if (searchState.hits.isNotEmpty() || searchState.searching) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SortMode.entries.forEach { mode ->
                    FilterChip(
                        selected = sort == mode,
                        onClick = { onSort(mode) },
                        label = { Text(mode.label, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (sort == mode) {
                                MaterialTheme.colorScheme.accentText
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        ),
                    )
                }
            }
        }

        DownloadStatus(download)

        when {
            searchState.searching -> Note("Searching\u2026 ${searchState.matched} match(es) so far")

            showingHistory -> Unit

            searchState.hits.isEmpty() -> Note("No results for \u201c${searchState.query}\u201d.")

            else -> Note("${searchState.matched} match(es) from ${searchState.peers} peer(s)")
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (showingHistory) {
                if (history.isEmpty()) {
                    item { Note("Search the Soulseek network to find music.") }
                } else {
                    item {
                        Text(
                            text = "Recent searches",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }

                    items(history, key = { it }) { term ->
                        RecentSearchRow(term = term, onClick = { runSearch(term) })
                    }
                }
            } else {
                items(searchState.hits, key = { it.peer + it.filename }) { hit ->
                    ResultRow(
                        hit = hit,
                        folderSize = SearchFolders.folderOf(searchState.hits, hit).size,
                        onDownload = { startDownload(hit) },
                        onDownloadFolder = {
                            SearchFolders.folderOf(searchState.hits, hit)
                                .forEach { startDownload(it) }
                        },
                    )
                }
            }
        }
    }

    if (askingWhere) {
        AlertDialog(
            onDismissRequest = {
                askingWhere = false
                waiting = null
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Where should downloads go?") },
            text = {
                Text(
                    "Files Sonora creates in shared storage are deleted if you uninstall the " +
                        "app. Choose a folder and the files are yours to keep.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askingWhere = false
                        pickFolder.launch(null)
                    },
                ) {
                    Text("Choose folder")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        askingWhere = false
                        SonoraBackend.useDefaultDownloadFolder(context)
                        waiting?.let { SonoraBackend.download(context, it) }
                        waiting = null
                    },
                ) {
                    Text("Use default", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
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
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedLeadingIconColor = MaterialTheme.colorScheme.accentText,
            unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun RecentSearchRow(term: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = term,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
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
private fun ResultRow(
    hit: SearchHit,
    folderSize: Int,
    onDownload: () -> Unit,
    onDownloadFolder: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDownload)
            .padding(horizontal = 20.dp, vertical = 7.dp),
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // Size is deliberately not part of this line: it was being truncated away
                    // behind the peer name, and it decides whether a download is worth starting.
                    text = listOfNotNull(hit.peer, quality(hit).ifEmpty { null })
                        .joinToString("  \u00b7  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatSize(hit.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Text(
                text = hit.filename.substringBeforeLast('\\', ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(end = 2.dp),
        ) {
            Text(
                text = if (hit.hasFreeUploadSlot) "Ready" else "Queued ${hit.queueLength}",
                style = MaterialTheme.typography.labelSmall,
                color = if (hit.hasFreeUploadSlot) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                maxLines = 1,
            )
            hit.averageSpeed.takeIf { it > 0 }?.let {
                Text(
                    text = "${formatSize(it)}/s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceFaint,
                    maxLines = 1,
                )
            }
        }

        IconButton(onClick = onDownload) {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = "Download",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Bulk download sits behind a menu: it acts on other results too, so it should not look
        // like the button that fetches this one.
        var menuOpen by remember { mutableStateOf(false) }

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "Result options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Download this file") },
                    onClick = {
                        menuOpen = false
                        onDownload()
                    },
                )

                if (folderSize > 1) {
                    DropdownMenuItem(
                        text = { Text("Download folder ($folderSize files)") },
                        onClick = {
                            menuOpen = false
                            onDownloadFolder()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadStatus(state: DownloadState) {
    when (state) {
        DownloadState.Idle -> Unit

        is DownloadState.Downloading -> Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = buildString {
                    append("Downloading ${state.filename} \u2014 ")
                    append(state.fraction?.let { "${(it * 100).toInt()}%" } ?: formatSize(state.bytes))
                    if (state.remaining > 0) append("  \u00b7  ${state.remaining} queued")
                },
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
            color = MaterialTheme.colorScheme.accentText,
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

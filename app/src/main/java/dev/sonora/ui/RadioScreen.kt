package dev.sonora.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.backend.SonoraBackend
import dev.sonora.backend.radio.RadioMode
import dev.sonora.backend.radio.RadioSeed
import dev.sonora.backend.radio.RadioSetup
import dev.sonora.ui.theme.accentText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The artist disc size in the picker and the picked list. */
private val SEED_ART = 40.dp

/**
 * Pick artists, then choose how far to reach.
 *
 * One screen rather than three entry points, because the three mixes are answers to the same
 * question and the question is "who". The mixes are therefore not tabs or modes stored away in a
 * menu: they sit under the same selection, and switching between them costs one tap.
 */
@Composable
fun RadioScreen(
    onBack: () -> Unit,
    onPlay: (RadioMode, List<dev.sonora.backend.LibraryTrack>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setup by SonoraBackend.radio.collectAsState()

    var picking by remember { mutableStateOf(false) }
    var building by remember { mutableStateOf<RadioMode?>(null) }
    var failure by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { SonoraBackend.refreshRadio(context) }

    fun start(mode: RadioMode) {
        if (building != null || !setup.isReady) return

        failure = false
        building = mode
        scope.launch {
            val tracks = runCatching { SonoraBackend.startRadio(context, mode) }.getOrDefault(emptyList())
            building = null
            if (tracks.isEmpty()) {
                failure = true
            } else {
                onPlay(mode, tracks)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = listBottomPadding(withMiniPlayer = false)),
    ) {
        Row(modifier = Modifier.padding(start = PAGE_GUTTER, top = 8.dp)) {
            PageBackButton(onClick = onBack)
        }

        Text(
            text = "Radio",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        )

        Text(
            text = "Pick a few artists, then choose how far out the mix should reach.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 4.dp),
        )

        Spacer(Modifier.height(12.dp))

        SettingsGroup(
            header = "Artists",
            footer = if (setup.seeds.isEmpty()) {
                "Up to ${RadioSetup.MAX_SEEDS}. Everything below is built from them."
            } else {
                null
            },
        ) {
            setup.seeds.forEachIndexed { index, seed ->
                if (index > 0) RowDivider()
                SeedRow(
                    seed = seed,
                    onRemove = { SonoraBackend.removeRadioSeed(context, seed.name) },
                )
            }

            if (setup.seeds.isNotEmpty()) RowDivider()

            SettingsActions {
                OutlinedButton(
                    onClick = { picking = true },
                    enabled = setup.seeds.size < RadioSetup.MAX_SEEDS,
                ) {
                    Text(if (setup.seeds.isEmpty()) "Add an artist" else "Add another")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsGroup(header = "Play a mix") {
            RadioMode.entries.forEachIndexed { index, mode ->
                if (index > 0) RowDivider()
                MixRow(
                    mode = mode,
                    enabled = setup.isReady && building == null,
                    building = building == mode,
                    onPlay = { start(mode) },
                )
            }
        }

        if (failure) {
            Text(
                text = "Could not build that mix. Check your connection and try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER + 6.dp, vertical = 12.dp),
            )
        }

        if (!setup.isReady) {
            Text(
                text = "An artist can be added from your listening history or by searching for one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER + 6.dp, vertical = 12.dp),
            )
        }
    }

    if (picking) {
        ArtistPickerSheet(
            selected = setup.seeds,
            onAdd = { seed -> SonoraBackend.addRadioSeed(context, seed) },
            onDismiss = { picking = false },
        )
    }
}

/** One picked artist: a disc, a name, and the way to take it back off. */
@Composable
private fun SeedRow(seed: RadioSeed, onRemove: () -> Unit) {
    val artwork = rememberArtworkAt(seed.artworkUrl, px = SEED_ART_PX)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtistDisc(artwork = artwork, name = seed.name)

        Spacer(Modifier.width(12.dp))

        Text(
            text = seed.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        CircleGlyph(
            icon = Icons.Filled.Close,
            contentDescription = "Remove ${seed.name}",
            onClick = onRemove,
            size = 44.dp,
            disc = false,
        )
    }
}

/** One mix: what it does, and the button that starts it. */
@Composable
private fun MixRow(
    mode: RadioMode,
    enabled: Boolean,
    building: Boolean,
    onPlay: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onPlay) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = mode.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = mode.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }

        Spacer(Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) {
                        MaterialTheme.colorScheme.accentText
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (building) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play ${mode.label}",
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.background
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

/**
 * The artist search, as a sheet over the Radio screen.
 *
 * Suggestions come from what the model already knows, so the common case — adding the artists you
 * actually listen to — needs no typing at all. The field is there for the rest.
 */
@Composable
private fun ArtistPickerSheet(
    selected: List<RadioSeed>,
    onAdd: (RadioSeed) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<RadioSeed>>(emptyList()) }
    var results by remember { mutableStateOf<List<RadioSeed>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        suggestions = runCatching { SonoraBackend.radioSuggestions(context) }.getOrDefault(emptyList())
    }

    LaunchedEffect(query) {
        searchJob?.cancel()
        if (query.isBlank()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }

        searching = true
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            results = runCatching {
                SonoraBackend.searchRadioArtists(context, query.trim())
            }.getOrDefault(emptyList())
            searching = false
        }
    }

    val shown = if (query.isBlank()) suggestions else results
    val picked = remember(selected) { selected.mapTo(HashSet()) { it.key } }

    SonoraSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.times(0.72f).dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = "Add an artist",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            SearchField(
                query = query,
                onQueryChange = { query = it },
                onSubmit = { },
                placeholder = "Search artists",
            )

            when {
                searching -> LoadingRow("Searching…")

                shown.isEmpty() && query.isBlank() ->
                    LoadingRow("Listen to something first, or search for an artist.")

                shown.isEmpty() -> LoadingRow("No artists found for “${query.trim()}”.")

                else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    if (query.isBlank()) {
                        item(key = "suggestion-header") {
                            Text(
                                text = "From your listening",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                        }
                    }

                    items(shown, key = { it.key.ifBlank { it.name } }) { seed ->
                        PickerRow(
                            seed = seed,
                            added = seed.key in picked,
                            atLimit = selected.size >= RadioSetup.MAX_SEEDS,
                            onAdd = { onAdd(seed) },
                        )
                    }
                }
            }
        }
    }
}

/** One candidate artist in the sheet. */
@Composable
private fun PickerRow(
    seed: RadioSeed,
    added: Boolean,
    atLimit: Boolean,
    onAdd: () -> Unit,
) {
    val artwork = rememberArtworkAt(seed.artworkUrl, px = SEED_ART_PX)
    val enabled = !added && !atLimit

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onAdd) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtistDisc(artwork = artwork, name = seed.name)

        Spacer(Modifier.width(12.dp))

        Text(
            text = seed.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Text(
            text = if (added) "Added" else "Add",
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.accentText
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** The artist's picture, or a person glyph when there is none. */
@Composable
private fun ArtistDisc(artwork: androidx.compose.ui.graphics.ImageBitmap?, name: String) {
    Box(
        modifier = Modifier
            .size(SEED_ART)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = name,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun LoadingRow(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
    )
}

/** The artist disc's bitmap: a fraction of its drawn size, which is all a 40dp circle needs. */
private const val SEED_ART_PX = 160

/** How long typing has to stop before the artist search runs. */
private const val SEARCH_DEBOUNCE_MS = 350L

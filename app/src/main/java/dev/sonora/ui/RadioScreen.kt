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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.backend.SonoraBackend
import dev.sonora.backend.radio.RadioSeed
import dev.sonora.backend.radio.RadioSetup
import dev.sonora.backend.radio.SavedRadio
import dev.sonora.ui.theme.accentText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The artist disc size in the picker and the picked list. */
private val SEED_ART = 40.dp

/**
 * Pick artists, set how far the mix roams, play it — and keep it.
 *
 * One control carries the whole character of a mix. Variety is a position, not a choice between
 * three named things: at the left the radio is only the artists that were picked, and it moves
 * outward from there. The slider's own caption says where it currently is, so the number on it is
 * never the only explanation.
 */
@Composable
fun RadioScreen(
    onBack: () -> Unit,
    onPlay: (List<dev.sonora.backend.LibraryTrack>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by SonoraBackend.radio.collectAsState()
    val draft = state.draft

    var picking by remember { mutableStateOf(false) }
    var building by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf(false) }
    var savedNotice by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { SonoraBackend.refreshRadio(context) }

    fun play(savedId: String? = null) {
        if (building || !draft.isReady) return

        failure = false
        building = true
        scope.launch {
            val tracks = runCatching {
                if (savedId != null) {
                    SonoraBackend.startSavedRadio(context, savedId)
                } else {
                    SonoraBackend.startRadio(context)
                }
            }.getOrDefault(emptyList())

            building = false
            if (tracks.isEmpty()) {
                failure = true
            } else {
                onPlay(tracks)
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

        SettingsGroup(
            header = "Artists",
            footer = if (draft.seeds.isEmpty()) {
                "Up to ${RadioSetup.MAX_SEEDS}. The mix is built from them."
            } else {
                null
            },
        ) {
            draft.seeds.forEachIndexed { index, seed ->
                if (index > 0) RowDivider()
                SeedRow(
                    seed = seed,
                    onRemove = { SonoraBackend.removeRadioSeed(context, seed.name) },
                )
            }

            if (draft.seeds.isEmpty()) {
                EmptyHint()
            } else {
                RowDivider()
            }

            SettingsActions {
                OutlinedButton(
                    onClick = { picking = true },
                    enabled = draft.seeds.size < RadioSetup.MAX_SEEDS,
                ) {
                    Text(if (draft.seeds.isEmpty()) "Add an artist" else "Add another")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        VarietyPanel(
            setup = draft,
            enabled = draft.isReady,
            onChange = { stop -> SonoraBackend.setRadioStop(context, stop) },
            onFamiliarChange = { SonoraBackend.setRadioFamiliar(context, it) },
        )

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { play() },
                enabled = draft.isReady && !building,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                shape = RoundedCornerShape(13.dp),
            ) {
                if (building) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Play mix")
                }
            }

            OutlinedButton(
                onClick = {
                    SonoraBackend.saveRadio(context)
                    savedNotice = true
                },
                enabled = draft.isReady,
                modifier = Modifier.height(50.dp),
                shape = RoundedCornerShape(13.dp),
            ) {
                Text("Save")
            }
        }

        if (savedNotice) {
            Text(
                text = "Saved. It is on Home and in the Library now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER + 4.dp, vertical = 10.dp),
            )
        }

        if (failure) {
            Text(
                text = "Could not build that mix. Check your connection and try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER + 4.dp, vertical = 10.dp),
            )
        }

        if (state.saved.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))

            SettingsGroup(
                header = "Saved radios",
                footer = "Tap one to tune it. Its artists and variety come back into this page.",
            ) {
                state.saved.forEachIndexed { index, radio ->
                    if (index > 0) RowDivider()
                    SavedRadioRow(
                        radio = radio,
                        onOpen = {
                            SonoraBackend.editRadio(context, radio.id)
                            savedNotice = false
                        },
                        onPlay = { play(savedId = radio.id) },
                        onDelete = { SonoraBackend.deleteRadio(context, radio.id) },
                    )
                }
            }
        }
    }

    if (picking) {
        ArtistPickerSheet(
            selected = draft.seeds,
            onAdd = { seed -> SonoraBackend.addRadioSeed(context, seed) },
            onDismiss = { picking = false },
        )
    }
}

/**
 * The variety slider, its end labels, and the sentence that says where the mix currently sits.
 *
 * The caption is the important half. A slider whose meaning lives only in its position is a control
 * you have to move before you can understand it; one that says "mostly your artists, with a few
 * neighbours" is readable at rest.
 */
@Composable
private fun VarietyPanel(
    setup: RadioSetup,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    onFamiliarChange: (Boolean) -> Unit,
) {
    // The thumb follows the finger while it is down and snaps to a stop when it lifts. Snapping
    // during the drag would fight the gesture; not snapping at all would leave the mix on a share
    // the caption cannot name.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shownStop = dragging?.let { setup.stopFor(it) } ?: setup.stop

    SettingsGroup(header = "Variety") {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Slider(
                value = dragging ?: setup.variety,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onChange(setup.stopFor(it)) }
                    dragging = null
                },
                enabled = enabled,
                valueRange = 0f..1f,
                // One step per gap between the five ratios.
                steps = RadioSetup.SHARES.size - 2,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.accentText,
                    activeTrackColor = MaterialTheme.colorScheme.accentText,
                    // Outline, not surfaceVariant: the track sits on a surfaceVariant card, so the
                    // same colour makes the right half of the range invisible.
                    inactiveTrackColor = MaterialTheme.colorScheme.outline,
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Only these artists",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Something new",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = varietyCaption(setup, shownStop),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        RowDivider()

        SettingsRow(
            title = "Familiar only",
            subtitle = "Leave out anything you have not played, liked or downloaded.",
            trailing = { SettingSwitch(setup.familiarOnly, onFamiliarChange) },
            onClick = { onFamiliarChange(!setup.familiarOnly) },
        )
    }
}

/** What the slider currently means, as the ratio the mix will be built at. */
private fun varietyCaption(setup: RadioSetup, stop: Int): String {
    val names = when (setup.seeds.size) {
        0 -> "your artists"
        1 -> setup.seeds.first().name
        2 -> setup.seeds.joinToString(" and ") { it.name }
        else -> "${setup.seeds.take(2).joinToString(", ") { it.name }} and more"
    }

    val share = (RadioSetup.SHARES[stop.coerceIn(0, RadioSetup.SHARES.lastIndex)] * 100).roundToInt()
    return when (share) {
        100 -> "Only $names, the whole queue."
        else -> "$share% $names · ${100 - share}% from around them."
    }
}

/** One saved radio: what it is, and the two things you can do with it. */
@Composable
internal fun SavedRadioRow(
    radio: SavedRadio,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = radio.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = radioSummary(radio),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        CircleGlyph(
            icon = Icons.Filled.Close,
            contentDescription = "Delete ${radio.name}",
            onClick = onDelete,
            size = 44.dp,
            glyphSize = 18.dp,
            disc = false,
        )

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.accentText)
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Play ${radio.name}",
                tint = MaterialTheme.colorScheme.background,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/** "3 artists · a mix of theirs and new" — enough to tell two saved radios apart. */
internal fun radioSummary(radio: SavedRadio): String {
    val artists = if (radio.seeds.size == 1) "1 artist" else "${radio.seeds.size} artists"
    if (radio.familiarOnly) return "$artists · familiar only"

    val share = (RadioSetup.SHARES[radio.stop.coerceIn(0, RadioSetup.SHARES.lastIndex)] * 100).roundToInt()
    return if (share == 100) "$artists · only them" else "$artists · $share% them"
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

@Composable
private fun EmptyHint() {
    Text(
        text = "No artists yet. Add one from your listening history or by searching.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
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
    var loadingSuggestions by remember { mutableStateOf(true) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        // One catalogue lookup per artist, so this is not instant on a cold start. Saying so is
        // what stops the sheet claiming the listener has no history.
        suggestions = runCatching { SonoraBackend.radioSuggestions(context) }.getOrDefault(emptyList())
        loadingSuggestions = false
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

    // Two artists whose names fold together are one pick, and two rows with the same key crash
    // the list. Deduped here rather than keyed by index so a row keeps its identity as results
    // arrive and disappear.
    val shown = (if (query.isBlank()) suggestions else results)
        .distinctBy { it.browseId ?: it.key }
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

                loadingSuggestions && query.isBlank() -> LoadingRow("Loading your artists…")

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

                    items(shown, key = { it.browseId ?: it.name }) { seed ->
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
internal fun ArtistDisc(artwork: androidx.compose.ui.graphics.ImageBitmap?, name: String) {
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
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 20.dp),
    )
}

/** The artist disc's bitmap: a fraction of its drawn size, which is all a 40dp circle needs. */
private const val SEED_ART_PX = 160

/** How long typing has to stop before the artist search runs. */
private const val SEARCH_DEBOUNCE_MS = 350L

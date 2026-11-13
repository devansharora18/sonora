package dev.sonora.ui

import androidx.compose.foundation.background
import dev.sonora.backend.SonoraBackend
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.R
import dev.sonora.backend.Shelves
import dev.sonora.ytm.YtmCategory

/**
 * YouTube Music's own categories, two to a row and as many rows as they take.
 *
 * A grid and not a row of circles, and both of those are the point. A row that scrolls sideways
 * hides everything but the first four and gives no idea how much there is; a column that scrolls
 * with the page is read the way a page is read. Rounded tiles rather than discs, because the
 * original is a grid of tiles and a name inside a circle is a caption on a swatch.
 *
 * Wrapping rather than a lazy grid, because this sits inside a list that already scrolls: a lazy
 * grid inside a lazy column has no bounded height to lay itself out in, and would either throw or
 * collapse. The category count is small and fixed, so laying it all out costs nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoryGrid(
    categories: List<YtmCategory>,
    chosen: YtmCategory?,
    onChoose: (YtmCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (categories.isEmpty()) return

    // Read once and handed to every tile, rather than each tile subscribing: forty tiles each
    // collecting the same flow is forty observers on one value, and the grid recomposes whenever
    // any of the forty answers.
    val shelves = SonoraBackend.shelves.collectAsState().value

    // Measured rather than weighted. A weight hands the child the leftover width, which a child
    // with an intrinsic size — a shipped painter does — takes as a minimum and overrides, so a row
    // of pictures ends up one full-width tile with a half-width one beside it. A width computed
    // from the row is the only thing that holds two across whatever the pictures are.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val tile = ((maxWidth - PAGE_GUTTER * 2 - SHELF_SPACING) / 2).coerceAtLeast(80.dp)

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_GUTTER),
            maxItemsInEachRow = 2,
            horizontalArrangement = Arrangement.spacedBy(SHELF_SPACING),
            verticalArrangement = Arrangement.spacedBy(SHELF_SPACING),
        ) {
            categories.forEach { category ->
                CategoryTile(
                    category = category,
                    // The grid's own file is what the tile draws; this is only the fallback for a
                    // category that has no file, and it is the one the shelf below shares.
                    artwork = shelves.playlists[category.title]
                        ?.firstNotNullOfOrNull { it.artworkUrl },
                    selected = category.title == chosen?.title,
                    onClick = { onChoose(category) },
                    modifier = Modifier.width(tile),
                )
            }
        }
    }
}

/**
 * One category: a tile with a picture in it and the name over the picture.
 *
 * The picture is borrowed from the first playlist in the category, which is what the category
 * actually looks like — a grid of forty identical colour blocks is a colour picker, not a place to
 * go. A category that has not answered yet gets a two-tone gradient built from its own stripe
 * instead, so every tile has depth from the first frame and the grid never looks half-finished.
 *
 * The scrim over the picture is not optional: the covers are photographs, and a name in white over
 * somebody's holiday is a name nobody can read.
 */
@Composable
private fun CategoryTile(
    category: YtmCategory,
    /** The cover borrowed from the category's first playlist, or null until it has answered. */
    artwork: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = Color(category.color)
    val shipped = rememberCategoryArtwork(category.title)
    val fetched = rememberArtworkAt(artwork, px = CARD_ART_PX)

    // A grey stripe is a category with no colour of its own, and its gradient is a flat block of
    // #3a3a3a. Flat is not a tile: it reads as one that failed to load. The note glyph gives it
    // something to be.
    val flat = tint.chroma() < ACHROMATIC_CHROMA

    Box(
        modifier = modifier
            .height(96.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(gradientFor(tint))
            // Ringed rather than scaled when chosen: a tile that grows pushes every tile after it
            // along, so picking one in the middle of a row rearranges the row.
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.onBackground, RoundedCornerShape(14.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
    ) {
        if (shipped == null && fetched == null && flat) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.35f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp),
            )
        }

        if (shipped != null) {
            Image(
                painter = shipped,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (fetched != null) {
            Image(
                bitmap = fetched,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // A wash of the category's own colour over the top, so forty borrowed covers still read
            // as forty *categories* rather than as forty album covers.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to tint.copy(alpha = 0.40f),
                            0.55f to tint.copy(alpha = 0.62f),
                            1f to tint.copy(alpha = 0.88f),
                        ),
                    ),
            )
        }

        // The scrim and the name are at the top, not the bottom. A borrowed cover is not a blank
        // picture: most of them print their own headline across the lower half — "GAMING HITS",
        // "Bollywood Melancholy" — and a name drawn there lands on top of it, so the tile says two
        // different things in the same place. The top of a YouTube Music cover is the picture.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.82f),
                        0.34f to Color.Black.copy(alpha = 0.42f),
                        0.62f to Color.Black.copy(alpha = 0.10f),
                        1f to Color.Transparent,
                    ),
                ),
        )

        Text(
            text = category.title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/**
 * The tile's own depth, from its own colour.
 *
 * Diagonal rather than vertical, and two steps of it, because a flat block of one colour at this
 * size is a rectangle of paint. The light end is the category's colour lifted and the dark end is
 * it pushed down, so the two ends still read as the same hue the stripe was.
 */
private fun gradientFor(tint: Color): Brush {
    val dark = Color(
        red = tint.red * 0.55f,
        green = tint.green * 0.55f,
        blue = tint.blue * 0.55f,
        alpha = 1f,
    )
    val light = if (tint.perceivedLuminance() < 0.35f) tint.lighten(0.22f) else tint.lighten(0.12f)
    return Brush.linearGradient(
        colors = listOf(light, dark),
        start = Offset.Zero,
        end = Offset(600f, 600f),
    )
}

/**
 * The playlists behind the chosen category.
 *
 * Real shelves with real covers, because that is what a page with nothing else on it has to be: a
 * grid of names is a list of things to read, and a wall of covers is a page of somewhere to start.
 */
@Composable
internal fun PlaylistShelf(
    title: String,
    subtitle: String,
    playlists: List<dev.sonora.ytm.YtmPlaylistRef>,
    onOpen: (dev.sonora.ytm.YtmPlaylistRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (playlists.isEmpty()) return

    Column(modifier = modifier.padding(bottom = 12.dp)) {
        SectionHeader(title = title, subtitle = subtitle)

        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(SHELF_SPACING),
        ) {
            items(playlists, key = { it.browseId }) { playlist ->
                MediaCard(
                    artwork = rememberArtworkAt(playlist.artworkUrl, px = CARD_ART_PX),
                    title = playlist.title,
                    subtitle = playlist.subtitle ?: "Playlist",
                    shape = RoundedCornerShape(CARD_CORNER),
                    icon = Icons.Filled.LibraryMusic,
                    onClick = { onOpen(playlist) },
                )
            }
        }
    }
}

/**
 * The whole thing: a grid of categories above the playlists behind the chosen one.
 *
 * A placeholder rather than nothing while the categories are in flight, because a page that is
 * about to fill with a grid of coloured circles should say so before the circles arrive and not
 * after.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BrowseShelves(
    shelves: Shelves,
    /** Opens the category's own page. A tile is a door, not a toggle. */
    onChoose: (YtmCategory) -> Unit,
    onOpen: (dev.sonora.ytm.YtmPlaylistRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (shelves.categories.isEmpty()) {
        if (shelves.loading) {
            Column(modifier = modifier.padding(bottom = 20.dp)) {
                ShelfSkeletonRow(count = 4)
                Spacer(Modifier.height(20.dp))
                ShelfSkeletonRow(count = 3)
            }
        }
        return
    }

    val chosen = shelves.chosen ?: shelves.categories.firstOrNull()
    val playlists = chosen?.let { shelves.playlists[it.title] }.orEmpty()

    Column(modifier = modifier) {
        ShelfHeader(
            title = "Browse all",
            subtitle = "YouTube Music's own shelves",
        )

        CategoryGrid(
            categories = shelves.categories,
            chosen = chosen,
            onChoose = onChoose,
        )

        Spacer(Modifier.height(24.dp))

        when {
            playlists.isNotEmpty() -> PlaylistShelf(
                title = chosen?.title ?: "Playlists",
                subtitle = "Curated by YouTube Music",
                playlists = playlists,
                onOpen = onOpen,
            )

            chosen != null && shelves.playlists.containsKey(chosen.title) -> EmptyState(
                icon = Icons.Filled.LibraryMusic,
                title = "Nothing in this one",
                message = "Pick another category, or search for what you want.",
            )

            else -> ShelfSkeletonRow(count = 3)
        }
    }
}

/** A row of stand-in cards at the real card metrics, so nothing jumps when the shelf lands. */
@Composable
internal fun ShelfSkeletonRow(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = PAGE_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(SHELF_SPACING),
    ) {
        repeat(count) {
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .height(140.dp)
                    .clip(RoundedCornerShape(CARD_CORNER))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.LibraryMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(34.dp),
                )
            }
        }
    }
}

private fun Color.lighten(by: Float): Color = Color(
    red = red + (1f - red) * by,
    green = green + (1f - green) * by,
    blue = blue + (1f - blue) * by,
    alpha = alpha,
)

private fun Color.perceivedLuminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

/** How far a colour is from grey: the spread between its strongest and weakest channel. */
private fun Color.chroma(): Float {
    val highest = maxOf(red, green, blue)
    val lowest = minOf(red, green, blue)
    return highest - lowest
}

/** Below this, a category's stripe carries no hue and its gradient is a grey block. */
private const val ACHROMATIC_CHROMA = 0.05f

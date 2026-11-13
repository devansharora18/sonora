package dev.sonora.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sonora.backend.LibraryTrack
import dev.sonora.ui.theme.accentText

/**
 * A shelf's heading: what the row is, one line saying why, and a way into all of it.
 *
 * [onShowAll] is the third thing rather than an assumption. A shelf that happens to show everything
 * has nothing to offer, and a "Show all" that opens a list identical to the row above it is worse
 * than no link at all.
 */
@Composable
internal fun ShelfHeader(
    title: String,
    subtitle: String = "",
    onShowAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (onShowAll != null) {
            Text(
                text = "Show all",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.accentText,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = onShowAll)
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * A square card in a shelf: artwork, a title, and a quiet line under it.
 *
 * 150dp, which on a phone puts two whole cards and the edge of a third on screen. That is the
 * number that matters — a card wide enough to show only one and a sliver says "there are more" by
 * wasting the space it is in, and one narrow enough to show four says the shelf is a list.
 */
@Composable
internal fun ShelfCard(
    artwork: ImageBitmap?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    /**
     * Tracks whose own covers make this card's picture, for anything that has no picture of its own
     * — a playlist. Empty for a card that is its own image, and for one that has only an icon.
     */
    mosaic: List<LibraryTrack> = emptyList(),
    /**
     * How many lines the title may take.
     *
     * One everywhere except a radio, whose name is its artists: a truncated "Billie Eilish, Arctic
     * …" is not a name, and the card is the only place on Home that says which mix this is.
     */
    titleLines: Int = 1,
) {
    Column(
        modifier = modifier
            .width(SHELF_CARD_WIDTH)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .thumbnailBorder(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                artwork != null -> Image(
                    bitmap = artwork,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )

                mosaic.isNotEmpty() -> PlaylistMosaic(
                    tracks = mosaic,
                    shape = RoundedCornerShape(12.dp),
                )

                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.accentText,
                    modifier = Modifier.size(34.dp),
                )

                else -> Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(34.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = titleLines,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The lead card of a feed: wide, taller than it is wide, caption over a scrim.
 *
 * The shape is the point. A square card in a row of square cards is one more of the same thing, and
 * a lead shelf has to be legible as the lead from across the room — so it is nearly square but
 * taller, and the caption sits on the artwork under a scrim rather than beside it, which doubles
 * what fits in the space above it.
 */
@Composable
internal fun HeroCard(
    artwork: ImageBitmap?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(HERO_CARD_RATIO)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .thumbnailBorder(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
    ) {
        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                // The scrim has to be deep enough to carry white text over any cover, and start
                // high enough that a two-line caption is entirely on it.
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                    ),
                )
                .padding(start = 16.dp, end = 16.dp, top = 34.dp, bottom = 14.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A row of cards, with the lead card sized from the row's own width.
 *
 * The hero's width is a fraction of the row rather than a fixed number so the next card always
 * peeks in past it — a lead card that fills the row leaves nothing to suggest there is a second one.
 */
@Composable
internal fun HeroShelf(
    title: String,
    subtitle: String = "",
    cards: List<HeroEntry>,
    onOpen: (HeroEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(bottom = 26.dp)) {
        ShelfHeader(title = title, subtitle = subtitle)

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = heroCardWidth(maxWidth)

            LazyRow(
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(SHELF_SPACING),
            ) {
                items(cards, key = { it.title + it.subtitle }) { entry ->
                    HeroCard(
                        artwork = entry.artwork,
                        title = entry.title,
                        subtitle = entry.subtitle,
                        onClick = { onOpen(entry) },
                        modifier = Modifier.width(width),
                    )
                }
            }
        }
    }
}

/** One entry in a hero shelf. Carries the artwork rather than a key, because the two sources differ. */
data class HeroEntry(
    val title: String,
    val subtitle: String,
    val artwork: ImageBitmap?,
    val tracks: List<LibraryTrack> = emptyList(),
)

/**
 * The heading on a group of results inside a list.
 *
 * A shelf on a page of its own can carry a big heading, because there is nothing else competing
 * for the top of the screen. A group *within* a list cannot: it arrives halfway down, under a dozen
 * rows and above another dozen, and a 22dp heading there reads as a new page starting rather than as
 * a label on what is under it. This is the small one, for that case.
 */
@Composable
internal fun ResultSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(
            start = PAGE_GUTTER,
            end = PAGE_GUTTER,
            top = 16.dp,
            bottom = 6.dp,
        ),
    )
}

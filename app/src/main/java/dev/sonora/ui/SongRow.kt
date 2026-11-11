package dev.sonora.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.backend.LibraryTrack
import dev.sonora.ui.theme.accentText

/**
 * The row every list of tracks is drawn from.
 *
 * One row rather than one per screen, because the Library, a playlist, a search result and the
 * player queue are all the same list of the same thing and any difference between them is a
 * difference nobody asked for.
 *
 * The optional trailing slots are what let one row cover every case: a library row passes its
 * like button and an overflow, a search row passes only the overflow, a numbered album tracklist
 * passes a number instead of artwork, and the queue passes nothing at all. What none of them can
 * change is the 52dp of artwork, the 14dp beside it and the two text styles — which is what keeps
 * a list of mixed row types scannable as one column rather than as three.
 */

/** The leading square in a row, and the gap beside it. Two numbers, because they always agree. */
private val ROW_ART = 52.dp

private val ROW_GAP = 14.dp

@Composable
internal fun SongRow(
    track: LibraryTrack,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    /** Replaces the artwork with a position in a tracklist, for album and playlist listings. */
    trackNumber: Int? = null,
    /** The line under the title. Passed in because it differs: artist, or artist and file size. */
    meta: String = listOfNotNull(track.artist, track.album).joinToString("  ·  "),
    durationText: String? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    activeTint: Color = MaterialTheme.colorScheme.accentText,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** Null hides the "already downloaded" mark, for lists where it would be noise. */
    downloadedTint: Color? = MaterialTheme.colorScheme.accentText,
    /**
     * Drawn after the metadata and before the overflow.
     *
     * The one thing a list may add that the row cannot know about: the library's like button, the
     * playlist's remove button. A slot rather than another row shape, because the whole point of one
     * row is that the artwork, the gap and the two text styles are the same everywhere — a second
     * row for the library would be free to disagree about all four.
     */
    trailing: @Composable RowScope.() -> Unit = {},
    /** Whether a hairline is drawn under this row. Off for the last one in a list. */
    divider: Boolean = false,
    dividerColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
) {
    val tint = if (isCurrent) activeTint else MaterialTheme.colorScheme.onBackground
    val background by animateColorAsState(
        targetValue = if (isCurrent) activeTint.copy(alpha = 0.14f) else Color.Transparent,
        label = "rowBackground",
    )

    Box(modifier = modifier.fillMaxWidth()) {
        // The hairline that separates one row from the next, drawn behind it and inset past the
        // artwork. A line that runs the full width cuts every row's leading square in half, and a
        // list of rows with no separation at all reads as a wall of text where the eye has nothing
        // to catch on between entries.
        if (divider) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = ROW_DIVIDER_INSET)
                    .height(0.5.dp)
                    .background(dividerColor),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .rowClickable(onClick = onClick, onLongClick = onLongPress)
                .padding(horizontal = PAGE_GUTTER, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                trackNumber != null -> Box(
                    modifier = Modifier.size(ROW_ART),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isCurrent) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Now playing",
                            tint = activeTint,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        Text(
                            text = "$trackNumber",
                            style = MaterialTheme.typography.bodyLarge,
                            color = subtitleColor,
                        )
                    }
                }

                else -> ArtworkTile(track, ROW_ART)
            }

            Spacer(Modifier.width(ROW_GAP))

            Column(Modifier.weight(1f)) {
                ExplicitSongTitle(
                    title = track.title,
                    isExplicit = false,
                    style = MaterialTheme.typography.titleMedium,
                    color = tint,
                )
                Spacer(Modifier.size(2.dp))
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = subtitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (track.file != null && downloadedTint != null) {
                Spacer(Modifier.width(8.dp))
                DownloadedBadge(tint = downloadedTint)
            }

            if (isCurrent && trackNumber == null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Now playing",
                    tint = activeTint,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (durationText != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = durationText,
                    style = MaterialTheme.typography.labelMedium,
                    color = subtitleColor,
                )
            }

            trailing()

            if (onMore != null) {
                Spacer(Modifier.width(8.dp))
                CircleGlyph(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = "More",
                    onClick = onMore,
                    // 48, not 36: the glyph stays the size it is, and the target grows around it.
                    size = 48.dp,
                    glyphSize = 20.dp,
                    disc = false,
                )
            }
        }
    }
}

/** The 52dp leading square: artwork when there is any, a note glyph when there is not. */
@Composable
private fun ArtworkTile(track: LibraryTrack, size: androidx.compose.ui.unit.Dp) {
    val shape = RoundedCornerShape(CARD_CORNER)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .thumbnailBorder(shape),
        contentAlignment = Alignment.Center,
    ) {
        val artwork = rememberTrackArtwork(track)
        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = null,
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
}

/** A row with no artwork and no trailing controls, for lists that are all text. */
@Composable
internal fun BrowseRow(
    title: String,
    subtitle: String,
    artwork: androidx.compose.ui.graphics.ImageBitmap?,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(CARD_CORNER),
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .rowClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(ROW_ART)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .thumbnailBorder(shape),
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
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.width(ROW_GAP))

        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
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
}

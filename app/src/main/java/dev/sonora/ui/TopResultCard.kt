package dev.sonora.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.backend.LibraryTrack
import dev.sonora.ytm.YtmEntity

/**
 * The search page's one promoted result, above the list it also appears in.
 *
 * A results list is scanned, not read: the eye goes down the artwork column looking for a title it
 * recognises, and the one it is most likely to recognise is the one the search ranked highest. So
 * that track is shown once more, larger, with the two things worth doing to it spelled out — and the
 * list below is left whole, so the promotion is an addition rather than a rearrangement.
 *
 * The buttons are outlined rather than filled because there are two of them and neither is the
 * obvious next step: one is the thing a listener came for, the other is the thing they do when they
 * already know the song. A filled button on the left would claim to be the answer when it is only
 * one of two.
 */
@Composable
internal fun TopResultCard(
    track: LibraryTrack,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 18.dp, bottom = 10.dp)
            .combinedClickable(onClick = onPlay, onLongClick = onMore),
    ) {
        Text(
            text = "Top result",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            val shape = RoundedCornerShape(10.dp)
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .thumbnailBorder(shape),
                contentAlignment = Alignment.Center,
            ) {
                val artwork = rememberTrackArtwork(track, px = ROW_ART_PX)
                if (artwork != null) {
                    Image(
                        bitmap = artwork,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = track.artist.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onMore, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "More",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Filled, the other outlined: Play is the thing this card exists for, and two outlined
            // pills of the same weight leave the listener to work out which one is the answer.
            Button(
                onClick = onPlay,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                ),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Play")
            }
            OutlinedButton(
                onClick = onAddToPlaylist,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Playlist")
            }
        }
    }
}

/**
 * An album or an artist in a results shelf, as a card.
 *
 * The same card as a shelf entry elsewhere, with a glyph standing in until the picture arrives and
 * after it if there is none — the two are not told apart by shape, because from a distance they are
 * the same kind of thing: somewhere to go next. Only the credits underneath and the glyph say which.
 */
@Composable
internal fun EntityCard(
    entity: YtmEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MediaCard(
        artwork = rememberArtworkAt(entity.artworkUrl, px = CARD_ART_PX),
        title = entity.title,
        // Not decoration: one album title belongs to several different artists, and only this says
        // which one this is.
        subtitle = listOfNotNull(
            entity.subtitle,
            if (entity.kind == YtmEntity.Kind.ALBUM) "Album" else "Artist",
        ).joinToString("  ·  "),
        shape = RoundedCornerShape(8.dp),
        icon = if (entity.kind == YtmEntity.Kind.ALBUM) {
            Icons.Filled.Album
        } else {
            Icons.Filled.Person
        },
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * A shelf of albums or of artists, under its own heading.
 *
 * Separate from the other shelves because these answer separately: a shelf that grew from one
 * source into two would have to push its own contents down when the second landed.
 */
@Composable
internal fun EntityShelf(
    title: String,
    subtitle: String,
    entities: List<YtmEntity>,
    onOpen: (YtmEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(bottom = 10.dp)) {
        SectionHeader(title = title, subtitle = subtitle)

        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(SHELF_SPACING),
        ) {
            items(entities, key = { it.browseId }) { entity ->
                EntityCard(entity = entity, onClick = { onOpen(entity) })
            }
        }
    }
}

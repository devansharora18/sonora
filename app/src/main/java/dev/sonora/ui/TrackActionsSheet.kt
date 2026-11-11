package dev.sonora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.backend.LibraryTrack
import dev.sonora.ui.theme.accentText

/** The corner at the top of a sheet. */
private val SHEET_SHAPE = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * A row in a bottom sheet: a glyph, a label, and sometimes a value on the right.
 *
 * [value] is how an action states what it currently is rather than what it would do — "Downloading",
 * "42%" — so a row can be both the button that starts something and the place that reports on it.
 * Without it every such action needs a second row somewhere to show its progress, and the two drift
 * out of step.
 */
@Composable
internal fun ActionRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    tint: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val content = tint ?: MaterialTheme.colorScheme.onBackground

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) content else content.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(18.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) content else content.copy(alpha = 0.4f),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = content.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    }
}

/** A small heading that divides a sheet into groups. */
@Composable
internal fun SheetHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** The artwork and title at the head of a sheet, so the action is about something recognisable. */
@Composable
internal fun SheetTrackHeader(track: LibraryTrack, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(CARD_CORNER)
        Box(
            modifier = Modifier
                .size(52.dp)
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
                    modifier = Modifier.size(52.dp),
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist ?: track.album.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The grab handle, drawn here rather than left to the sheet's own. */
@Composable
private fun SheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(34.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)),
        )
    }
}

/** The shell every sheet here is drawn in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SonoraSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = SHEET_SHAPE,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onBackground,
        dragHandle = null,
        modifier = modifier,
    ) {
        SheetHandle()
        content()
    }
}

/**
 * What can be done to one track from the library.
 *
 * Ordered by how destructive and how reversible each thing is, rather than by where it came from:
 * the things a listener reaches for are at the top, and the two that remove something are at the
 * bottom, where a thumb does not land by accident.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackActionsSheet(
    track: LibraryTrack,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleLike: () -> Unit,
    onDelete: () -> Unit,
    onForget: () -> Unit,
    onFindLossless: (() -> Unit)?,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onOpenAlbum: (() -> Unit)? = null,
    onOpenArtist: (() -> Unit)? = null,
    onStartRadio: (() -> Unit)? = null,
) {
    SonoraSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Capped against the window rather than at a fixed height: a sheet with a long
                // list and no cap grows past the screen and its own actions go off the bottom with
                // nothing to scroll them back, while a fixed cap is either too short for a track
                // with an album and an artist to open, or too tall on a small screen.
                .heightIn(max = LocalConfiguration.current.screenHeightDp.times(0.72f).dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            SheetTrackHeader(track)

            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

            ActionRow(
                icon = Icons.Filled.PlaylistAdd,
                label = "Add to playlist",
                onClick = onAddToPlaylist,
            )
            ActionRow(
                icon = Icons.Filled.Favorite,
                label = "Add to Liked Songs",
                tint = MaterialTheme.colorScheme.accentText,
                onClick = onToggleLike,
            )
            onFindLossless?.let { find ->
                ActionRow(
                    icon = Icons.Filled.CloudDownload,
                    // Named for what it fetches and where from, because a row called "Download" on
                    // a streaming result is ambiguous: a stream is already playing, and what this
                    // does is go and find the file. The value says which network it will ask, so
                    // the listener is not left wondering why YouTube is in the list.
                    label = "Download a FLAC from Soulseek",
                    onClick = find,
                )
            }
            onPlayNext?.let { next ->
                ActionRow(
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    label = "Play next",
                    onClick = next,
                )
            }
            onAddToQueue?.let { enqueue ->
                ActionRow(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    label = "Add to queue",
                    onClick = enqueue,
                )
            }
            onOpenAlbum?.let { open ->
                ActionRow(
                    icon = Icons.Filled.Album,
                    label = "Open album",
                    onClick = open,
                )
            }
            onOpenArtist?.let { open ->
                ActionRow(
                    icon = Icons.Filled.Person,
                    label = "Open artist",
                    onClick = open,
                )
            }
            onStartRadio?.let { radio ->
                ActionRow(
                    icon = Icons.Rounded.Radio,
                    label = "Start a radio",
                    onClick = radio,
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline,
            )

            if (track.remote != null) {
                ActionRow(
                    icon = Icons.Filled.Close,
                    label = "Remove from library",
                    onClick = onForget,
                )
            }
            if (canDelete) {
                ActionRow(
                    icon = Icons.Filled.Delete,
                    label = "Delete download",
                    // Error red, and not the accent: this is the one row in the sheet that takes
                    // something away, and it has to stay legible as the thing it is whatever else
                    // the rest of the sheet looks like.
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        }
    }
}

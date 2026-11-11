package dev.sonora.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.backend.LibraryGrouping
import dev.sonora.backend.SearchQueries
import dev.sonora.ytm.YtmEntity

/**
 * One artist: their picture, their releases, and their songs.
 *
 * The layout preserves the single unified scrolling list while theming the background,
 * typography, and controls with Sonora's ambient acrylic backdrop aesthetic.
 */
@Composable
fun ArtistDetailScreen(
    artist: LibraryGrouping.Artist,
    /** The artist's own albums and singles, once the page has answered. Empty until it has. */
    remoteAlbums: List<YtmEntity> = emptyList(),
    remoteSingles: List<YtmEntity> = emptyList(),
    /** The artist's own picture, which is theirs rather than any one record's. */
    artworkUrl: String? = null,
    playing: Boolean = false,
    isLoading: Boolean = false,
    onBack: () -> Unit,
    onPlayFrom: (Int) -> Unit,
    onFindMore: (String) -> Unit,
) {
    val releaseCount = remoteAlbums.size + remoteSingles.size
    val albumCount = if (releaseCount > 0) {
        releaseCount
    } else {
        LibraryGrouping.albums(artist.tracks).size
    }
    val releases = remoteAlbums + remoteSingles

    Box(modifier = Modifier.fillMaxSize()) {
        ArtworkBackdrop(
            artworkUrl = artworkUrl,
            fallbackTrack = artist.tracks.firstOrNull(),
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(
                bottom = listBottomPadding(playing) + 16.dp,
            ),
        ) {
            item(key = "back") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White.copy(alpha = 0.90f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            item(key = "header") {
                ArtistHeader(
                    artist = artist,
                    albumCount = albumCount,
                    artworkUrl = artworkUrl,
                    isLoading = isLoading,
                    hasReleases = releases.isNotEmpty(),
                    onPlayFrom = onPlayFrom,
                    onFindMore = onFindMore,
                )
            }

            if (isLoading) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.5.dp,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                }
            }

            if (releases.isNotEmpty()) {
                item(key = "releases") {
                    Column(modifier = Modifier.padding(bottom = 12.dp, top = 8.dp)) {
                        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                            Text(
                                text = if (remoteSingles.isEmpty()) "Albums" else "Albums and singles",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "From YouTube Music; tap to look for it",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.60f),
                            )
                        }

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                            horizontalArrangement = Arrangement.spacedBy(SHELF_SPACING),
                        ) {
                            items(releases, key = { it.browseId }) { release ->
                                EntityCard(
                                    entity = release,
                                    onClick = {
                                        onFindMore(SearchQueries.forAlbum(release.title, artist.name))
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (artist.tracks.isNotEmpty()) {
                item(key = "songs-heading") {
                    Text(
                        text = "Songs",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
                    )
                }
            }

            itemsIndexed(artist.tracks, key = { _, track -> track.key }) { index, track ->
                TrackListRow(
                    track = track,
                    // Artist after album, which is the order every other list in the app uses.
                    meta = listOfNotNull(track.artist, track.album).joinToString("  ·  "),
                    onClick = { onPlayFrom(index) },
                )
            }
        }
    }
}

/** The picture, the name, the counts, and the two things worth doing on arrival. */
@Composable
private fun ArtistHeader(
    artist: LibraryGrouping.Artist,
    albumCount: Int,
    artworkUrl: String?,
    isLoading: Boolean,
    hasReleases: Boolean,
    onPlayFrom: (Int) -> Unit,
    onFindMore: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.20f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                val artwork = rememberArtworkAt(artworkUrl, px = CARD_ART_PX)
                    ?: artist.tracks.firstOrNull()?.let { rememberTrackArtwork(it, px = CARD_ART_PX) }
                if (artwork != null) {
                    Image(
                        bitmap = artwork,
                        contentDescription = "Artist artwork",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.50f),
                        modifier = Modifier.size(44.dp),
                    )
                }
            }

            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                if (!isLoading && (albumCount > 0 || artist.tracks.isNotEmpty())) {
                    Text(
                        text = listOf(
                            if (albumCount == 1) "1 album" else "$albumCount albums",
                            if (artist.tracks.size == 1) "1 track" else "${artist.tracks.size} tracks",
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.60f),
                    )
                }
            }
        }

        if (!isLoading) {
            Row(
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (artist.tracks.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable { onPlayFrom(0) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play artist",
                            tint = Color.Black,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }

                if (!hasReleases) {
                    Spacer(Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.20f), CircleShape)
                            .clickable { onFindMore(SearchQueries.forArtist(artist.name)) }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.90f),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Find more",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                                color = Color.White.copy(alpha = 0.90f),
                            )
                        }
                    }
                }
            }
        }
    }
}

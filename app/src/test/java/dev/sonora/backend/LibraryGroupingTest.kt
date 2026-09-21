package dev.sonora.backend

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryGroupingTest {

    private fun track(
        name: String,
        title: String = name,
        artist: String? = null,
        album: String? = null,
    ) = LibraryTrack(
        file = File("/music/$name"),
        title = title,
        artist = artist,
        album = album,
        size = 1_000L,
    )

    @Test
    fun `tracks sharing an album become one album in filename order`() {
        val tracks = listOf(
            track("b.flac", album = "Kid A", artist = "Radiohead"),
            track("a.flac", album = "Kid A", artist = "Radiohead"),
        )

        val album = LibraryGrouping.albums(tracks).single()

        assertEquals("Kid A", album.name)
        assertEquals("Radiohead", album.artist)
        assertEquals(listOf("a.flac", "b.flac"), album.tracks.map { it.file.name })
    }

    @Test
    fun `a missing album tag gets its own bucket rather than being dropped`() {
        val albums = LibraryGrouping.albums(listOf(track("loose.flac", artist = "Someone")))

        assertEquals(LibraryGrouping.UNKNOWN_ALBUM, albums.single().name)
    }

    @Test
    fun `a blank album tag is treated as missing`() {
        val albums = LibraryGrouping.albums(listOf(track("loose.flac", album = "   ")))

        assertEquals(LibraryGrouping.UNKNOWN_ALBUM, albums.single().name)
    }

    @Test
    fun `an album with differing artists is reported as various artists`() {
        val tracks = listOf(
            track("one.flac", album = "Now 64", artist = "Billie Eilish"),
            track("two.flac", album = "Now 64", artist = "Someone Else"),
        )

        assertEquals(LibraryGrouping.VARIOUS_ARTISTS, LibraryGrouping.albums(tracks).single().artist)
    }

    @Test
    fun `albums are sorted by name and the artist grouping agrees`() {
        val tracks = listOf(
            track("a.flac", album = "Zebra", artist = "Zed"),
            track("b.flac", album = "Apple", artist = "Anna"),
        )

        assertEquals(listOf("Apple", "Zebra"), LibraryGrouping.albums(tracks).map { it.name })
        assertEquals(listOf("Anna", "Zed"), LibraryGrouping.artists(tracks).map { it.name })
    }

    @Test
    fun `an album with no artist is not reported as various artists`() {
        val tracks = listOf(
            track("one.flac", album = "Demos"),
            track("two.flac", album = "Demos"),
        )

        assertEquals(LibraryGrouping.UNKNOWN_ARTIST, LibraryGrouping.albums(tracks).single().artist)
    }

    @Test
    fun `a missing artist tag gets its own bucket`() {
        val artists = LibraryGrouping.artists(listOf(track("loose.flac", album = "Demos")))

        assertEquals(LibraryGrouping.UNKNOWN_ARTIST, artists.single().name)
    }

    @Test
    fun `artist tracks are ordered by title`() {
        val tracks = listOf(
            track("b.flac", title = "Zebra", artist = "Anna"),
            track("a.flac", title = "Apple", artist = "Anna"),
        )

        assertEquals(
            listOf("Apple", "Zebra"),
            LibraryGrouping.artists(tracks).single().tracks.map { it.title },
        )
    }

    @Test
    fun `an empty library groups to nothing`() {
        assertEquals(emptyList<LibraryGrouping.Album>(), LibraryGrouping.albums(emptyList()))
        assertEquals(emptyList<LibraryGrouping.Artist>(), LibraryGrouping.artists(emptyList()))
    }
}

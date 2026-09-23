package dev.sonora.metadata

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverArtCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val image = byteArrayOf(1, 2, 3, 4)

    @Test
    fun `an image is fetched once and then served from disk`() {
        var fetches = 0
        val cache = cacheFor { fetches++; image }

        assertArrayEquals(image, cache.load("release-1"))
        assertArrayEquals(image, cache.load("release-1"))

        assertEquals(1, fetches)
    }

    @Test
    fun `a release with no cover is not asked about again`() {
        var fetches = 0
        val cache = cacheFor { fetches++; null }

        assertNull(cache.load("release-1"))
        assertNull(cache.load("release-1"))

        assertEquals("a 404 is remembered too", 1, fetches)
    }

    @Test
    fun `different releases are cached separately`() {
        val cache = cacheFor { url -> if (url.contains("release-1")) image else byteArrayOf(9) }

        assertArrayEquals(image, cache.load("release-1"))
        assertArrayEquals(byteArrayOf(9), cache.load("release-2"))
    }

    @Test
    fun `a release with no identifier is not looked up`() {
        var fetches = 0
        val cache = cacheFor { fetches++; image }

        assertNull(cache.load(""))

        assertEquals(0, fetches)
    }

    @Test
    fun `an image past its life is fetched again`() {
        var now = 1_000L
        var fetches = 0
        val cache = CoverArtCache(folder.newFolder(), { fetches++; image }, now = { now })

        cache.load("release-1")

        now += 31L * 24 * 60 * 60 * 1000
        cache.load("release-1")

        assertEquals(2, fetches)
    }

    @Test
    fun `the url points at the front cover of that release`() {
        assertEquals(
            "https://coverartarchive.org/release-group/abc-123/front-250",
            CoverArt.url("abc-123"),
        )
    }

    private fun cacheFor(fetch: (String) -> ByteArray?): CoverArtCache =
        CoverArtCache(folder.newFolder(), fetch)
}

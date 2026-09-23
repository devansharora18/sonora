package dev.sonora.backend

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The atomic write, which every store in the app depends on. */
class JsonFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `concurrent writes all succeed`() {
        val json = JsonFile(folder.newFile("values.json"), ListSerializer(String.serializer()))

        val writers = 16
        val each = 50
        val start = CountDownLatch(1)
        val finished = CountDownLatch(writers)
        val failures = AtomicInteger()
        val firstFailure = AtomicReference<Throwable?>()
        val pool = Executors.newFixedThreadPool(writers)

        repeat(writers) { writer ->
            pool.execute {
                start.await()
                repeat(each) { value ->
                    runCatching { json.write(listOf("writer $writer", "value $value")) }
                        .onFailure {
                            failures.incrementAndGet()
                            firstFailure.compareAndSet(null, it)
                        }
                }
                finished.countDown()
            }
        }

        start.countDown()
        finished.await(30, TimeUnit.SECONDS)
        pool.shutdownNow()

        // The temporary file used to be shared, so one writer moved the file another had already
        // renamed away. In the app that reached the user as a crash while playing a track.
        assertEquals("no write should fail — first was ${firstFailure.get()}", 0, failures.get())

        // And what is left is a whole document rather than a half-written one.
        assertEquals(2, json.read()?.size)
    }

    @Test
    fun `a write leaves only the document behind`() {
        val directory = folder.newFolder()
        val json = JsonFile(File(directory, "values.json"), ListSerializer(String.serializer()))

        json.write(listOf("one"))

        assertEquals(listOf("values.json"), directory.listFiles()?.map { it.name })
    }

    @Test
    fun `a stored value comes back`() {
        val json = JsonFile(folder.newFile("values.json"), ListSerializer(String.serializer()))

        json.write(listOf("one", "two"))

        assertEquals(listOf("one", "two"), json.read())
    }
}

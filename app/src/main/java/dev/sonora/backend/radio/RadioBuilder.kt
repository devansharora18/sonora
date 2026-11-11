package dev.sonora.backend.radio

import dev.sonora.backend.taste.RankProfile
import dev.sonora.backend.taste.TasteEngine
import dev.sonora.backend.taste.TrackRef

/**
 * Turns a set of picked artists into one of the three mixes.
 *
 * The whole feature is a pool problem: [TasteEngine.rank] already knows how to order candidates for
 * this listener, so a mix is "what goes into the pool" plus "which weights order it". Each mode
 * answers those two questions differently and nothing else is mode-specific.
 *
 * Both network sources are injected. That is what lets the pools be tested on the JVM — a fake
 * radio proves the Discover filter without a device, a token, or a peer network.
 */
class RadioBuilder(
    private val engine: TasteEngine,
    /** The radio for one picked artist: music by and around it. */
    private val seedRadio: suspend (RadioSeed) -> List<TrackRef>,
    /** The radio for one track, used for Discover's second hop. */
    private val relatedTo: suspend (TrackRef) -> List<TrackRef>,
) {

    /**
     * A mix of up to [count] tracks, or an empty list when there is nothing to build from.
     *
     * @param library everything the app knows about, as refs — a downloaded album by a picked
     *   artist belongs in a blended or familiar mix even if it has never been played.
     * @param cur the track playing now, so the mix does not open with it.
     */
    suspend fun build(
        setup: RadioSetup,
        mode: RadioMode,
        library: List<TrackRef>,
        cur: TrackRef? = null,
        count: Int = MIX_SIZE,
    ): List<TrackRef> {
        if (!setup.isReady || count <= 0) return emptyList()

        val seedKeys = setup.seeds.mapTo(HashSet()) { it.key }
        val fromPickedArtists = library.filter { it.artistKey in seedKeys }

        return when (mode) {
            RadioMode.BLENDED -> blended(setup, fromPickedArtists, cur, count)
            RadioMode.FAMILIAR -> familiar(fromPickedArtists, cur, count)
            RadioMode.DISCOVER -> discover(setup, library, seedKeys, cur, count)
        }
    }

    /**
     * The picked artists, their neighbours, and everything the model already likes.
     *
     * Unfiltered on purpose: this is the mix that is supposed to feel like the listener's own
     * library with new things in it, so nothing is excluded — the scorer decides the balance.
     */
    private suspend fun blended(
        setup: RadioSetup,
        fromPickedArtists: List<TrackRef>,
        cur: TrackRef?,
        count: Int,
    ): List<TrackRef> {
        val pool = LinkedHashMap<String, TrackRef>()

        engine.candidates(cur).forEach { pool[it.key] = it }
        fromPickedArtists.forEach { pool[it.key] = it }

        for (seed in setup.seeds) {
            for (ref in radioOf(seed)) if (pool[ref.key] == null) pool[ref.key] = ref
        }

        return engine.rank(pool.values.toList(), cur, count, RankProfile.DEFAULT)
    }

    /**
     * Only what this listener has already been through.
     *
     * No radio at all: a mix that says "familiar" and then plays something unheard would be a lie,
     * and the pool filter is the only thing that can promise it.
     */
    private suspend fun familiar(
        fromPickedArtists: List<TrackRef>,
        cur: TrackRef?,
        count: Int,
    ): List<TrackRef> {
        val model = engine.snapshot()
        val pool = LinkedHashMap<String, TrackRef>()

        engine.candidates(cur).forEach { pool[it.key] = it }
        // A downloaded album is the listener's own music whether or not the model has seen a play
        // of it, so a file is evidence enough to count as familiar.
        fromPickedArtists.forEach { pool[it.key] = it }

        val known = pool.values.filter { ref ->
            val stats = model.tracks[ref.key]?.stats
            (stats?.plays ?: 0) > 0 || stats?.liked == true || ref.localPath != null
        }

        return engine.rank(known, cur, count, RankProfile.FAMILIAR)
    }

    /**
     * Music near the picks that this listener has not played.
     *
     * A second hop only when the first did not fill the pool: every extra hop is another request
     * before the first track plays, and a single seed's radio is usually enough on its own.
     */
    private suspend fun discover(
        setup: RadioSetup,
        library: List<TrackRef>,
        seedKeys: Set<String>,
        cur: TrackRef?,
        count: Int,
    ): List<TrackRef> {
        val pool = LinkedHashMap<String, TrackRef>()
        val firstHop = setup.seeds.flatMap { radioOf(it) }
        firstHop.forEach { pool[it.key] = it }

        if (pool.size < count) {
            firstHop.distinctBy { it.key }.take(SECOND_HOP_SEEDS).forEach { ref ->
                for (next in runCatching { relatedTo(ref) }.getOrDefault(emptyList())) {
                    if (pool[next.key] == null) pool[next.key] = next
                }
            }
        }

        val model = engine.snapshot()
        val libraryKeys = library.mapTo(HashSet()) { it.key }
        val unheard = pool.values.filter { ref ->
            ref.key !in libraryKeys && (model.tracks[ref.key]?.stats?.plays ?: 0) == 0
        }

        // Ordered as a whole rather than straight to `count`, because the artist cap below drops
        // entries and would otherwise leave the mix short: a radio seeded by one artist returns
        // mostly that artist, which is exactly what Discover has to cut back.
        val ordered = unheard.take(MAX_DISCOVER_POOL)
        val ranked = engine.rank(ordered, cur, ordered.size, RankProfile.DISCOVER)
        return cappedToSelectedArtists(ranked, seedKeys, count)
    }

    /**
     * Keeps at most a third of the mix by the picked artists.
     *
     * A picked artist may appear — that is what "around" means — but a Discover mix that turns out
     * to be the picked artist's discography is just Blended with a different name.
     */
    private fun cappedToSelectedArtists(
        ranked: List<TrackRef>,
        seedKeys: Set<String>,
        count: Int,
    ): List<TrackRef> {
        val cap = (count / DISCOVER_SELECTED_FRACTION).coerceAtLeast(1)
        var selected = 0
        val out = ArrayList<TrackRef>(count)

        for (ref in ranked) {
            if (ref.artistKey in seedKeys) {
                if (selected >= cap) continue
                selected++
            }
            out += ref
            if (out.size == count) break
        }

        return out
    }

    /** One seed's radio, degrading to nothing rather than failing the whole mix. */
    private suspend fun radioOf(seed: RadioSeed): List<TrackRef> =
        runCatching { seedRadio(seed) }.getOrDefault(emptyList())

    companion object {

        /** How many tracks one mix queues up front. */
        const val MIX_SIZE = 40

        /** How many first-hop tracks Discover is allowed to walk one step further from. */
        const val SECOND_HOP_SEEDS = 3

        /** The share of a Discover mix that may be the picked artists, as a divisor. */
        const val DISCOVER_SELECTED_FRACTION = 3

        /** The most candidates a Discover mix will order. Bounds the O(n²) sampling. */
        const val MAX_DISCOVER_POOL = 200
    }
}

package dev.sonora.backend.radio

import dev.sonora.backend.taste.RankProfile
import dev.sonora.backend.taste.TasteEngine
import dev.sonora.backend.taste.TrackRef
import kotlin.math.roundToInt

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
    /** The radio for one track, used for the second hop when the pools are thin. */
    private val relatedTo: suspend (TrackRef) -> List<TrackRef>,
    /**
     * The picked artist's own tracks.
     *
     * A radio seeded by that artist answers with the neighbourhood — mostly other people — so it
     * cannot supply the share the slider asks for by itself. This is the artist's catalogue.
     */
    private val seedCatalogue: suspend (RadioSeed) -> List<TrackRef> = { emptyList() },
) {

    /**
     * A mix of up to [count] tracks, or an empty list when there is nothing to build from.
     *
     * The whole feature is one proportion: how much of the queue is the picked artists and how much
     * is the artists around them. [RadioSetup.variety] sets that proportion directly, so there is
     * no mode to choose between — 0 is only the picks, 1 is almost all neighbours, and every value
     * between is the mix the listener asked for.
     *
     * @param library everything the app knows about, as refs — a downloaded album by a picked
     *   artist belongs in the mix even if it has never been played.
     * @param cur the track playing now, so the mix does not open with it.
     */
    suspend fun build(
        setup: RadioSetup,
        library: List<TrackRef>,
        cur: TrackRef? = null,
        count: Int = MIX_SIZE,
        /** Tracks already queued or played by this radio, which a refill must not hand back. */
        exclude: Set<String> = emptySet(),
    ): List<TrackRef> {
        if (!setup.isReady || count <= 0) return emptyList()

        val seedKeys = setup.seeds.mapTo(HashSet()) { it.key }
        val model = engine.snapshot()

        val radio = setup.seeds.flatMap { radioOf(it) }
        val candidates = engine.candidates(cur)

        val fromSeeds = LinkedHashMap<String, TrackRef>()
        val fromOthers = LinkedHashMap<String, TrackRef>()

        fun offer(ref: TrackRef, into: LinkedHashMap<String, TrackRef>) {
            if (ref.key in exclude) return
            if (into[ref.key] == null) into[ref.key] = ref
        }

        for (ref in radio) offer(ref, if (ref.artistKey in seedKeys) fromSeeds else fromOthers)
        for (ref in candidates) offer(ref, if (ref.artistKey in seedKeys) fromSeeds else fromOthers)
        for (ref in library) offer(ref, if (ref.artistKey in seedKeys) fromSeeds else fromOthers)

        // Last and most important: what the picked artists actually made. Without it a 100:0 mix
        // is whatever handful of their tracks the radios happened to include.
        for (seed in setup.seeds) {
            for (ref in runCatching { seedCatalogue(seed) }.getOrDefault(emptyList())) {
                offer(ref, fromSeeds)
                // A row on the artist's page can be credited to a collaborator first, so the very
                // same track may already have arrived through the radio or the library and been
                // classed as an outsider. It belongs to the picks half here, and it must not stay
                // in the other one as well — blend draws from both lists and would queue it twice.
                fromOthers.remove(ref.key)
            }
        }

        // One step out, only when the picks' own radios did not bring enough back with them: every
        // hop is another request before the first track plays.
        if (fromSeeds.size < count * setup.selectedShare && radio.isNotEmpty()) {
            for (seedTrack in radio.filter { it.artistKey in seedKeys }.take(SECOND_HOP_SEEDS)) {
                for (next in runCatching { relatedTo(seedTrack) }.getOrDefault(emptyList())) {
                    offer(next, if (next.artistKey in seedKeys) fromSeeds else fromOthers)
                }
            }
        }

        val selected = if (setup.familiarOnly) fromSeeds.values.filter { isKnown(it, model) } else fromSeeds.values.toList()
        val others = if (setup.familiarOnly) fromOthers.values.filter { isKnown(it, model) } else fromOthers.values.toList()

        if (selected.isEmpty() && others.isEmpty()) return emptyList()

        // Both halves are ordered before they are blended, so the mix is "the best of these
        // artists, interleaved with the best of the rest" rather than the two races in one list.
        // The picks half ignores the recently-played window. That window exists to stop Autoplay
        // repeating itself, but a radio is built from the artist the listener *chose*, and the
        // tracks they have heard by that artist are exactly the ones the slider is asking for.
        // Left on, it starved the picks down to the handful never played while the neighbours —
        // all new, none excluded — took the rest of the queue: an 80:20 mix that came out 20:80.
        // Repeats across refills are handled by the exclusion set instead.
        val selectedProfile = (if (setup.familiarOnly) RankProfile.FAMILIAR else RankProfile.DEFAULT)
            .copy(repeatWindow = 0)
        val otherProfile = if (setup.familiarOnly) RankProfile.FAMILIAR else RankProfile.DISCOVER

        return blend(
            selected = engine.rank(selected, cur, selected.size, selectedProfile),
            others = engine.rank(others, cur, others.size, otherProfile),
            selectedShare = setup.selectedShare,
            count = count,
        )
    }

    /**
     * Interleaves the two halves at the share the slider asks for.
     *
     * Each half is first cut to its own quota, so a stop is a promise about the *whole* queue and
     * not a starting point: 100:0 is all picks, and a mix that runs out of them is shorter rather
     * than quietly filled with other artists. The one direction top-up is allowed is towards the
     * picks — when the neighbourhood cannot fill its 20%, more of what the listener asked for is
     * not a broken promise.
     *
     * The interleave itself is target-driven: at each slot it asks how many picked-artist tracks
     * the mix should hold by now and takes one if it is behind, which spreads the difference
     * evenly instead of leaving one kind in a run at the end.
     */
    private fun blend(
        selected: List<TrackRef>,
        others: List<TrackRef>,
        selectedShare: Float,
        count: Int,
    ): List<TrackRef> {
        val share = selectedShare.coerceIn(0f, 1f)
        val wantedSelected = (count * share).roundToInt()
        val pickedSelected = selected.take(wantedSelected)

        // The other half is capped by what the picks half actually supplied, not by the stop alone.
        // Cutting both halves to their own quotas independently is what let an 80:20 refill come
        // out at 3 picks to 8 strangers: the picks had run down, their quota went unfilled, and the
        // others still took their full share of the slot count. A short mix is honest; a mix that
        // says 80:20 and is 27:73 is not.
        val othersCap = if (share <= 0f) {
            count
        } else {
            (pickedSelected.size * (1f - share) / share).roundToInt()
        }
        val wantedOthers = (count - pickedSelected.size).coerceAtMost(othersCap)
        val pickedOthers = others.take(wantedOthers)

        // The neighbourhood ran out first: the picks may take the rest of the queue.
        val picked = pickedSelected + selected.drop(pickedSelected.size)
            .take((count - pickedSelected.size - pickedOthers.size).coerceAtLeast(0))

        val out = ArrayList<TrackRef>(count)
        var fromSelected = 0
        var fromOthers = 0

        for (slot in 0 until count) {
            val wanted = ((slot + 1) * share).roundToInt()

            val takeSelected = when {
                fromSelected < picked.size && fromOthers < pickedOthers.size -> fromSelected < wanted
                fromSelected < picked.size -> true
                fromOthers < pickedOthers.size -> false
                else -> break
            }

            out += if (takeSelected) picked[fromSelected++] else pickedOthers[fromOthers++]
        }

        return out
    }

    /** Whether the model has evidence this listener has been through the track before. */
    private fun isKnown(ref: TrackRef, model: dev.sonora.backend.taste.TasteModel): Boolean {
        val stats = model.tracks[ref.key]?.stats
        return (stats?.plays ?: 0) > 0 || stats?.liked == true || ref.localPath != null
    }

    /** One seed's radio, degrading to nothing rather than failing the whole mix. */
    private suspend fun radioOf(seed: RadioSeed): List<TrackRef> =
        runCatching { seedRadio(seed) }.getOrDefault(emptyList())

    companion object {

        /** How many tracks one mix queues up front. */
        const val MIX_SIZE = 40

        /** How many of the picks' tracks are allowed to seed one more hop. */
        const val SECOND_HOP_SEEDS = 3
    }
}

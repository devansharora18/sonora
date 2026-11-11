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
    /** The radio for one track, used for Discover's second hop. */
    private val relatedTo: suspend (TrackRef) -> List<TrackRef>,
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
    ): List<TrackRef> {
        if (!setup.isReady || count <= 0) return emptyList()

        val seedKeys = setup.seeds.mapTo(HashSet()) { it.key }
        val model = engine.snapshot()

        val radio = setup.seeds.flatMap { radioOf(it) }
        val candidates = engine.candidates(cur)

        val fromSeeds = LinkedHashMap<String, TrackRef>()
        val fromOthers = LinkedHashMap<String, TrackRef>()

        fun offer(ref: TrackRef, into: LinkedHashMap<String, TrackRef>) {
            if (into[ref.key] == null) into[ref.key] = ref
        }

        for (ref in radio) offer(ref, if (ref.artistKey in seedKeys) fromSeeds else fromOthers)
        for (ref in candidates) offer(ref, if (ref.artistKey in seedKeys) fromSeeds else fromOthers)
        for (ref in library) offer(ref, if (ref.artistKey in seedKeys) fromSeeds else fromOthers)

        // One step out, only when the picks' own radios did not bring enough back with them: every
        // hop is another request before the first track plays.
        if (fromSeeds.size + fromOthers.size < count && radio.isNotEmpty()) {
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
        val selectedProfile = if (setup.familiarOnly) RankProfile.FAMILIAR else RankProfile.DEFAULT
        val otherProfile = if (setup.familiarOnly) RankProfile.FAMILIAR else RankProfile.DISCOVER

        return blend(
            selected = engine.rank(selected, cur, selected.size, selectedProfile),
            others = engine.rank(others, cur, others.size, otherProfile),
            variety = setup.variety,
            count = count,
        )
    }

    /**
     * Interleaves the two halves at the share [variety] asks for.
     *
     * A target rather than a fixed pattern: at each slot it asks how many picked-artist tracks the
     * mix should hold by now and takes one of those if it is behind. That gives 0 and 1 their exact
     * meanings at the ends of the slider and an even spread everywhere else, and it never leaves a
     * run of one kind because the other ran out early.
     */
    private fun blend(
        selected: List<TrackRef>,
        others: List<TrackRef>,
        variety: Float,
        count: Int,
    ): List<TrackRef> {
        val selectedShare = 1.0 - variety.coerceIn(0f, 1f)
        val out = ArrayList<TrackRef>(count)
        var fromSelected = 0
        var fromOthers = 0

        for (slot in 0 until count) {
            val wanted = ((slot + 1) * selectedShare).roundToInt()

            val takeSelected = when {
                fromSelected < selected.size && fromOthers < others.size -> fromSelected < wanted
                fromSelected < selected.size -> true
                fromOthers < others.size -> false
                else -> break
            }

            out += if (takeSelected) selected[fromSelected++] else others[fromOthers++]
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

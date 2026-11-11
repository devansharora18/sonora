package dev.sonora.backend.radio

import dev.sonora.backend.taste.TrackIdentity
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * One artist the listener picked to build a mix from.
 *
 * [browseId] is YouTube Music's artist id, which is what fetches the catalogue; [name] is what the
 * chip shows and what the taste model is keyed by. An artist picked from the taste list starts with
 * a name only and gains its id when the directory resolves it, so both are nullable for that one
 * case rather than a sentinel being invented for it.
 */
@Serializable
data class RadioSeed(
    val name: String,
    val browseId: String? = null,
    val artworkUrl: String? = null,
) {
    /** The identity used to tell two picks apart. The name, folded the same way the model folds it. */
    val key: String get() = TrackIdentity.artistKey(name)
}

/**
 * The picked artists and how far the mix should roam, as one document.
 *
 * The reach is one of five stops, each an exact share of the queue that comes from the picked
 * artists: 100:0, 80:20, 60:40, 50:50, 30:70. Five ratios rather than a continuous float because
 * these are the points a listener can actually name — "mostly mine" is a number, "0.37" is not —
 * and because a mix that hits them exactly can be explained in one line.
 */
@Serializable
data class RadioSetup(
    val seeds: List<RadioSeed> = emptyList(),
    /** Which stop of [SHARES] the slider is on. */
    val stop: Int = DEFAULT_STOP,
    /** Only music already played, liked or downloaded, whatever the reach. */
    val familiarOnly: Boolean = false,
) {

    /** The share of the mix that must be the picked artists, whichever stop is selected. */
    val selectedShare: Float get() = SHARES[stop.coerceIn(0, SHARES.lastIndex)]

    /** The slider's position, 0…1, for a control with one step per stop. */
    val variety: Float get() = stop.coerceIn(0, SHARES.lastIndex) / (SHARES.size - 1).toFloat()

    fun withStop(stop: Int): RadioSetup = copy(stop = stop.coerceIn(0, SHARES.lastIndex))

    /** The stop nearest a slider position, so the control snaps to the five ratios. */
    fun stopFor(variety: Float): Int =
        (variety.coerceIn(0f, 1f) * (SHARES.size - 1)).roundToInt().coerceIn(0, SHARES.lastIndex)

    val isReady: Boolean get() = seeds.isNotEmpty()

    /** Adds a seed, ignoring one already picked or past the cap. */
    fun withSeed(seed: RadioSeed): RadioSetup {
        if (seed.name.isBlank()) return this
        if (seeds.size >= MAX_SEEDS) return this
        if (seeds.any { it.key == seed.key }) return this
        return copy(seeds = seeds + seed)
    }

    /** Removes the seed with this name, matching the way [RadioSeed.key] does. */
    fun withoutSeed(name: String): RadioSetup {
        val key = TrackIdentity.artistKey(name)
        return copy(seeds = seeds.filterNot { it.key == key })
    }

    /**
     * The same selection with [seed] resolved — its real id and artwork.
     *
     * A pick added from the taste list is a name only. When the directory answers, the stored seed
     * is upgraded in place so the next open costs no lookup.
     */
    fun withResolved(seed: RadioSeed): RadioSetup = copy(
        seeds = seeds.map { stored -> if (stored.key == seed.key) seed else stored },
    )

    companion object {
        /** The most artists one mix can be built from. */
        const val MAX_SEEDS = 5

        /**
         * The five stops, as the picked artists' share of the queue.
         *
         * Not evenly spaced, and the last one still keeps 30% for the picks: the far end of the
         * slider is "mostly new", not "nothing you asked for".
         */
        val SHARES = floatArrayOf(1.0f, 0.8f, 0.6f, 0.5f, 0.3f)

        /** Where the slider starts: mostly the picks, a fifth from around them. */
        const val DEFAULT_STOP = 1
    }
}

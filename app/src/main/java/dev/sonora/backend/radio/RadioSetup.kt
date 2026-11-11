package dev.sonora.backend.radio

import dev.sonora.backend.taste.TrackIdentity
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
 * [variety] is the whole control, expressed as one number: at 0 the mix is only the artists that
 * were picked, and at 1 it is almost entirely artists around them. Everything between is a
 * proportion, not a mode — which is what makes it a slider instead of three buttons that each
 * pretend to be the same kind of thing.
 */
@Serializable
data class RadioSetup(
    val seeds: List<RadioSeed> = emptyList(),
    val variety: Float = DEFAULT_VARIETY,
    /** Only music already played, liked or downloaded, whatever the variety. */
    val familiarOnly: Boolean = false,
) {

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

        /** Where the slider starts: mostly the picks, with a few neighbours. */
        const val DEFAULT_VARIETY = 0.3f
    }
}

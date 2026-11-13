package dev.sonora.backend.taste

/**
 * The weights one caller gives [TasteEngine.rank].
 *
 * Autoplay and the three radio mixes score the same candidates with the same formula; what changes
 * is how much each term is worth. Keeping that as data rather than as a branch inside the scorer is
 * what stops "Discover" and "Familiar only" from turning into two copies of the scoring code that
 * disagree the first time one of them is tuned.
 *
 * The defaults are Autoplay's own weights, so a caller that does not care behaves exactly as before.
 */
data class RankProfile(
    /** How much the listener's affinity for the track's artist is worth. */
    val artistWeight: Double = TasteEngine.ARTIST_WEIGHT,
    /** How much finishing this track before is worth. */
    val familiarityWeight: Double = TasteEngine.FAMILIARITY_WEIGHT,
    /** Added to a candidate that has never been played. */
    val noveltyBonus: Double = TasteEngine.NOVELTY_BONUS,
    /** Multiplied by a track's own skip rate, once it has been played twice. */
    val skipPenalty: Double = TasteEngine.SKIP_PENALTY,
    /**
     * How many recently played tracks to keep out of the result.
     *
     * Autoplay's answer to "do not repeat yourself". A familiar mix wants the opposite: the tracks
     * just played are the most familiar ones there are, so it sets this to zero and only the track
     * playing now is excluded.
     */
    val repeatWindow: Int = TasteEngine.RECENT_WINDOW,
) {

    companion object {

        /** Autoplay and the blended mix. */
        val DEFAULT = RankProfile()

        /**
         * Familiar only: finishing a track is the point, so the term doubles and a never-played
         * track gets no bonus at all. The pool already excludes the unfamiliar; this keeps the
         * *order* inside it honest too.
         */
        val FAMILIAR = RankProfile(
            familiarityWeight = TasteEngine.FAMILIARITY_WEIGHT * 2.0,
            noveltyBonus = 0.0,
            repeatWindow = 0,
        )

        /**
         * Discover: nothing for having heard it before, a bonus for having not, and a weaker skip
         * penalty — a track skipped once in another mood should not keep a new artist out.
         */
        val DISCOVER = RankProfile(
            familiarityWeight = 0.0,
            noveltyBonus = 0.3,
            skipPenalty = 0.4,
        )
    }
}

package dev.sonora.backend.taste

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Who put a track on. An autoplay pick teaches the model less than a deliberate one. */
enum class Origin { USER, AUTOPLAY }

/**
 * Learns a listener's taste from what they play, on device.
 *
 * Everything is derived from signals the player already produces — a transition, a played ratio, a
 * pause — so there is no tracking beyond what the app needs to play music. The model outlives the
 * process (see [TasteStore]) and is scored by [next] to refill the queue.
 *
 * The engine is pure Kotlin: no Android types, an injected clock and [Random], which is what lets
 * every rule and the whole scoring loop be tested on the JVM.
 */
class TasteEngine(
    private val store: TasteStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {

    @Volatile
    private var model: TasteModel = store.load()

    /** One lock for every mutation, so a save never observes a half-written model. */
    private val mutex = Mutex()

    /** The model as it stands. For tests and the Taste screen; not a live view. */
    fun snapshot(): TasteModel = model

    /** The decayed weight of `from -> to`, or 0. For tests and diagnostics. */
    fun edgeWeight(from: String, to: String, now: Long = clock()): Double =
        model.edges[from]?.get(to)?.at(now, HALF_LIFE_EDGE) ?: 0.0

    /**
     * Records one play and folds it into the model.
     *
     * @param listenedMs real played time, not wall-clock — the caller banks it across pauses.
     * @param durationMs the track's length, or null when it is not known.
     * @param liked whether the track is liked right now.
     */
    suspend fun recordPlay(
        ref: TrackRef,
        listenedMs: Long,
        durationMs: Long?,
        origin: Origin = Origin.USER,
        liked: Boolean = false,
        now: Long = clock(),
    ) {
        val ratio = durationMs
            ?.takeIf { it > 0L }
            ?.let { (listenedMs.toDouble() / it.toDouble()).coerceIn(0.0, 1.0) }
        val signal = PlaySignal.signal(ratio, listenedMs, liked)
        val completed = ratio != null && ratio >= PlaySignal.COMPLETE_RATIO

        // An autoplay pick scoring a hit is the engine rewarding itself; a positive one counts
        // half, so the queue drifts outward instead of collapsing onto its own last few picks.
        val weighted = if (origin == Origin.AUTOPLAY && signal > 0.0) {
            signal * AUTOPLAY_POSITIVE_WEIGHT
        } else {
            signal
        }

        mutex.withLock {
            model = apply(
                model = model,
                ref = ref,
                signal = signal,
                weighted = weighted,
                completed = completed,
                liked = liked,
                now = now,
            )
            store.scheduleSave(model)
        }
    }

    /** Sets the like flag without recording a play, so a like teaches the artist and the tags too. */
    suspend fun setLiked(key: String, liked: Boolean, now: Long = clock()) {
        mutex.withLock {
            val entry = model.tracks[key] ?: return@withLock
            if (entry.stats.liked == liked) return@withLock

            val delta = if (liked) 0.5 else -0.5
            val stats = entry.stats.copy(liked = liked, weight = entry.stats.weight.add(delta, now, HALF_LIFE_TRACK))
            var updated = model.copy(
                tracks = model.tracks + (key to entry.copy(stats = stats)),
                updatedAt = now,
            )
            updated = updated.copy(
                artists = bump(updated.artists, entry.ref.artistKey, delta, now, HALF_LIFE_ARTIST),
            )
            if (entry.ref.tags.isNotEmpty()) {
                val each = delta / entry.ref.tags.size
                var tags = updated.tags
                for (tag in entry.ref.tags.distinct()) tags = bump(tags, tag, each, now, HALF_LIFE_TAG)
                updated = updated.copy(tags = tags)
            }
            model = updated
            store.scheduleSave(updated)
        }
    }

    /** Wipes everything learned. */
    suspend fun reset(now: Long = clock()) {
        mutex.withLock {
            val empty = TasteModel(updatedAt = now)
            model = empty
            store.writeNow(empty)
        }
    }

    /** Replaces the model with an imported one and writes it. */
    suspend fun replace(imported: TasteModel) {
        mutex.withLock {
            model = imported
            store.writeNow(imported)
        }
    }

    /**
     * Picks the next [count] tracks to play after [cur].
     *
     * Three sources feed one pool: what has followed [cur] before, what the listener's favourite
     * artists have had completed, and [external] — the YouTube Music radio, which is the only source
     * that knows anything when the model is empty and the only one that can take the queue somewhere
     * new. The pool is filtered (no repeats, nothing from the recent window), scored, and sampled.
     *
     * Sampling rather than sorting is the point: a strictly best-first queue is the same ten tracks
     * in the same order every time, and taste is not that. Temperature keeps a good-but-not-top pick
     * reachable without making the choice arbitrary.
     *
     * @param external seeded with a track to find related music for; called only when the pool alone
     *   cannot fill the batch.
     */
    suspend fun next(
        cur: TrackRef?,
        count: Int,
        external: suspend (TrackRef?) -> List<TrackRef>,
    ): List<TrackRef> {
        if (count <= 0) return emptyList()

        val snapshot = model
        val now = clock()

        // Cold start: there is nothing to score, so the radio's own order is the best answer there
        // is. Scoring an empty model would just shuffle a list that is already ranked.
        if (snapshot.tracks.isEmpty()) {
            val radio = runCatching { external(cur) }.getOrDefault(emptyList())
            return radio.filterNot { it.key == cur?.key }.distinctBy { it.key }.take(count)
        }

        val pool = buildCandidates(snapshot, cur, now)

        // The radio is always consulted, not only when the pool is short: it is the exploration
        // source, and a pool that is already full of the familiar is exactly the state that needs
        // something new put in front of it.
        val radio = runCatching { external(cur) }.getOrDefault(emptyList())
        for (ref in radio) if (pool.none { it.key == ref.key }) pool += ref

        return rankFrom(pool, cur, count, RankProfile(), snapshot, now)
    }

    /**
     * Scores [pool] and samples [count] out of it, best-first under a softmax.
     *
     * The half of [next] that every mix shares: where the candidates came from is the caller's
     * business, and how they are weighed is [profile]'s. Radio builds its own pool — three seeds
     * instead of one track — and leans on the same scorer, so a mix and Autoplay cannot drift into
     * two different ideas of what this listener likes.
     */
    fun rank(
        pool: List<TrackRef>,
        cur: TrackRef?,
        count: Int,
        profile: RankProfile = RankProfile(),
    ): List<TrackRef> = rankFrom(pool, cur, count, profile, model, clock())

    /**
     * The model's own candidates for [cur]: strong transitions, the top artists' completed tracks,
     * and everything liked.
     *
     * Exposed for a caller building a wider pool than Autoplay needs. The returned list is the same
     * one [next] starts from, so a mix that adds to it also inherits Autoplay's idea of "familiar".
     */
    fun candidates(cur: TrackRef?): List<TrackRef> = buildCandidates(model, cur, clock())

    private fun rankFrom(
        pool: List<TrackRef>,
        cur: TrackRef?,
        count: Int,
        profile: RankProfile,
        snapshot: TasteModel,
        now: Long,
    ): List<TrackRef> {
        if (count <= 0 || pool.isEmpty()) return emptyList()

        val window = profile.repeatWindow.coerceAtLeast(0)
        val excluded = if (window == 0) mutableSetOf() else snapshot.recent.takeLast(window).toMutableSet()
        cur?.let { excluded += it.key }

        // The recent window is a preference, not a veto: a model smaller than the window would
        // otherwise have every candidate excluded and fall through to an unranked list, which is
        // exactly the deterministic behaviour this is meant to avoid.
        val ranked = pool.filterNot { it.key in excluded }
        val eligible = ranked.ifEmpty { pool.filterNot { it.key == cur?.key } }
        if (eligible.isEmpty()) return emptyList()

        val picked = mutableListOf<TrackRef>()
        val context = snapshot.recent.toMutableList()
        var previous = previousOf(snapshot.recent, cur?.key)
        var current = cur

        repeat(count) {
            val candidates = eligible.filterNot { ref -> picked.any { it.key == ref.key } }
            if (candidates.isEmpty()) return@repeat

            val scored = candidates.map { ref ->
                ref to score(
                    candidate = ref,
                    currentKey = current?.key,
                    previousKey = previous,
                    model = snapshot,
                    context = context,
                    now = now,
                    profile = profile,
                )
            }

            val choice = sample(scored, MODEL_TOP) ?: return@repeat
            picked += choice
            previous = current?.key
            current = choice
            context += choice.key
        }

        return picked
    }

    /** `prev -> prev-prev`, read from the recorded order rather than kept as extra state. */
    private fun previousOf(recent: List<String>, curKey: String?): String? {
        if (curKey == null) return recent.lastOrNull()
        val index = recent.indexOfLast { it == curKey }
        return if (index > 0) recent[index - 1] else null
    }

    /**
     * Everything worth considering: strong transitions out of [cur], completed tracks by the
     * strongest artists, and anything the listener has liked. Deduplicated and capped, so the score
     * is computed over a bounded set rather than the whole model.
     */
    private fun buildCandidates(model: TasteModel, cur: TrackRef?, now: Long): MutableList<TrackRef> {
        val pool = mutableListOf<TrackRef>()
        val seen = mutableSetOf<String>()

        fun add(ref: TrackRef?) {
            if (ref != null && seen.add(ref.key)) pool += ref
        }

        cur?.let { seed ->
            model.edges[seed.key].orEmpty()
                .map { (to, weight) -> to to weight.at(now, HALF_LIFE_EDGE) }
                .filter { (_, decayed) -> decayed > 0.0 }
                .sortedByDescending { (_, decayed) -> decayed }
                .take(CANDIDATE_EDGES)
                .forEach { (to, _) -> add(model.tracks[to]?.ref) }
        }

        // The listener's strongest artists, and one step of their catalogue that was actually
        // finished. A track merely played is weaker evidence than a track completed.
        model.artists.entries
            .map { (artist, affinity) -> artist to affinity.at(now, HALF_LIFE_ARTIST) }
            .filter { (_, affinity) -> affinity > 0.0 }
            .sortedByDescending { (_, affinity) -> affinity }
            .take(TOP_ARTISTS)
            .forEach { (artist, _) ->
                model.tracks.values
                    .asSequence()
                    .filter { it.ref.artistKey == artist && it.stats.completes > 0 }
                    .sortedByDescending { it.stats.weight.at(now, HALF_LIFE_TRACK) }
                    .take(ARTIST_TRACKS)
                    .forEach { add(it.ref) }
            }

        model.tracks.values
            .asSequence()
            .filter { it.stats.liked }
            .sortedByDescending { it.stats.weight.at(now, HALF_LIFE_TRACK) }
            .take(LIKED_CANDIDATES)
            .forEach { add(it.ref) }

        return pool
    }

    /**
     * One candidate's score.
     *
     * Affinities are squashed with `tanh(x/2)` so a track loved fifty times cannot drown out a
     * transition that is a direct, recent statement about what follows. Counts and rates enter
     * unscaled because they are already bounded.
     */
    private fun score(
        candidate: TrackRef,
        currentKey: String?,
        previousKey: String?,
        model: TasteModel,
        context: List<String>,
        now: Long,
        profile: RankProfile,
    ): Double {
        val edge1 = currentKey?.let { model.edges[it]?.get(candidate.key) }?.at(now, HALF_LIFE_EDGE) ?: 0.0
        val edge2 = previousKey?.let { model.edges[it]?.get(candidate.key) }?.at(now, HALF_LIFE_EDGE) ?: 0.0

        val entry = model.tracks[candidate.key]
        val stats = entry?.stats ?: TrackStats()

        val artistAffinity = tanh((model.artists[candidate.artistKey]?.at(now, HALF_LIFE_ARTIST) ?: 0.0) / SQUASH)
        val tagAffinity = candidate.tags
            .takeIf { it.isNotEmpty() }
            ?.map { model.tags[it]?.at(now, HALF_LIFE_TAG) ?: 0.0 }
            ?.average()
            ?.let { tanh(it / SQUASH) }
            ?: 0.0

        val familiarity = ln(1.0 + stats.completes.toDouble())
        val liked = if (stats.liked) LIKED_BONUS else 0.0
        val novelty = if (stats.plays == 0) profile.noveltyBonus else 0.0
        val skip = if (stats.plays >= 2) profile.skipPenalty * stats.skipRate() else 0.0
        val repeats = context.takeLast(3).count { key ->
            model.tracks[key]?.ref?.artistKey == candidate.artistKey
        }

        return edge1 +
            EDGE2_WEIGHT * edge2 +
            profile.artistWeight * artistAffinity +
            TAG_WEIGHT * tagAffinity +
            profile.familiarityWeight * familiarity +
            liked +
            novelty -
            skip -
            REPEAT_PENALTY * repeats
    }

    /**
     * Softmax over the top [limit] candidates at [SOFTMAX_TEMPERATURE], then a draw.
     *
     * Returns null only when there is nothing to pick. The top slice keeps a bad tail from being
     * sampled just because it exists; the softmax keeps the order inside the slice from being fixed.
     */
    private fun sample(scored: List<Pair<TrackRef, Double>>, limit: Int): TrackRef? {
        if (scored.isEmpty()) return null

        // Shuffled before the sort so equal scores are broken at random. Without it a cold model —
        // where every candidate scores the same — would always draw from the first few entries of
        // the pool, which for a seeded mix means the first seed's radio and nothing else.
        val top = scored.shuffled(random).sortedByDescending { it.second }.take(limit)
        if (top.size == 1) return top.first().first

        val max = top.maxOf { it.second }
        val weights = top.map { exp((it.second - max) / SOFTMAX_TEMPERATURE) }
        val total = weights.sum()
        if (total <= 0.0 || !total.isFinite()) return top.first().first

        var draw = random.nextDouble() * total
        for (index in top.indices) {
            draw -= weights[index]
            if (draw <= 0.0) return top[index].first
        }
        return top.last().first
    }

    /** Writes any queued model immediately. */
    suspend fun flush() = store.flush()

    private fun apply(
        model: TasteModel,
        ref: TrackRef,
        signal: Double,
        weighted: Double,
        completed: Boolean,
        liked: Boolean,
        now: Long,
    ): TasteModel {
        val existing = model.tracks[ref.key]
        val oldStats = existing?.stats ?: TrackStats()

        val stats = oldStats.copy(
            plays = oldStats.plays + 1,
            completes = oldStats.completes + if (completed) 1 else 0,
            skips = oldStats.skips + if (signal < 0.0) 1 else 0,
            lastPlayed = now,
            liked = liked,
            weight = oldStats.weight.add(weighted, now, HALF_LIFE_TRACK),
        )

        var updated = model.copy(
            tracks = model.tracks + (ref.key to TrackEntry(merge(existing?.ref, ref), stats)),
            artists = bump(model.artists, ref.artistKey, weighted, now, HALF_LIFE_ARTIST),
        )

        if (ref.tags.isNotEmpty()) {
            val each = weighted / ref.tags.size
            var tags = updated.tags
            for (tag in ref.tags.distinct()) tags = bump(tags, tag, each, now, HALF_LIFE_TAG)
            updated = updated.copy(tags = tags)
        }

        updated = learnEdge(updated, ref, weighted, signal, now)
        updated = updated.copy(
            recent = (updated.recent + ref.key).takeLast(TasteModel.RECENT_CAP),
            lastKey = ref.key,
            lastAt = now,
            lastPositive = signal > 0.0,
            updatedAt = now,
        )

        return enforceTrackCap(updated, now)
    }

    /**
     * Writes `previous -> current` when it is a real transition.
     *
     * Four conditions, each of which has a failure it prevents: the same track twice is not a
     * transition; a previous track that was skipped taught nothing worth following; a current track
     * with no signal (the 0.25–0.5 band) is not evidence of anything; and a gap over 30 minutes is a
     * different session, so this morning's track should not claim to precede tonight's.
     */
    private fun learnEdge(
        model: TasteModel,
        ref: TrackRef,
        weighted: Double,
        signal: Double,
        now: Long,
    ): TasteModel {
        val previous = model.lastKey ?: return model
        if (previous == ref.key) return model
        if (!model.lastPositive) return model
        if (signal == 0.0) return model
        if (now <= model.lastAt || now - model.lastAt >= SESSION_GAP_MS) return model

        val nodes = model.edges[previous].orEmpty()
        val updated = nodes[ref.key]?.add(weighted, now, HALF_LIFE_EDGE)
            ?: Decayed().add(weighted, now, HALF_LIFE_EDGE)

        val counts = model.edgeCounts[previous].orEmpty()
        val prunedNodes = pruneNode(nodes + (ref.key to updated), now)

        return model.copy(
            edges = model.edges + (previous to prunedNodes),
            // Counts travel with their edges, so the pair a pruned node keeps is the same pair on
            // both sides and the screen never shows a transition that is not there.
            edgeCounts = model.edgeCounts + (previous to prunedNodes.mapValues { (to, _) ->
                counts[to]?.plus(1) ?: 1
            }),
        )
    }

    /** Keeps the strongest edges out of a node, dropping the ones that have decayed to noise. */
    private fun pruneNode(nodes: Map<String, Decayed>, now: Long): Map<String, Decayed> =
        nodes.asSequence()
            .map { (to, weight) -> Triple(to, weight, weight.at(now, HALF_LIFE_EDGE)) }
            .filter { (_, _, decayed) -> kotlin.math.abs(decayed) > MIN_EDGE_WEIGHT }
            .sortedByDescending { (_, _, decayed) -> kotlin.math.abs(decayed) }
            .take(MAX_EDGES_PER_NODE)
            .associate { (to, weight, decayed) -> to to Decayed(decayed, now) }

    /**
     * Bounds the model by dropping the least-recently-played, low-signal tracks first.
     *
     * Hit only by a listening history that would otherwise grow without limit; a real model of a few
     * thousand tracks never reaches it. Edges to and from a dropped track are removed with it so the
     * graph cannot point at a track that is no longer there.
     */
    private fun enforceTrackCap(model: TasteModel, now: Long): TasteModel {
        if (model.tracks.size <= MAX_TRACKS) return model

        val dropped = model.tracks.entries
            .sortedWith(
                compareBy({ it.value.stats.lastPlayed }, { it.value.stats.weight.at(now, HALF_LIFE_TRACK) }),
            )
            .take(model.tracks.size - MAX_TRACKS)
            .mapTo(HashSet()) { it.key }

        return model.copy(
            tracks = model.tracks.filterKeys { it !in dropped },
            edges = model.edges
                .filterKeys { it !in dropped }
                .mapValues { (_, nodes) -> nodes.filterKeys { it !in dropped } },
            edgeCounts = model.edgeCounts
                .filterKeys { it !in dropped }
                .mapValues { (_, nodes) -> nodes.filterKeys { it !in dropped } },
        )
    }

    private fun merge(old: TrackRef?, new: TrackRef): TrackRef {
        if (old == null) return new
        return new.copy(
            title = new.title.ifBlank { old.title },
            artist = new.artist.ifBlank { old.artist },
            ytmId = new.ytmId ?: old.ytmId,
            localPath = new.localPath ?: old.localPath,
            artworkUrl = new.artworkUrl ?: old.artworkUrl,
            durationMs = new.durationMs ?: old.durationMs,
            tags = (old.tags + new.tags).distinct(),
        )
    }

    private fun bump(
        map: Map<String, Decayed>,
        key: String,
        delta: Double,
        now: Long,
        halfLifeMs: Long,
    ): Map<String, Decayed> {
        if (key.isBlank()) return map
        val updated = map[key]?.add(delta, now, halfLifeMs) ?: Decayed().add(delta, now, halfLifeMs)
        return map + (key to updated)
    }

    companion object {
        private const val DAY = 24L * 60L * 60L * 1000L
        private const val MINUTE = 60_000L

        /** How fast a track's own accumulated signal fades. */
        val HALF_LIFE_TRACK = 30 * DAY

        /** How fast an artist's affinity fades. */
        val HALF_LIFE_ARTIST = 30 * DAY

        /** How fast a tag's affinity fades. */
        val HALF_LIFE_TAG = 45 * DAY

        /** How fast a learned transition fades. */
        val HALF_LIFE_EDGE = 60 * DAY

        /** Beyond this gap, two plays belong to different sessions and form no edge. */
        const val SESSION_GAP_MS = 30 * MINUTE

        /** A positive autoplay pick counts this much. */
        const val AUTOPLAY_POSITIVE_WEIGHT = 0.5

        /** Edges kept per node. */
        const val MAX_EDGES_PER_NODE = 30

        /** Below this decayed magnitude an edge is noise and is dropped. */
        const val MIN_EDGE_WEIGHT = 0.05

        /** The most tracks the model will hold. */
        const val MAX_TRACKS = 20_000

        /** How many recent keys Autoplay refuses to repeat. */
        const val RECENT_WINDOW = 40

        /** Out-edges of the current track considered as candidates. */
        const val CANDIDATE_EDGES = 15

        /** Artists whose completed catalogue enters the pool. */
        const val TOP_ARTISTS = 5

        /** Completed tracks taken per top artist. */
        const val ARTIST_TRACKS = 25

        /** Liked tracks always enter the pool, however they scored. */
        const val LIKED_CANDIDATES = 20

        /** Candidates the softmax draws from. */
        const val MODEL_TOP = 8

        /** Lower is greedier. A higher temperature flattens the distribution. */
        const val SOFTMAX_TEMPERATURE = 0.35

        /** `tanh(x / SQUASH)` bounds an affinity to (-1, 1). */
        const val SQUASH = 2.0

        const val EDGE2_WEIGHT = 0.4
        internal const val ARTIST_WEIGHT = 0.6
        internal const val TAG_WEIGHT = 0.5
        internal const val FAMILIARITY_WEIGHT = 0.2
        internal const val LIKED_BONUS = 0.3
        internal const val NOVELTY_BONUS = 0.15
        internal const val SKIP_PENALTY = 0.8
        const val REPEAT_PENALTY = 0.5
    }
}

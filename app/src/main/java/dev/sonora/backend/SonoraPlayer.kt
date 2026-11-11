package dev.sonora.backend

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.sonora.ui.RemoteArtworkCache
import dev.sonora.backend.taste.Origin
import dev.sonora.backend.taste.toTrackRef
import dev.sonora.playback.PlaybackService
import dev.sonora.playback.PlaybackTracker
import dev.sonora.ytm.YtmAudio
import dev.sonora.ytm.YtmStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Drives playback through the [PlaybackService].
 *
 * The player itself lives in that service, not here: it has to outlive the UI, and a session
 * service is what earns background playback and lock-screen controls. This holds a
 * [MediaController] — a connection to the service — and mirrors its state for the UI.
 */
object SonoraPlayer {

    private const val TAG = "SonoraPlayer"

    private val _state = MutableStateFlow(PlaybackState())

    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(Progress())

    /** How far through the track playback is, as its own signal. See [syncPosition]. */
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private var controller: MediaController? = null

    private var connecting = false

    /** Set when playback is requested before the controller has finished connecting. */
    private var pendingQueue: List<LibraryTrack>? = null
    private var pendingIndex = 0

    /**
     * What is playing and what comes next, for the player's own queue panel.
     *
     * Published rather than read on demand because the service only exposes media items and the
     * panel has to show the tracks — their covers, their artists, which one is the one playing — and
     * a media item cannot answer any of that. Mirrors [queue] for the same reason.
     */
    private val _upNext = MutableStateFlow<UpNext>(UpNext.Empty)

    val upNext: StateFlow<UpNext> = _upNext.asStateFlow()

    /** The same list, for the paths that only need to look something up. */
    private val queue: List<LibraryTrack> get() = _upNext.value.queue

    /** Resolved streams, by track key. A YouTube URL expires, so this is a cache and not a store. */
    private val resolved = HashMap<String, YtmAudio>()

    /**
     * The main thread, and it has to be.
     *
     * MediaController verifies the thread it was created on and refuses to be called from any
     * other, so every queue edit — a reorder, a removal, a setMediaItems — has to happen here. The
     * network is the part that moves off: [resolve] switches to IO for the fetch and comes back.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var resolveJob: Job? = null

    /**
     * Called whenever a track begins, whoever started it.
     *
     * Set by the backend, which keeps the record. A hook rather than a call at each play site
     * because the player is the only thing that sees next, previous, shuffle and auto-advance, so
     * this is the one place that knows everything that was actually listened to.
     */
    var onTrackStarted: ((LibraryTrack) -> Unit)? = null

    /** The application context, kept for the taste hooks that outlive any one screen. */
    private var appContext: Context? = null

    private var tracker: PlaybackTracker? = null

    /** Keys the engine put on the queue, so a play of one is attributed to Autoplay. */
    private val autoplayPicks = HashSet<String>()

    private val _autoplayKeys = MutableStateFlow<Set<String>>(emptySet())

    /** Queue keys Autoplay added, for the "Autoplay" label. */
    val autoplayKeys: StateFlow<Set<String>> = _autoplayKeys.asStateFlow()

    private var refillJob: Job? = null

    /** Starts connecting to the playback service. Safe to call repeatedly. */
    fun connect(context: Context) {
        if (controller != null || connecting) return

        connecting = true
        appContext = context.applicationContext
        val appContext = context.applicationContext
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()

        future.addListener(
            {
                connecting = false

                controller = runCatching { future.get() }
                    .onFailure { Log.w(TAG, "could not connect to playback service", it) }
                    .getOrNull()

                controller?.addListener(listener)
                controller?.let { active ->
                    tracker = PlaybackTracker(active) { mediaId, listened, duration, ended ->
                        onTastePlayed(mediaId, listened, duration, ended)
                    }.also { it.start() }
                }

                // Shuffle survives on the service across a UI restart, so the mirrored state is
                // read back rather than assumed to start off.
                controller?.let { active ->
                    _state.update {
                        it.copy(
                            isShuffled = active.shuffleModeEnabled,
                            repeatMode = repeatModeOf(active.repeatMode),
                        )
                    }
                }

                pendingQueue?.let { tracks ->
                    pendingQueue = null
                    controller?.let { playNow(it, tracks, pendingIndex) }
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    fun play(context: Context, track: LibraryTrack) {
        play(context, listOf(track), 0)
    }

    fun play(context: Context, tracks: List<LibraryTrack>, startIndex: Int) {
        if (tracks.isEmpty()) return

        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        val active = controller
        if (active == null) {
            pendingQueue = tracks
            pendingIndex = safeIndex
            connect(context)
        } else {
            playNow(active, tracks, safeIndex)
        }
    }

    /**
     * Puts one track straight after whatever is sounding.
     *
     * "Play next" and "add to queue" are the same operation at different distances, and they are
     * worth having separately: the first is for the song you have just thought of while this one is
     * still going, and the second is for the one you will want in a few minutes' time. Both are
     * applied to the service rather than to a local list, so the panel cannot disagree with the
     * player about the order.
     */
    fun playNext(context: Context, track: LibraryTrack) = insertAt(track, offset = 1)

    fun addToQueue(context: Context, track: LibraryTrack) = insertAt(track, offset = 0)

    private fun insertAt(track: LibraryTrack, offset: Int) {
        val active = controller ?: run {
            // Nothing is playing, so the queue is the only queue. Held rather than dropped: a
            // track queued before the service finishes starting is the one the listener is waiting
            // for, and silently playing nothing would look like the tap did nothing.
            pendingQueue = listOf(track)
            pendingIndex = 0
            return
        }

        scope.launch {
            val items = _upNext.value.queue.mapNotNull { itemFor(it) }
            val target = (active.currentMediaItemIndex + offset).coerceIn(0, items.size)
            active.addMediaItems(target, listOfNotNull(itemFor(track)))

            // The mirror moves with the service rather than waiting to be told. A queue panel that
            // is a frame behind a tap reads as the tap having failed, which is the one thing it
            // definitely did not do.
            val current = _upNext.value.index.coerceAtLeast(0)
            val queue = _upNext.value.queue.toMutableList()
            queue.add((current + offset).coerceIn(0, queue.size), track)
            _upNext.value = UpNext(queue = queue, index = current)
        }
    }

    /**
     * Moves one track to a new place in the queue.
     *
     * Applied to the service rather than to a local list, for the same reason removal is: a panel
     * that reorders itself and a player that does not means the next track after the one the
     * listener just moved is the wrong one, and nothing on screen would say so.
     *
     * What is playing does not move. A reorder that carried the sounding track with it would change
     * which index counts as "current" and rewind the position along with it — the track is still
     * the same track, and it is still being listened to.
     */
    fun moveInQueue(from: Int, to: Int) {
        val active = controller ?: return
        val queue = _upNext.value.queue
        if (from !in queue.indices || to !in queue.indices || from == to) return
        if (from == _upNext.value.index) return

        scope.launch {
            val reordered = queue.toMutableList()
            val moved = reordered.removeAt(from)
            reordered.add(to.coerceIn(0, reordered.size), moved)

            // The sounding track's index moves with whatever crossed it.
            val current = _upNext.value.index
            val index = when {
                current == from -> to
                from < current && to >= current -> current - 1
                from > current && to <= current -> current + 1
                else -> current
            }

            _upNext.value = UpNext(queue = reordered, index = index)

            val items = reordered.mapNotNull { itemFor(it) }
            val keep = active.currentPosition
            val at = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            active.setMediaItems(items, at, keep)
            active.prepare()
        }
    }

    /**
     * Takes one track out of the queue.
     *
     * Done on the service rather than in a local copy, because a queue that disagrees with the
     * player about what is in it will put the removed track back the moment anything else
     * triggers a refresh — and the listener has just been told it is gone.
     *
     * The track currently sounding is not removed: taking out what is playing would either stop
     * the music or jump to a different song, and neither is what pressing remove on the thing you
     * are listening to can reasonably mean.
     */
    fun removeFromQueue(index: Int) {
        val active = controller ?: return
        scope.launch { removeFromQueueNow(active, index) }
    }

    private suspend fun removeFromQueueNow(active: MediaController, index: Int) {
        if (index == _upNext.value.index) return

        val remaining = _upNext.value.queue.filterIndexed { position, _ -> position != index }
        // The index of whatever was sounding shifts down by one when something before it goes.
        val nowPlaying = (_upNext.value.index - if (index < _upNext.value.index) 1 else 0)
            .coerceIn(0, (remaining.size - 1).coerceAtLeast(0))

        val items = remaining.mapNotNull { itemFor(it) }
        if (items.isEmpty()) {
            active.stop()
            active.clearMediaItems()
            _upNext.value = UpNext.Empty
            _state.value = PlaybackState()
            return
        }

        _upNext.value = UpNext(queue = remaining, index = nowPlaying)
        val target = items.indexOfFirst { it.mediaId == remaining[nowPlaying].key }
            .let { if (it >= 0) it else 0 }

        // Kept on the same track where it can be, so removing something from further along the
        // queue does not interrupt what is playing.
        val keepPosition = active.currentPosition
        active.setMediaItems(items, target, keepPosition)
        active.prepare()
    }

    fun togglePlayPause() {
        val active = controller ?: return
        if (active.isPlaying) active.pause() else active.play()
    }

    fun previous() {
        controller?.seekToPreviousMediaItem()
    }

    fun next() {
        val active = controller ?: return

        // With repeat-one the queue is effectively this one track, so advancing would mean the
        // mode only ever took effect at the end of the track. Restarting is what "loop this song"
        // implies when the forward control is pressed.
        if (active.repeatMode == Player.REPEAT_MODE_ONE) {
            active.seekTo(0L)
            return
        }

        active.seekToNextMediaItem()
    }

    /**
     * Shuffle is delegated to the player rather than reordering our copy of the queue: Media3
     * already shuffles traversal while keeping the current item, so reordering would duplicate
     * that and lose the place of the track playing.
     */

    fun toggleShuffle() {
        val active = controller ?: return
        active.shuffleModeEnabled = !active.shuffleModeEnabled
    }

    /**
     * Stops playback and clears the queue.
     *
     * Needed when the file being played is deleted underneath the player: the queue would otherwise
     * hold items that can no longer be opened.
     */
    fun stop() {
        val active = controller ?: return

        active.stop()
        active.clearMediaItems()
        _upNext.value = UpNext.Empty
        pendingQueue = null
        _state.value = PlaybackState()
    }

    /**
     * Jumps to a position in the track being played.
     *
     * Seeking only moves; it does not start playback, so scrubbing while paused leaves you paused
     * at the point you chose.
     */
    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    /**
     * Cycles off → loop queue → loop track.
     *
     * Repeat is a Media3 mode, so the player decides what happens at the end of the queue; this
     * only picks the next mode. See [next] for how the forward control behaves while looping one
     * track.
     */
    fun cycleRepeat() {
        val active = controller ?: return
        active.repeatMode = when (active.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Keeps the Compose progress bar in step with the service without moving playback ownership. */
    /**
     * The play position, on its own flow.
     *
     * Kept out of [state] on purpose: the position changes twice a second, and every screen in the
     * app collects the state. Publishing it there recomposed the whole page — every shelf, every
     * row — to move a seek bar. Only the two things that draw a position collect this.
     */
    fun syncPosition() {
        val active = controller ?: return

        val now = Progress(
            positionMs = active.currentPosition.coerceAtLeast(0L),
            durationMs = active.duration.takeIf { duration -> duration > 0L } ?: 0L,
        )
        if (now != _progress.value) _progress.value = now
    }

    /**
     * Hands the queue to the player, resolving anything that is not a file first.
     *
     * A streaming track has no file to open, so its audio has to be asked for before Media3 can be
     * given anything. That is a network round trip per track, so it happens here — once, up front —
     * rather than inside the player, which would stall the queue on the first track that needs it.
     *
     * A track that will not resolve is dropped from the queue rather than added as something that
     * cannot play. The alternative is a silent gap in the middle of an album, and a queue that skips
     * a track is a far smaller failure than one that appears to hang on it.
     */
    private fun playNow(active: MediaController, tracks: List<LibraryTrack>, startIndex: Int) {
        val playable = tracks.filter { it.isDownloaded || it.isRemote }
        if (playable.isEmpty()) return

        val wanted = tracks.getOrNull(startIndex)?.key
        val index = playable.indexOfFirst { it.key == wanted }.coerceAtLeast(0)

        // The queue and the state are published before anything is resolved.
        //
        // Resolving first is what made a tap feel broken: every item in the queue is a network
        // round trip, and a page of search results is dozens of them, so the mini player did not
        // appear until all of them had answered — seconds of nothing at all, on a screen where the
        // listener has just pressed something. The queue is known immediately; only the audio is
        // late.
        _state.value = PlaybackState(track = playable[index], isResolving = true)
        _upNext.value = UpNext(queue = playable, index = index)

        resolveJob?.cancel()
        resolveJob = scope.launch {
            // The tapped track is resolved first so playback starts immediately.
            val byKey = HashMap<String, MediaItem>()
            val firstTrack = playable[index]
            val firstItem = itemFor(firstTrack)

            if (firstItem == null) {
                Log.w(TAG, "could not resolve ${firstTrack.title}")
                // The track stays, with a reason. Dropping it removes the player from the screen,
                // and a listener who tapped a song and watched the player disappear has learned
                // that the button does nothing — which is the one thing this must not do.
                _state.value = PlaybackState(
                    track = firstTrack,
                    isResolving = false,
                    problem = if (firstTrack.file == null) {
                        "Couldn't play this one. YouTube would not serve it just now."
                    } else {
                        null
                    },
                )
                return@launch
            }
            byKey[firstTrack.key] = firstItem

            // Tracks already on disk or cached in memory are resolved immediately without network.
            for (track in playable) {
                if (track.playableUri != null || resolved.containsKey(track.key)) {
                    itemFor(track)?.let { byKey[track.key] = it }
                }
            }

            // Immediately start playing what is ready without waiting on remote tracks.
            val initialItems = playable.mapNotNull { byKey[it.key] }
            val resolvedIndex = initialItems.indexOfFirst { it.mediaId == firstTrack.key }.coerceAtLeast(0)

            active.setMediaItems(initialItems, resolvedIndex, 0L)
            active.prepare()
            active.play()
            updateTrack(active, resolvedIndex)

            // Resolve remaining upcoming and previous remote tracks in the background.
            val upcoming = playable.subList(index + 1, playable.size).filter { !byKey.containsKey(it.key) }
            for (batch in upcoming.chunked(RESOLVE_BATCH)) {
                val items = batch.map { track -> async { itemFor(track) } }.awaitAll()
                val resolvedBatch = mutableListOf<MediaItem>()
                batch.forEachIndexed { at, track ->
                    items[at]?.let { item ->
                        byKey[track.key] = item
                        resolvedBatch.add(item)
                    }
                }
                if (resolvedBatch.isNotEmpty() && _upNext.value.queue == playable) {
                    active.addMediaItems(active.mediaItemCount, resolvedBatch)
                }
            }

            val previous = playable.subList(0, index).filter { !byKey.containsKey(it.key) }
            if (previous.isNotEmpty()) {
                val previousResolved = mutableListOf<MediaItem>()
                for (batch in previous.chunked(RESOLVE_BATCH)) {
                    val items = batch.map { track -> async { itemFor(track) } }.awaitAll()
                    batch.forEachIndexed { at, track ->
                        items[at]?.let { item ->
                            byKey[track.key] = item
                            previousResolved.add(item)
                        }
                    }
                }
                if (previousResolved.isNotEmpty() && _upNext.value.queue == playable) {
                    active.addMediaItems(0, previousResolved)
                }
            }
        }
    }

    /**
     * A media item for one track: the file itself, or a resolved stream with the headers its fetch
     * has to carry.
     */
    private suspend fun itemFor(track: LibraryTrack): MediaItem? {
        val uri = track.playableUri?.let { uri ->
            // The id is set here too, not only on the streaming branch. It is what the queue is
            // searched by to work out where playback actually started, and an item without one
            // never matches — which silently sent every local tap to the top of the list.
            return MediaItem.Builder()
                .setUri(Uri.parse(uri))
                .setMediaId(track.key)
                .setMediaMetadata(metadata(track))
                .build()
        }

        val audio = resolved[track.key] ?: resolve(track) ?: return null
        return MediaItem.Builder()
            .setUri(audio.url)
            .setMediaId(track.key)
            .setMediaMetadata(metadata(track))
            .build()
    }

    /**
     * What the track says about itself, including the picture the notification shows.
     *
     * The cover is part of the metadata rather than something the player screen adds, because the
     * notification is drawn by the system from this and from nothing else: an item with a title and
     * no artwork is a notification with a blank square in it, on the lock screen and in the shade.
     *
     * **Named, not fetched.** This runs once per item, for every item in the queue, before the queue
     * is handed to the player at all — so anything done here is done before the first note of the
     * first track. Pulling a cover here meant a request per queued track for a streamed one, and
     * `MediaMetadataRetriever` over the whole of a 27 MB FLAC for a downloaded one, which is a long
     * wait for a picture that is not what anyone tapped for.
     *
     * So the metadata says where the picture *is* and the library fetches it, off the play path, at
     * its own pace. A cover already extracted is named from the cache; one that is not is left to be
     * found, and the notification fills in when it arrives rather than holding the song up until it
     * does.
     */
    private fun metadata(track: LibraryTrack): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
        artworkFor(track)?.let { builder.setArtworkUri(it) }
        return builder.build()
    }

    /** Where this track's cover already is, or null when nothing has looked for it yet. */
    private fun artworkFor(track: LibraryTrack): Uri? {
        val remote = track.remote
        if (remote != null) {
            // The catalogue's own address for the picture. The library downloads it when it wants it,
            // which for a notification is immediately and off this path.
            return remote.artworkUrl
                ?.let { RemoteArtworkCache.keyFor(it, NOTIFICATION_ART_PX) }
                ?.let(Uri::parse)
        }
        return track.file?.let { RemoteArtworkCache.cachedArtwork(it) }?.let(Uri::fromFile)
    }

    /**
     * Turns a video id into a stream, reusing one already resolved this session.
     *
     * The cache is bounded and dropped wholesale when it is full rather than evicted one at a time:
     * a YouTube URL is good for about an hour, so anything cached from long ago is dead anyway and
     * there is nothing worth ordering by age.
     */
    private suspend fun resolve(track: LibraryTrack): YtmAudio? {
        val videoId = track.remote?.videoId ?: return null
        if (resolved.size >= MAX_RESOLVED) resolved.clear()

        // Off the main thread, and back on before anything touches the controller. Minting a stream
        // is two round trips to a service that decides whether to serve it at all, and a frame
        // should not be waiting on either of them.
        return runCatching { withContext(Dispatchers.IO) { YtmStream.resolve(videoId) } }
            // The type and the stack, not just the message: a resolution failure from a library
            // whose exceptions mostly carry no message at all is otherwise indistinguishable from a
            // network refusal, and those need completely different fixes.
            .onFailure {
                Log.w(TAG, "could not resolve ${track.title}", it)
            }
            .getOrNull()
            ?.also { resolved[track.key] = it }
    }



    private fun updateTrack(active: MediaController, index: Int = active.currentMediaItemIndex) {
        val mediaId = active.currentMediaItem?.mediaId
        val track = (if (mediaId != null) queue.find { it.key == mediaId } else null)
            ?: queue.getOrNull(index)
            ?: return
        val queueIndex = queue.indexOfFirst { it.key == track.key }.let { if (it >= 0) it else index }

        // The panel shows which entry is sounding, so the index travels with the queue rather than
        // being read from the controller at the moment it is drawn — a shuffle reorders the
        // controller's own list, and a position in that list is not a position in this one.
        _upNext.value = UpNext(queue = queue, index = queueIndex)
        _progress.value = Progress(
            positionMs = active.currentPosition.coerceAtLeast(0L),
            durationMs = active.duration.takeIf { it > 0L } ?: 0L,
        )
        _state.value = PlaybackState(
            track = track,
            isPlaying = active.isPlaying,
            durationMs = active.duration.takeIf { it > 0L } ?: 0L,
            isShuffled = active.shuffleModeEnabled,
            repeatMode = repeatModeOf(active.repeatMode),
            // A track that is playing has no problem, whatever the last one had.
            problem = null,
        )

        onTrackStarted?.invoke(track)
        maybeRefill(track)
    }

    /**
     * Records one finished play with the taste engine.
     *
     * The origin is read from the keys the engine put on the queue: a play of one of its own picks
     * counts for less, so the model cannot reward itself into a loop.
     */
    private fun onTastePlayed(mediaId: String, listenedMs: Long, durationMs: Long?, endedNaturally: Boolean) {
        val context = appContext ?: return
        val track = _upNext.value.queue.find { it.key == mediaId } ?: return

        // A natural ending with no reported duration is still a full listen; say so rather than
        // hand the engine a zero-length track it would score as a skip.
        val duration = when {
            durationMs != null && durationMs > 0L -> durationMs
            endedNaturally -> listenedMs.coerceAtLeast(1L)
            else -> 0L
        }

        val origin = if (mediaId in autoplayPicks) Origin.AUTOPLAY else Origin.USER
        scope.launch { SonoraBackend.recordTaste(context, track, listenedMs, duration, origin) }
    }

    /**
     * Tops the queue up when it is nearly done.
     *
     * Appends only: what the listener queued is never reordered or removed, and a refill that is
     * already in flight is left alone rather than stacked. The queue is compared before appending
     * because the radio call is a network round trip and the listener may have started something
     * else in the meantime.
     */
    private fun maybeRefill(track: LibraryTrack) {
        val context = appContext ?: return
        if (!SonoraBackend.settings.value.autoplay) return
        if (refillJob?.isActive == true) return

        val queue = _upNext.value.queue
        val remaining = queue.size - 1 - _upNext.value.index
        if (remaining > AUTOPLAY_HEADROOM) return

        refillJob = scope.launch {
            val picks = runCatching {
                SonoraBackend.autoplayNext(context, track.toTrackRef(), AUTOPLAY_BATCH)
            }.getOrDefault(emptyList())
            if (picks.isEmpty()) return@launch

            val active = controller ?: return@launch
            if (_upNext.value.queue != queue) return@launch

            val items = mutableListOf<MediaItem>()
            val playable = mutableListOf<LibraryTrack>()
            for (pick in picks) {
                val item = itemFor(pick) ?: continue
                playable += pick
                items += item
            }
            if (items.isEmpty()) return@launch

            active.addMediaItems(active.mediaItemCount, items)
            _upNext.value = UpNext(queue = queue + playable, index = _upNext.value.index)
            autoplayPicks += playable.map { it.key }
            _autoplayKeys.value = autoplayPicks.toSet()
            Log.d(TAG, "autoplay: appended ${items.size} track(s)")
        }
    }

    /** Media3 reports the repeat mode as an int; this keeps that detail out of the state. */
    private fun repeatModeOf(playerMode: Int): RepeatMode = when (playerMode) {
        Player.REPEAT_MODE_ONE -> RepeatMode.One
        Player.REPEAT_MODE_ALL -> RepeatMode.All
        else -> RepeatMode.Off
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Pause/play from the notification arrives here too, so the UI stays in step with
            // controls the app never saw.
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            controller?.let { updateTrack(it) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _state.update { it.copy(isShuffled = shuffleModeEnabled) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _state.update { it.copy(repeatMode = repeatModeOf(repeatMode)) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                _state.update { it.copy(isPlaying = false) }
            }
        }
    }

    private const val MAX_RESOLVED = 32


    /**
     * How many of the rest of a queue are resolved at once.
     *
     * Enough that the time to the tapped track does not grow with the size of the library, small
     * enough that a library of a few thousand tracks does not open a few thousand sockets at it, and
     * small enough that the track being waited on is never one of many competing for the same
     * connection. Six is the point where this machine's own numbers stop improving.
     */
    private const val RESOLVE_BATCH = 6

    /** Big enough for the notification's own copy of a cover, which it scales down itself. */

    private const val NOTIFICATION_ART_PX = 544

    /** How many items may remain before Autoplay tops the queue up. */
    private const val AUTOPLAY_HEADROOM = 2

    /** How many picks one refill adds. */
    private const val AUTOPLAY_BATCH = 5
}

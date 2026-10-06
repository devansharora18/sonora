package dev.sonora.ui

import dev.sonora.backend.LibraryTrack
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import dev.sonora.lyrics.LyricsStore
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import dev.sonora.backend.AudioQuality
import dev.sonora.backend.RepeatMode
import dev.sonora.backend.SearchQueries
import dev.sonora.backend.SonoraBackend
import dev.sonora.backend.SonoraPlayer
import dev.sonora.ui.theme.accentText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    context: android.content.Context,
    onClose: () -> Unit,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenArtist: (String) -> Unit = {},
    onOpenAlbum: (String) -> Unit = {},
    /** Raised when something needed the peer network and there was no session to ask. */
    onNeedPeers: () -> Unit = {},
) {
    val playback by SonoraPlayer.state.collectAsState()
    val upNext by SonoraPlayer.upNext.collectAsState()
    // The queue is a panel over the player rather than a page, so it is state here and not a screen
    // the caller has to know about. A listener who opens it is still listening.
    var queueOpen by remember { mutableStateOf(false) }
    // The words, over the sleeve. Held here rather than in the app so the pane and the artwork are
    // two states of one thing and cannot disagree about which is showing.
    var lyricsOpen by remember { mutableStateOf(false) }
    var lyricsFullscreen by remember { mutableStateOf(false) }
    val lyrics by LyricsStore.current.collectAsState()
    val track = playback.track ?: return

    LaunchedEffect(lyricsOpen) {
        if (!lyricsOpen) lyricsFullscreen = false
    }

    // Asked for as the pane opens, not when the track starts: a request made for a track nobody is
    // going to read the words of is a request for nothing.
    LaunchedEffect(lyricsOpen, track.key, playback.durationMs) {
        if (lyricsOpen) {
            LyricsStore.request(track, playback.durationMs)
        }
    }

    // Non-null only while a finger is down on the bar. Held locally so the polled position cannot
    // drag the handle back out from under the drag.
    var scrubbing by remember(track.file) { mutableStateOf<Float?>(null) }
    val duration = playback.durationMs
    val played = scrubbing ?: if (duration > 0L) {
        (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val dismissThresholdPx = with(density) { 120.dp.toPx() }
    val artCardWidthPx = screenWidthPx - with(density) { 48.dp.toPx() }
    val artGapPx = with(density) { 48.dp.toPx() }
    val slideDistancePx = artCardWidthPx + artGapPx

    var displayedTrack by remember(track.file) { mutableStateOf(track) }
    var totalDragX by remember { mutableFloatStateOf(0f) }
    val artOffset = remember { Animatable(0f) }
    var isSwitching by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(track.key) {
        displayedTrack = track
        artOffset.snapTo(0f)
        totalDragX = 0f
        isSwitching = false
    }

    val queue = upNext.queue
    val isRepeatAll = playback.repeatMode == RepeatMode.All
    val currentIndex = queue.indexOfFirst { it.key == displayedTrack.key }
        .let { if (it >= 0) it else upNext.index }
    val hasNext = queue.size > 1 && (isRepeatAll || currentIndex < queue.size - 1)
    val hasPrevious = queue.size > 1 && (isRepeatAll || currentIndex > 0)

    val nextTrack = if (hasNext) {
        val idx = (currentIndex + 1) % queue.size
        queue.getOrNull(idx)
    } else null

    val previousTrack = if (hasPrevious) {
        val idx = if (currentIndex - 1 < 0) queue.size - 1 else currentIndex - 1
        queue.getOrNull(idx)
    } else null

    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val animOffsetY = remember { Animatable(0f) }
    var isAnimating by remember { mutableStateOf(false) }
    var isDismissing by remember { mutableStateOf(false) }
    val sheetCornerShape = remember { RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp) }

    val currentOffset = if (isAnimating) animOffsetY.value else dragOffsetY

    val dragFraction = if (slideDistancePx > 0f) {
        (artOffset.value / slideDistancePx).coerceIn(-1f, 1f)
    } else 0f
    val nextBackdropAlpha = if (dragFraction < 0f && nextTrack != null) -dragFraction else 0f
    val prevBackdropAlpha = if (dragFraction > 0f && previousTrack != null) dragFraction else 0f

    val verticalDragState = rememberDraggableState { delta ->
        if (isDismissing) return@rememberDraggableState
        if (isAnimating) {
            dragOffsetY = animOffsetY.value
            isAnimating = false
        }
        dragOffsetY = (dragOffsetY + delta).coerceIn(0f, screenHeightPx)
    }

    val onVerticalDragStopped: suspend kotlinx.coroutines.CoroutineScope.(Float) -> Unit = { velocity ->
        if (!isDismissing) {
            val offset = dragOffsetY
            val shouldDismiss = (offset > dismissThresholdPx && velocity > -400f) || velocity > 800f
            if (shouldDismiss) {
                isDismissing = true
                isAnimating = true
                coroutineScope.launch {
                    try {
                        animOffsetY.snapTo(offset)
                        animOffsetY.animateTo(
                            targetValue = screenHeightPx,
                            initialVelocity = velocity.coerceAtLeast(0f),
                            animationSpec = tween(
                                durationMillis = 200,
                                easing = FastOutLinearInEasing,
                            ),
                        )
                    } catch (_: Exception) {
                    } finally {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            onClose()
                        }
                    }
                }
            } else {
                isAnimating = true
                coroutineScope.launch {
                    try {
                        animOffsetY.snapTo(offset)
                        animOffsetY.animateTo(
                            targetValue = 0f,
                            initialVelocity = velocity,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    } finally {
                        dragOffsetY = 0f
                        isAnimating = false
                    }
                }
            }
        }
    }

    fun animateNext() {
        if (isSwitching) return
        val target = nextTrack
        if (!hasNext || target == null) {
            coroutineScope.launch {
                artOffset.animateTo(-36f, animationSpec = tween(90))
                artOffset.animateTo(
                    0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                )
            }
            return
        }
        isSwitching = true
        coroutineScope.launch {
            artOffset.animateTo(
                targetValue = -slideDistancePx,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
            )
            displayedTrack = target
            artOffset.snapTo(0f)
            totalDragX = 0f
            SonoraPlayer.next()
            kotlinx.coroutines.delay(600)
            if (isSwitching) {
                displayedTrack = track
                isSwitching = false
            }
        }
    }

    fun animatePrevious() {
        if (isSwitching) return
        val target = previousTrack
        if (!hasPrevious || target == null) {
            coroutineScope.launch {
                artOffset.animateTo(36f, animationSpec = tween(90))
                artOffset.animateTo(
                    0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                )
            }
            return
        }
        isSwitching = true
        coroutineScope.launch {
            artOffset.animateTo(
                targetValue = slideDistancePx,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
            )
            displayedTrack = target
            artOffset.snapTo(0f)
            totalDragX = 0f
            SonoraPlayer.previous()
            kotlinx.coroutines.delay(600)
            if (isSwitching) {
                displayedTrack = track
                isSwitching = false
            }
        }
    }

    val horizontalDragState = rememberDraggableState { delta ->
        val dampedDelta = when {
            delta < 0 && (!hasNext || nextTrack == null) -> delta * 0.35f
            delta > 0 && (!hasPrevious || previousTrack == null) -> delta * 0.35f
            else -> delta
        }
        totalDragX += dampedDelta
        coroutineScope.launch {
            artOffset.snapTo(totalDragX)
        }
    }

    val onHorizontalDragStopped: suspend kotlinx.coroutines.CoroutineScope.(Float) -> Unit = { velocity ->
        val switchThreshold = slideDistancePx * 0.35f
        if (totalDragX < -switchThreshold || velocity < -800f) {
            val target = nextTrack
            if (hasNext && target != null) {
                isSwitching = true
                coroutineScope.launch {
                    artOffset.animateTo(
                        targetValue = -slideDistancePx,
                        initialVelocity = velocity,
                        animationSpec = tween(180, easing = FastOutLinearInEasing),
                    )
                    displayedTrack = target
                    artOffset.snapTo(0f)
                    totalDragX = 0f
                    SonoraPlayer.next()
                    kotlinx.coroutines.delay(600)
                    if (isSwitching) {
                        displayedTrack = track
                        isSwitching = false
                    }
                }
            } else {
                coroutineScope.launch {
                    artOffset.animateTo(
                        targetValue = 0f,
                        initialVelocity = velocity,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                    )
                    totalDragX = 0f
                }
            }
        } else if (totalDragX > switchThreshold || velocity > 800f) {
            val target = previousTrack
            if (hasPrevious && target != null) {
                isSwitching = true
                coroutineScope.launch {
                    artOffset.animateTo(
                        targetValue = slideDistancePx,
                        initialVelocity = velocity,
                        animationSpec = tween(180, easing = FastOutLinearInEasing),
                    )
                    displayedTrack = target
                    artOffset.snapTo(0f)
                    totalDragX = 0f
                    SonoraPlayer.previous()
                    kotlinx.coroutines.delay(600)
                    if (isSwitching) {
                        displayedTrack = track
                        isSwitching = false
                    }
                }
            } else {
                coroutineScope.launch {
                    artOffset.animateTo(
                        targetValue = 0f,
                        initialVelocity = velocity,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                    )
                    totalDragX = 0f
                }
            }
        } else {
            coroutineScope.launch {
                artOffset.animateTo(
                    targetValue = 0f,
                    initialVelocity = velocity,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                )
                totalDragX = 0f
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = currentOffset
                if (currentOffset > 0f) {
                    clip = true
                    shape = sheetCornerShape
                }
            },
        color = MaterialTheme.colorScheme.background,
    ) {
        ArtworkBackdrop(track = displayedTrack)

        // Blend in next track's backdrop during swipe left
        if (nextTrack != null && nextBackdropAlpha > 0f) {
            ArtworkBackdrop(
                track = nextTrack,
                modifier = Modifier.graphicsLayer { alpha = nextBackdropAlpha },
            )
        }

        // Blend in previous track's backdrop during swipe right
        if (previousTrack != null && prevBackdropAlpha > 0f) {
            ArtworkBackdrop(
                track = previousTrack,
                modifier = Modifier.graphicsLayer { alpha = prevBackdropAlpha },
            )
        }

        val playerControlsAlpha by animateFloatAsState(
            targetValue = if (queueOpen) 0f else 1f,
            animationSpec = tween(durationMillis = 200),
            label = "playerControlsAlpha",
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = playerControlsAlpha }
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 24.dp, top = 4.dp, end = 24.dp, bottom = 24.dp)
                .draggable(
                    orientation = Orientation.Vertical,
                    enabled = !lyricsOpen && !queueOpen,
                    state = verticalDragState,
                    onDragStopped = onVerticalDragStopped,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Drag affordance handle at the top of the player sheet
            Box(
                modifier = Modifier
                    .padding(top = 2.dp, bottom = 12.dp)
                    .draggable(
                        orientation = Orientation.Vertical,
                        enabled = lyricsOpen,
                        state = verticalDragState,
                        onDragStopped = onVerticalDragStopped,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.32f)),
                )
            }

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier.align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SubIconButton(
                        icon = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Collapse player",
                        onClick = onClose,
                        size = 40.dp,
                        glyphSize = 24.dp,
                        idleTint = Color.White.copy(alpha = 0.8f),
                    )

                    AnimatedVisibility(
                        visible = lyricsOpen,
                        enter = fadeIn(tween(180)),
                        exit = fadeOut(tween(140)),
                    ) {
                        SubIconButton(
                            icon = if (lyricsFullscreen) {
                                Icons.Rounded.FullscreenExit
                            } else {
                                Icons.Rounded.Fullscreen
                            },
                            contentDescription = if (lyricsFullscreen) {
                                "Exit fullscreen lyrics"
                            } else {
                                "Fullscreen lyrics"
                            },
                            onClick = { lyricsFullscreen = !lyricsFullscreen },
                            size = 40.dp,
                            glyphSize = 24.dp,
                            active = lyricsFullscreen,
                            activeTint = Color.White,
                            idleTint = Color.White.copy(alpha = 0.55f),
                        )
                    }
                }

                Text(
                    text = "NOW PLAYING",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp,
                    ),
                    color = Color.White.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                )

                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    LikeGlyph(
                        liked = isLiked,
                        onClick = onToggleLike,
                        size = 40.dp,
                        glyphSize = 22.dp,
                    )

                    SubIconButton(
                        icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                        contentDescription = "Add to playlist",
                        onClick = onAddToPlaylist,
                        size = 40.dp,
                        glyphSize = 24.dp,
                        idleTint = Color.White.copy(alpha = 0.8f),
                    )
                }
            }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = if (lyricsOpen) Arrangement.Top else Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The sleeve and the words, one over the other. Crossfaded rather than swapped, so
            // turning the lyrics on is a change of what the screen is *about* rather than a redraw:
            // a hard cut reads as a different page, and this is the same page about the same song.
            val artAlpha by animateFloatAsState(
                targetValue = if (lyricsOpen) 0f else 1f,
                animationSpec = tween(340),
                label = "artAlpha",
            )
            val wordsAlpha by animateFloatAsState(
                targetValue = if (lyricsOpen) 1f else 0f,
                animationSpec = tween(340),
                label = "wordsAlpha",
            )

            // The sleeve and the words share one square, crossfaded. Stacked as two squares they
            // were two full-width blocks in a column, which is twice the height the screen has: the
            // second was squeezed to nothing and drew a music note where the cover should be.
            //
            // The same square the sleeve occupied, so the words take the cover's place rather than
            // the cover's place *and* the room below it. A pane measured against the whole column
            // pushes the transport off the bottom of the screen, which is where the thing that
            // turns the lyrics off is.
            val artworkShape = remember { RoundedCornerShape(22.dp) }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (!lyricsOpen) {
                            Modifier.aspectRatio(1f)
                        } else {
                            Modifier.fillMaxSize()
                        }
                    ),
            ) {
                // A track that could not be fetched says so, in the place the picture would be, and
                // offers the two things a listener can actually do about it. A spinner that never stops
                // is worse than nothing: it says "working on it" for as long as it is on screen.
                val problem = playback.problem
                if (problem != null) {
                    UnplayableState(
                        track = track,
                        reason = problem,
                        onRetry = { SonoraPlayer.play(context, track) },
                        onSkip = { SonoraPlayer.next() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (artAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .draggable(
                                orientation = Orientation.Horizontal,
                                enabled = !isSwitching && !lyricsOpen,
                                state = horizontalDragState,
                                onDragStopped = onHorizontalDragStopped,
                            ),
                    ) {
                        // Previous card (left)
                        if (previousTrack != null) {
                            ArtworkCard(
                                track = previousTrack,
                                artworkShape = artworkShape,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationX = artOffset.value - slideDistancePx
                                        alpha = artAlpha
                                        val scale = 1f - (1f - artAlpha) * 0.08f
                                        scaleX = scale
                                        scaleY = scale
                                    },
                            )
                        }

                        // Next card (right)
                        if (nextTrack != null) {
                            ArtworkCard(
                                track = nextTrack,
                                artworkShape = artworkShape,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationX = artOffset.value + slideDistancePx
                                        alpha = artAlpha
                                        val scale = 1f - (1f - artAlpha) * 0.08f
                                        scaleX = scale
                                        scaleY = scale
                                    },
                            )
                        }

                        // Current track card (center)
                        ArtworkCard(
                            track = displayedTrack,
                            artworkShape = artworkShape,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = artOffset.value
                                    alpha = if (problem != null) 0f else artAlpha
                                    val scale = 1f - (1f - artAlpha) * 0.08f
                                    scaleX = scale
                                    scaleY = scale
                                },
                        )
                    }
                }

                if (wordsAlpha > 0f) {
                    LyricsPane(
                        lyrics = lyrics,
                        positionMs = playback.positionMs,
                        isPlaying = playback.isPlaying,
                        onSeek = { SonoraPlayer.seekTo(it) },
                        isFullscreen = lyricsFullscreen,
                        modifier = Modifier.graphicsLayer { alpha = wordsAlpha },
                    )
                }
            }
        }

            val titleTopPadding by animateDpAsState(
                targetValue = if (lyricsFullscreen) 12.dp else 22.dp,
                animationSpec = tween(280),
                label = "titleTopPadding",
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = titleTopPadding),
            ) {
                val textShadow = remember {
                    Shadow(
                        color = Color.Black.copy(alpha = 0.45f),
                        offset = Offset(0f, 1f),
                        blurRadius = 4f,
                    )
                }

                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.4).sp,
                        shadow = textShadow,
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val hasArtist = !track.artist.isNullOrBlank()
                    val hasAlbum = !track.album.isNullOrBlank()

                    if (hasArtist && hasAlbum) {
                        BlinkableText(
                            text = track.artist!!,
                            onClick = { onOpenArtist(track.artist) },
                            shadow = textShadow,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = " · ",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Normal,
                                shadow = textShadow,
                            ),
                            color = Color.White.copy(alpha = 0.55f),
                        )
                        BlinkableText(
                            text = track.album!!,
                            onClick = { onOpenAlbum(track.album) },
                            shadow = textShadow,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    } else if (hasArtist) {
                        BlinkableText(
                            text = track.artist!!,
                            onClick = { onOpenArtist(track.artist) },
                            shadow = textShadow,
                        )
                    } else if (hasAlbum) {
                        BlinkableText(
                            text = track.album!!,
                            onClick = { onOpenAlbum(track.album) },
                            shadow = textShadow,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = !lyricsFullscreen,
                enter = expandVertically(
                    animationSpec = tween(
                        durationMillis = 280,
                        easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
                    ),
                    expandFrom = Alignment.Top,
                ) + slideInVertically(
                    initialOffsetY = { it / 2 },
                    animationSpec = tween(
                        durationMillis = 280,
                        easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
                    ),
                ) + fadeIn(animationSpec = tween(durationMillis = 200)),
                exit = shrinkVertically(
                    animationSpec = tween(
                        durationMillis = 260,
                        easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
                    ),
                    shrinkTowards = Alignment.Top,
                ) + slideOutVertically(
                    targetOffsetY = { it / 2 },
                    animationSpec = tween(
                        durationMillis = 260,
                        easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
                    ),
                ) + fadeOut(animationSpec = tween(durationMillis = 180)),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val quality = remember(track.key, duration) { AudioQuality.from(track, duration) }
                    if (quality.isNotBlank()) {
                        val badgeShape = RoundedCornerShape(percent = 50)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(badgeShape)
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .border(0.5.dp, Color.White.copy(alpha = 0.15f), badgeShape)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    text = if (quality == "YT Music") "YT Music" else quality.uppercase(),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 0.6.sp,
                                    ),
                                    color = Color.White.copy(alpha = 0.85f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    ) {
                        PlayerScrubber(
                            positionMs = (played.toDouble() * duration).toLong(),
                            durationMs = duration,
                            onSeek = { SonoraPlayer.seekTo(it) },
                        )
                    }

                    TransportRow(
                        isPlaying = playback.isPlaying,
                        onPrevious = { animatePrevious() },
                        onPlayPause = { SonoraPlayer.togglePlayPause() },
                        onNext = { animateNext() },
                        modifier = Modifier.padding(top = 16.dp),
                    )

                    Spacer(Modifier.height(16.dp))

                    val (volume, onVolumeChange) = rememberDeviceVolume()
                    VolumeRow(
                        volume = volume,
                        onVolumeChange = onVolumeChange,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )

                    Spacer(Modifier.height(16.dp))

                    PlayerActionRow(
                        lyricsOpen = lyricsOpen,
                        onToggleLyrics = {
                            lyricsOpen = !lyricsOpen
                            if (lyricsOpen) queueOpen = false
                        },
                        isShuffled = playback.isShuffled,
                        onToggleShuffle = onToggleShuffle,
                        repeatMode = playback.repeatMode,
                        onCycleRepeat = onCycleRepeat,
                        queueOpen = queueOpen,
                        onToggleQueue = { queueOpen = !queueOpen },
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        }

    BackHandler(enabled = lyricsFullscreen) {
        lyricsFullscreen = false
    }

    AnimatedVisibility(
        visible = queueOpen,
        modifier = Modifier.fillMaxSize(),
        enter = slideInVertically(
            initialOffsetY = { fullHeight -> fullHeight },
            animationSpec = tween(
                durationMillis = 240,
                easing = CubicBezierEasing(0.1f, 1f, 0.1f, 1f),
            ),
        ) + fadeIn(
            animationSpec = tween(durationMillis = 180),
        ),
        exit = slideOutVertically(
            targetOffsetY = { fullHeight -> fullHeight },
            animationSpec = tween(
                durationMillis = 200,
                easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f),
            ),
        ) + fadeOut(
            animationSpec = tween(durationMillis = 160),
        ),
    ) {
        BackHandler { queueOpen = false }
        QueuePanel(
            upNext = upNext,
            onPlayFrom = { index -> SonoraPlayer.play(context, upNext.queue, index) },
            onRemove = { index -> SonoraPlayer.removeFromQueue(index) },
            onMove = { from, to -> SonoraPlayer.moveInQueue(from, to) },
            onClose = { queueOpen = false },
        )
    }
}
}

private fun formatMillis(value: Long): String {
    val totalSeconds = (value / 1000L).coerceAtLeast(0L)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun BlinkableText(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shadow: Shadow? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val flash = remember { Animatable(0f) }
    var isBlinking by remember { mutableStateOf(false) }
    val baseColor = Color.White.copy(alpha = 0.72f)
    val currentColor = lerp(baseColor, Color.White, flash.value)

    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = FontWeight.Medium,
            shadow = shadow,
        ),
        color = currentColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) {
            if (isBlinking) return@clickable
            isBlinking = true
            coroutineScope.launch {
                flash.animateTo(1f, animationSpec = tween(durationMillis = 80))
                onClick()
                flash.animateTo(0f, animationSpec = tween(durationMillis = 120))
                isBlinking = false
            }
        },
    )
}

@Composable
private fun ArtworkCard(
    track: LibraryTrack,
    artworkShape: Shape,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .shadow(elevation = 16.dp, shape = artworkShape, clip = false)
            .clip(artworkShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 0.5.dp,
                color = Color.White.copy(alpha = 0.12f),
                shape = artworkShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val artwork = rememberTrackArtwork(track, px = PLAYER_ART_PX)
        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = "Album artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(72.dp),
            )
        }
    }
}


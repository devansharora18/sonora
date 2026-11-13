package dev.sonora.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeEffectScope
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect

/**
 * The measurements every page, bar and card is built from.
 *
 * They live here rather than in whichever screen needed them first because they only work as a set:
 * a track row, a card and a heading all line up with the edge of the bars stacked below them by
 * sharing one inset, and the pill bars are inset by the same [PAGE_GUTTER] that the artwork in a row
 * clears its own padding by. Moving one without the others is what produces a bar that looks a pixel
 * or two narrow.
 */

/**
 * The left and right inset every page's content sits at.
 *
 * Ten, not twenty. The bars at the foot of the page float at this inset, so content that sits wider
 * than the bars looks like it is hanging off the edge rather than sitting on the page.
 */
val PAGE_GUTTER: Dp = 10.dp

/** Where a divider under a track row starts: clear of the artwork. */
val ROW_DIVIDER_INSET: Dp = PAGE_GUTTER + 68.dp

/**
 * How wide the floating bars are ever allowed to get.
 *
 * Both are fixed rows of controls rather than content that benefits from room, and a phone is the
 * width they were spaced for. Run right across a tablet the tabs end up marooned in the middle of
 * nothing, so past this they stop growing and centre themselves over the page instead.
 *
 * Set clear of the widest phone, so on a phone it does nothing and the bars still line up with the
 * page's content.
 */
val FLOATING_BAR_MAX_WIDTH: Dp = 440.dp

/**
 * Width of a card in the compact carousels.
 *
 * Sized so a phone-width row shows two cards whole with the edge of a third showing: enough to say
 * the row scrolls without a card being half a card.
 */
val SHELF_CARD_WIDTH: Dp = 150.dp

/** Gap between cards in a shelf, and between a shelf's header and its cards. */
val SHELF_SPACING: Dp = 14.dp

/** The corner every thumbnail in the app carries. */
val CARD_CORNER: Dp = 8.dp

/** Share of the row a lead-shelf card takes, so the next one peeks in past it. */
private const val HERO_CARD_FRACTION = 0.70f

/**
 * How wide a lead-shelf card is ever allowed to get.
 *
 * The fraction alone is a phone measurement wearing a percent sign: 70% of a tablet is a card the
 * better part of a foot across, and a hero card is a caption over some artwork rather than a canvas.
 * Set just clear of what the widest phone asks for, so every phone keeps the width the fraction gives
 * it and only a wider screen is held back.
 */
private val HERO_CARD_MAX_WIDTH: Dp = 320.dp

/** A lead-shelf card's proportions: a touch taller than it is wide. */
const val HERO_CARD_RATIO: Float = 0.92f

/**
 * How wide a lead-shelf card should be in a row [available] wide.
 *
 * Shared by the real shelf and the skeleton that stands in for it, which have to agree to the pixel
 * or the page jumps when the data lands. Given the row's own width rather than the window's, so it
 * is still right in the narrower column a tablet leaves once the player has taken its pane.
 */
fun heroCardWidth(available: Dp): Dp = minOf(available * HERO_CARD_FRACTION, HERO_CARD_MAX_WIDTH)

/** Share of the row a page of tracks takes, so the next page peeks in past it. */

/**
 * How wide a sideways-paging column of track rows is ever allowed to get.
 *
 * A track row is artwork, a title and a subtitle, none of which have any use for more room — at 88%
 * of a tablet the contents stay their own size and the space all lands between the title and the
 * overflow button.
 */
/** The narrowest a library grid card is let get before another column gives way. */
private val LIBRARY_GRID_MIN_CARD_WIDTH: Dp = 140.dp

/** Gap between cards in a library grid, in both directions. */
val LIBRARY_GRID_SPACING: Dp = 12.dp

private const val LIBRARY_GRID_MIN_COLUMNS = 2

/** Library shelves never grow past this many across, however wide the screen. */
private const val LIBRARY_GRID_MAX_COLUMNS = 5

/** How many cards sit across a library grid row, and how wide each lands. */
data class LibraryGridSpec(val columns: Int, val cardWidth: Dp)

/**
 * How wide, in pixels, cover art is fetched for each place it appears.
 *
 * In pixels rather than dp because these cross the network: a request for 52dp at three densities
 * is three different requests for what is the same picture, and the number that matters is the one
 * the decode is sized against. A row is small, a card is not, and the player sleeve is larger than
 * both — asking for the row's size and stretching it over the sleeve is what makes a cover look
 * soft, and no amount of careful decoding can put those pixels back.
 */
const val ROW_ART_PX = 200

/** A shelf card, drawn at 150dp. */
const val CARD_ART_PX = 480

/** The player sleeve, which fills most of a screen. */
const val PLAYER_ART_PX = 720

/**
 * How many covers fit across [available], and how wide each one should be.
 *
 * Adding a column at the narrowest card rather than a fixed count is what makes a grid on a narrow
 * phone the same size as the same grid on a tablet — a fixed three across turns a phone into three
 * unusably small covers.
 */
fun libraryGrid(available: Dp): LibraryGridSpec {
    if (available <= 0.dp) return LibraryGridSpec(LIBRARY_GRID_MIN_COLUMNS, 0.dp)

    val spacing = LIBRARY_GRID_SPACING
    val widest = LIBRARY_GRID_MAX_COLUMNS

    // Walk down from the widest until every column clears the minimum. The widest that does is the
    // one that goes, so the cards come out as large as they can while still fitting.
    for (columns in widest downTo LIBRARY_GRID_MIN_COLUMNS) {
        val cardWidth = (available - spacing * (columns - 1)) / columns
        if (cardWidth >= LIBRARY_GRID_MIN_CARD_WIDTH) return LibraryGridSpec(columns, cardWidth)
    }

    return LibraryGridSpec(
        LIBRARY_GRID_MIN_COLUMNS,
        (available - spacing * (LIBRARY_GRID_MIN_COLUMNS - 1)) / LIBRARY_GRID_MIN_COLUMNS,
    )
}

/**
 * Keeps Haze's visual style while allowing it to reduce its sampling resolution.
 *
 * Haze 1.x processes every effect at full resolution, even when a large blur makes those extra
 * source pixels invisible — which is every frame of every scroll past the mini player and the tab
 * bar. A third is what the glass surfaces already sample at, and the blur is what hides the
 * upscale, so the pixels being paid for could not be seen either way.
 */
@OptIn(ExperimentalHazeApi::class)
fun Modifier.optimizedHazeEffect(
    state: HazeState,
    style: HazeStyle = HazeStyle.Unspecified,
    block: (HazeEffectScope.() -> Unit)? = null,
): Modifier = hazeEffect(state, style) {
    inputScale = HazeInputScale.Fixed(0.33f)
    block?.invoke(this)
}

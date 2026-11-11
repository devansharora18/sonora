# Decisions

Assumptions and trade-offs made while building Radio and fixing the audit, with the reason each
one is the way it is. Newest first.

## Radio

**Variety is a proportion, not three modes.** The first build had Blended / Discover / Familiar as
three mixes. They are now one slider: 0 means only the picked artists, 1 means almost entirely
artists around them, and a mix is built to that ratio. Three named modes asked the listener to
choose between things they could not see; a slider shows its answer in the caption under it, and
the ends of its range mean something exact. `RadioBuilder.blend` meets the ratio by target count
per slot rather than by a fixed pattern, so both ends are exact and the middle is even.

**Familiar only stayed a separate switch.** It is not a point on the variety axis — it is a promise
about every track regardless of reach — so it filters the pool on both sides of the blend instead of
being folded into the slider.

**Saved radios store ingredients, not tracks.** A saved radio is artists + variety + familiar flag.
Rebuilding the queue on every play means a radio does not go stale when the catalogue or the
listening history changes, and it cannot replay the same forty tracks forever.

**Saving the same artists twice makes two radios.** Different variety is a different thing worth
going back to; merging them would make the slider unkeepable.

**Radio lives on Home and in the Library, not in a sixth tab.** The bar already divides its width by
five; a sixth leaves about 50dp per tab at 360dp and the longer labels start to ellipsize. Home
carries a "Start a radio" card and a "Your radios" shelf; the Library carries a Radios section.

**A refill only continues a mix while the queue is still that mix.** `activeRadioKeys` records what
the mix queued; if the playing track is not one of them, the listener started something else by hand
and the refill falls back to Autoplay instead of hijacking their choice.

**Refill after a manual track pick stops the radio** rather than re-seeding it from the new track.
The alternative — silently turning an album into a radio — is worse than the radio ending.

**A seed that cannot resolve is skipped, not fatal.** `RadioArtistDirectory` caches "asked, nothing
there" and every lookup degrades to null. One silent artist does not empty a mix of the others.

**Discover's artist cap was replaced by the blend.** The old rule capped picked artists at a third
and packed them at the front, because ranking puts the listener's own artists first. The blend's
target-count logic puts them where the variety asks, so the cap has nothing left to do.

## Player and lists

**The play position is its own flow.** It used to be a field on `PlaybackState` updated every 500ms,
which recomposed every screen in the app twice a second to move a seek bar. `SonoraPlayer.progress`
is collected only by the two things that draw a position.

**The mini player got a progress line and a 64dp bar.** Transport targets are 48dp now, which made
the glyph slot taller than the 40dp artwork, so the slot sets the bar's height.

**Swipe-to-queue was deleted, not repaired.** It had no caller, and its commit check could never
fire. A gesture nobody can reach is a worse answer than no gesture.

**Home no longer ends in the Browse-all grid.** It was the same composable as Search's, so two of
five tabs opened onto the same catalogue. Search keeps it.

**Category names moved to the top of their tiles.** The covers are borrowed from YouTube Music and
print their own headline across the lower half; a label drawn at the bottom landed on top of it.
The top of a cover is the picture.

**Read-only settings rows lost their chevron.** An arrow beside "Plays recorded" promised a page
that does not exist.

**The per-row AUTOPLAY tag stayed.** Rows in the queue can be reordered by hand, so a single
"autoplay starts here" divider would move out of step with what it describes.

**The Now Playing volume slider stayed.** It duplicates the hardware keys on a phone, but it is the
only volume control on a tablet or a TV where the custom player draws its own chrome.

**Audit item M9 (a section heading cut in half by the floating bar) was not reproduced.** The bottom
scrim fades content under the bars and every list already carries bottom content padding; what the
audit saw was the viewport edge, not the bar. No change made.

## Housekeeping

**`JsonFile` now ignores unknown keys.** A document written by an older build still reads; a field
that was removed is not a reason to throw away the listener's playlists.

**Pre-existing uncommitted files in `.agents/` and `AGENTS.md` were left alone.** They belong to
separate work in progress and have nothing to do with this change.

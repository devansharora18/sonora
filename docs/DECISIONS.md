# Decisions

Assumptions and trade-offs made while building Radio and fixing the audit, with the reason each
one is the way it is. Newest first.

## Radio

**Variety is a proportion, not three modes.** The first build had Blended / Discover / Familiar as
three mixes. They are now one slider over five stops, each an exact share of the queue from the
picked artists: **100:0, 80:20, 60:40, 50:50, 30:70**. Three named modes asked the listener to
choose between things they could not see; a ratio can be read off the caption. The stops are not
evenly spaced and the last still keeps 30% for the picks — the far end means "mostly new", not
"none of what I asked for". `RadioBuilder.blend` hits each share by tracking the target count per
slot, so a stop is exact rather than approximate.

**The artist's own catalogue is what supplies a high share.** A radio seeded by an artist answers
with the neighbourhood — mostly other people — so the first build could not fill an 80:20 queue and
filled it with neighbours instead. `RadioArtistDirectory.tracksFor` now keeps the artist page's
whole track list and `RadioBuilder` takes `seedCatalogue` from it. Without this the slider's left
half was a promise the pool could not keep.

**A track credited to a collaborator belongs to the picks half, and only there.** An artist's page
lists collaborations under the other artist's name first, so the same track can arrive through the
radio as an outsider and through the catalogue as a pick. Where it is added second, it is removed
from the other half — `blend` draws from the two lists independently and would otherwise queue the
same song twice.

**The peer tab is not re-asked for a term it already answered.** Tapping Soulseek with results
already on screen used to rebuild the search state from scratch: the rows vanished, the sort reset
and the query went out to the network a second time. The pill only asks when the peers have said
nothing yet.

**The picker dedupes by artist id before it draws.** Two search results whose names fold together
("Passenger", six times over) were two list items with one key, which is an immediate crash. Rows
are keyed by browse id, and the list is deduped by it first.

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

**A refill keeps the mix's rule, its artists, and its pace.** Three things had to be true for an
endless radio to stay honest. The set of what the mix has queued is keyed the way `TrackRef` keys
it, because the refill is handed a `TrackRef` and the player's keys (`ytm:<id>`) never match it —
that mismatch dropped the radio on the very first refill and let Autoplay carry on with other
artists under the radio's name. The set grows with every refill, so a refill never hands back
something already queued. And when a finite catalogue runs low the batch is topped up from the
whole pool rather than dribbled out one track at a time: repeating these artists is what an endless
radio of them means, and it beats handing the queue to Autoplay.

**The picks half ignores the recently-played window.** That window exists so Autoplay does not
repeat itself, but a radio is built from the artist the listener *chose*, and the tracks they have
heard by that artist are exactly what the slider is asking for. Left on, it starved the picks down
to the handful never played while the neighbours — all new, none excluded — took the rest: an 80:20
radio that came out 20:80, and refills of two tracks. Repeats across refills are handled by the
radio's own exclusion set instead.

**A stop is a quota, not a starting point.** Each half of the mix is cut to its own share before
the two are interleaved, so 100:0 is all picks and a mix that runs out of them is shorter rather
than padded with other artists. The only top-up allowed is towards the picks, when the
neighbourhood cannot fill its share.

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

## Search

**Live results are the catalogue only; the peer network is asked on submit.** A YouTube search is
one HTTPS request; a Soulseek search is a broadcast to strangers that takes seconds to answer. The
debounced path therefore asks the catalogue on every pause, and the peer search when the Soulseek
tab is the one showing — where waiting for a submit would leave the page blank under a query that
was already typed.

**Typing does not write search history.** `searchWhileTyping` passes `record = false`; only a
submitted search is remembered. A history full of "b", "bi", "bil" is worse than no history.

**The search box is the listener's while it is focused.** It used to be keyed on the committed
query, which was harmless when a search only happened on submit. With results arriving per pause,
re-keying the field would rewrite it under the finger — the visible version being a trailing space
that disappears mid-word.

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

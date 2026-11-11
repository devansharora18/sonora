# Radio — PRD

Status: implemented · Owner: Devansh
Companion to `docs/audit-2026-11-11.md`. Built in this branch; see `docs/DECISIONS.md` for the
calls made while building it.

---

## Problem

The queue can only be started from a found track. There is no way to say "play me a mix of
these artists", so a listener who knows what they are in the mood for has to find a song first and
then let Autoplay take over from it. Autoplay itself is one blend: it cannot be asked for
something new or something familiar, and it is seeded by whatever happens to be playing
(`SonoraBackend.autoplayNext`, `TasteEngine.next`). The result is that the app can continue a
mood but cannot start one.

## Users

Primary: a listener who has been using the app long enough to name three artists they are in the
mood for.
Secondary: a first-run listener who wants music now, before anything has been learned about them.
Not for: someone who wants to browse a catalogue; that is Search and Home.

## Outcome

From a cold open, a listener reaches playing music from chosen artists in three taps:
Home → Start a radio → pick artists → a mix.

## The feature

One screen. Pick one to five artists, set one slider, play it, keep it.

**Variety** is the whole control, and it is a proportion rather than a choice between named mixes.
It has five stops, and each is an exact share of the queue from the picked artists:

| Stop | Picked artists | From around them |
|---|---|---|
| 1 | 100% | 0% |
| 2 | 80% | 20% |
| 3 | 60% | 40% |
| 4 | 50% | 50% |
| 5 | 30% | 70% |

The caption under the slider prints the ratio it is on, so the position is never the only
explanation. The picked artists' own catalogue is what supplies their share; the radio seeded by
each artist supplies the rest.

| Control | What it does |
|---|---|
| Artists | 1–5 seeds, added from the listening history or by searching the catalogue |
| Variety | five stops: 100:0, 80:20, 60:40, 50:50, 30:70 |
| Familiar only | filters both sides to music played, liked or downloaded |
| Save | keeps artists + variety + familiar flag as a radio on Home and in the Library |

A saved radio stores its **ingredients**, not its tracks. Playing one rebuilds the queue from the
current catalogue and listening history; the mix therefore never goes stale, and two saved radios
that share artists but differ in variety stay two different things.

An artist picked in the UI is a **seed**: it supplies artists for the taste scoring, and it seeds a
YouTube Music radio (`YtmRadio.related`), which is the app's only source of music it does not
already know about.

## Scope

- [ ] A Radio screen reachable from Home, with a searchable artist picker.
- [ ] Selection of 1–5 artists, with the top three artists from the taste model pre-selected
      `[assumption]`.
- [ ] Three mixes, each buildable from the same selection, each showing its own count.
- [ ] Starting a mix plays it immediately and shows its name in the player.
- [ ] A mix refills itself as it nears the end, like Autoplay, and keeps its mode.
- [ ] The selection survives leaving the screen and reopening the app.
- [ ] A "Start radio" entry in a track's action sheet, seeded by that track's artist.

## Non-goals

- No radio from a playlist, an album or a song (only from artists) in v1. A radio is defined by a set
  of artists; a playlist is a different seed shape.
- No continuous "infinite station" that mutates while playing. A mix is generated and refills with
  the same mode; it does not re-pick its artists.
- No server-side personalisation and no account. Everything is local, like the taste model.
- No sharing, no saved named stations, no per-mix cover art in v1.
- No new bottom tab. The bar already carries five and divides its width by `tabs.size`
  (`FloatingTabBar.kt:158`); a sixth leaves about 50dp per tab at 360dp against 60dp today, which
  is where "Settings" and "Network" start to ellipsize.

## Where it lives

```
Home
 ├─ [Start a radio]          ← card, above the shelves
 ├─ Your radios              ← saved mixes, one tap to play
 └─ …
      ↓
Radio screen (full screen, back chevron)
 ├─ Artists: picked chips, × to remove, "+ Add another"
 ├─ Variety: slider (0…1, five stops) + end labels + a live caption
 ├─ Familiar only: switch
 ├─ [Play mix]  [Save]
 └─ Saved radios: tap to retune, play disc to start, × to delete
```

The Library's Radios section lists the same saved radios: the row retunes, the disc plays.

## Data model

```
RadioSeed(            // one picked artist
  browseId: String?,  // YouTube artist id, null for an unresolved name
  name: String,
  artworkUrl: String?,
)

RadioSetup(           // the draft, persisted
  seeds: List<RadioSeed>,   // 0..5, in pick order
  stop: Int,                // index into SHARES = [1.0, 0.8, 0.6, 0.5, 0.3]
  familiarOnly: Boolean,
)

SavedRadio(           // a kept radio
  id: String, name: String,
  seeds: List<RadioSeed>, stop: Int, familiarOnly: Boolean, createdAt: Long,
)

RadioState(           // radio.json
  draft: RadioSetup,
  saved: List<SavedRadio>,
)
```

`TasteModel.artists` is keyed by a normalised artist **name** (`TrackRef.artistKey` is built from
the display strings in `TrackRef.kt:38`), not by id, so the
suggestions list needs a name → `YtmEntity` resolution to get artwork and a `browseId`. Plan: a
small `RadioArtistDirectory` with an in-memory cache, calling `YtmCatalogSearch.artists(name)` and
taking the first hit.

## Engine

`TasteEngine.next` currently does pool construction, filtering, scoring and sampling in one
function. Radio needs the middle two with a different pool:

1. **`TasteEngine.rank(pool, cur, profile)`** — the scoring + softmax half
   of `next`, unchanged in behaviour. `next` keeps working by calling it. This is a
   behaviour-preserving refactor with the existing tests as the proof.
2. **New `backend/radio/RadioBuilder.kt`** owns the pool per mode:

   · Blend: `buildCandidates(model, cur)` + the selected artists' completed catalogues + one
     `YtmRadio.related` per seed.
   · Discover: only `YtmRadio.related` per seed, then one hop of `related` from those results,
     filtered to `plays == 0`.
   · Familiar: `buildCandidates` + local files, filtered to `plays > 0 || liked || file != null`.

3. **`RankProfile`** carries the per-mode weight overrides, so the three mixes use one scorer:
   · Familiar — `NOVELTY_BONUS = 0`, `FAMILIARITY_WEIGHT ×2`, skip penalty unchanged.
   · Discover — no familiarity term, novelty bonus always on.
   · Blend — the current weights.

4. **Seeding a radio for an artist** needs a video id. `YtmBrowse.page(artistId)` returns the
   artist's tracks; the first one seeds `YtmRadio.related`. Cached per artist for the session.

## Build

Done in this branch, one commit each: the setup model and store · `TasteEngine.rank` extracted ·
`RankProfile` · `RadioBuilder` pools · seeded-artist resolution · the Radio screen with the
picker · the variety slider and Save · saved radios on Home and in the Library · refill that keeps
the mix · the action-sheet entry.

The three named mixes were replaced by the variety slider after the first build; see
`docs/DECISIONS.md`.

## Acceptance criteria

- Given a fresh install with no history, when the listener picks three artists and presses **Play
  mix** with the slider at the middle, then at least ten tracks play and about half are the picks.
- Given any selection, when the slider is at the far left, then every track in the queue is by a
  picked artist.
- Given any selection with neighbours available, when the slider is on stop 2, then 80% of the queue
  is by a picked artist; on stop 5, 30%.
- Given a taste model with plays, when **Familiar only** is on, then every track in the queue has
  been played, liked or downloaded before.
- Given a mix is playing, when it reaches the last five tracks, then it refills with the same
  variety without changing what is already queued.
- Given a radio is saved, when Home or the Library is opened, then it is listed there, and playing
  it from either is the same mix.
- Given no network, when they tap a mix, then the screen says so and does not queue an empty player.
- Given one picked artist whose radio returns nothing, when a mix is built, then the other seeds
  still fill the queue and the empty one is named.

## Risks and open questions

1. **Where should Radio live — a Home card, or replace the Network tab?** The audit already
   questions Settings and Network as primary tabs. A radio tab would use the slot better, but
   moving Network into Settings is a separate change. *Default: Home card, no tab.*
2. **Is the selection one saved station, or re-picked each time?** *Default: one saved selection,
   edited in place.*
3. **May Discover include the picked artists at all?** *Default: at most a third.*
4. **YouTube radio stability.** `YtmRadio` is already load-bearing for Autoplay, so this adds no new
   dependency — but Discover leans on it harder (two hops). Needs the same
   "degrade to fewer tracks" behaviour it already has.
5. **Cost of seeding.** One `YtmBrowse` + one `YtmRadio` per seed, plus a second hop for Discover,
   is up to 15 requests before the first track plays. Needs a loading state and a cap
   `[assumption: cap Discover at 2 hops × 5 seeds]`.
6. **Artist name collisions** in `TasteModel.artists` — two "Nirvana"s resolve to one name key. The
   picker resolves names to ids, so the seed is right even when the model is not.

## Metrics

Primary: radio mixes started per week, and the share of a mix's tracks played past 50%.
Guardrail: autoplay skip rate must not rise — the same scorer is being reused.

# Radio — PRD

Status: draft · Owner: Devansh
Companion to `docs/audit-2026-11-11.md`. Nothing here is built yet.

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

One screen. Pick one to five artists, then choose one of three mixes built from that selection.

| Mix | What it plays | Pool | Filter |
|---|---|---|---|
| **Blended** | the picked artists, woven with what you already listen to | taste model + library + artist radios | none |
| **Discover** | artists near the selection that you have not played | artist radios, two hops out | `plays == 0`, nothing in the library |
| **Familiar only** | only music you have played, liked or downloaded | taste model + library | `plays > 0`, liked, or a local file |

The three differ in one variable — how much of the pool is new — which is what makes the choice
legible: same seed artists, three answers to "how far out?".

An artist picked in the UI is a **seed**: it supplies artists for the taste scoring, and it is the
seed for a YouTube Music radio (`YtmRadio.related`), which is already the app's only source of
music it does not already know about.

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

- No radio from a playlist, an album or a song (only from artists) in v1. The three mixes are
  defined by a set of artists; a playlist is a different seed shape.
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
 ├─ [Start a radio]  ← a card above "Recently added", only when a taste model or playlists exist
 └─ …
      ↓
Radio screen (full screen, back chevron)
 ├─ "Radio" heading, one line of explanation
 ├─ Artist chips: picked artists, × to remove, "+ Add artist"
 ├─ [Add artist] → search field + suggestions (top taste artists, then YouTube artists)
 └─ Three mix cards, each with a one-line description and a Play button:
      Blended · Discover · Familiar only
```

## Data model

```
RadioMode { BLENDED, DISCOVER, FAMILIAR }

RadioSeed(            // one picked artist
  browseId: String?,  // YouTube artist id, null for an unresolved name
  name: String,
  artworkUrl: String?,
)

RadioSetup(           // persisted; SharedPreferences or the existing Playlists store
  seeds: List<RadioSeed>,   // 0..5, order = the order the listener picked them
  mode: RadioMode,          // last used, so reopening resumes on the same tab
)

RadioSession(         // in memory, belongs to the player
  setup: RadioSetup,
  builtAt: Long,
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

1. **Extract `TasteEngine.rank(pool, cur, recent) → List<TrackRef>`** — the scoring + softmax half
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

## Steps

Each step is one commit and stops for review.

| # | Step | Size |
|---|---|---|
| 1 | `RadioMode`, `RadioSeed`, `RadioSetup`, persistence + round-trip test | small |
| 2 | Extract `TasteEngine.rank`; existing tests stay green | small |
| 3 | `RankProfile` + the three weight sets, tested on the JVM | small |
| 4 | `RadioBuilder`: blend pool, tested against a fixture model | medium |
| 5 | `RadioBuilder`: discover and familiar pools, tested | medium |
| 6 | Artist seed resolution (name → entity, artist → video id), cached | medium |
| 7 | Radio screen: picker + chips + empty state | medium |
| 8 | Three mix cards, generate, play, show the name in the player | medium |
| 9 | Refill on the same mode; Home card; action-sheet entry | medium |
| 10 | Error and empty states: no results for a seed, pool too small, offline | small |

## Acceptance criteria

- Given a fresh install with no history, when the listener picks three artists and taps **Blended**,
  then at least ten tracks play and none is a repeat of the last forty played.
- Given a taste model with plays, when they tap **Familiar only**, then every track in the queue has
  been played, liked or downloaded before.
- Given the same three artists, when they tap **Discover**, then no track in the queue is in their
  library, and at most a third of it is by the picked artists.
- Given a mix is playing, when it reaches the last five tracks, then it refills with the same mode
  without changing what is already queued.
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

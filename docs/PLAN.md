# Plan

## Part A — Radio

Exact values decided here; later steps implement them.

| Constant | Value | Why |
|---|---|---|
| `MAX_SEEDS` | 5 | five artists is the widest set that still reads as one mood |
| `DEFAULT_SEEDS` | top 3 taste artists | pre-fill, so the screen opens ready |
| `MIX_SIZE` | 40 tracks | enough for ~2½ hours; refills after |
| `REFILL_AT` | 5 left | same as Autoplay's batch trigger |
| `DISCOVER_MAX_SELECTED` | ⅓ of the mix | Discover may revisit a picked artist, not dominate |
| `DISCOVER_HOPS` | 2 | one hop off each seed, one off the results |
| `SEED_RADIO_LIMIT` | 25 | `YtmRadio.DEFAULT_LIMIT` |
| `RESOLVE_TIMEOUT_MS` | 8_000 | a seed that cannot resolve is named, not waited on |

Pools:

- **Blended** — `TasteEngine.candidates(cur)` ∪ radios of every seed ∪ library tracks whose artist
  is a seed. No filter. Current weights.
- **Discover** — radios of every seed, then one hop off the first 10 results. Filter
  `plays == 0` and key not in the library. Weights: familiarity 0, novelty 0.3, skip 0.4.
- **Familiar only** — `candidates(cur)` ∪ library tracks of seed artists. Filter
  `plays > 0 || liked || localPath != null`. No radios. Weights: familiarity ×2, novelty 0.

Steps:

1. `RadioMode`, `RadioSeed`, `RadioSetup`, `RadioStore` + round-trip test.
2. `TasteEngine.rank(pool, cur, profile)` extracted from `next`; `RankProfile` with the three
   weight sets. Existing autoplay tests stay green.
3. `RadioBuilder` with an injected `seedRadio` lambda, so pools are JVM-testable.
4. Tests: blended pool, discover filter, familiar filter, empty seed, no-network seed.
5. `RadioArtistDirectory`: name/id → artist entity → first video id, cached per session.
6. `SonoraBackend` radio state: refresh, add/remove seed, set mode, suggestions, start.
7. `RadioScreen`: heading, chips, add-artist search sheet, three mix cards.
8. Wiring: Home card, `SonoraApp` overlay, refill by mode, player name.
9. Tests for persistence and mode weights.

## Part B — Audit fixes

In the order of `docs/audit-2026-11-11.md`, one commit each:

1. Soulseek empty state (B1)
2. Search → artist back stack (B2)
3. Album/artist artwork for streamed tracks (H1)
4. Delete the dead swipe-to-queue (H2)
5. Taste back button (H3)
6. Chevron only when clickable (H4)
7. Artist page section heading (H5)
8. 48dp touch targets (H6)
9. Category tiles: crop the borrowed cover, fallback for a flat tile (H7, H9)
10. Home no longer repeats the browse grid (H8)
11. Queue header: autoplay switch, count with a noun, one autoplay tag (M1)
12. Now Playing: primary Play, grouped controls, badge contrast (M2)
13. Mini player: progress line (M3)
14. Search: Play is primary, repeated subtitles removed (M4)
15. Metadata order: `artist · album` everywhere (M5)
16. Recently played: no text-less slivers at 360dp (M6)
17. Settings: About row with version (M7)
18. Bottom scrim covers an entering heading (M9)
19. Position updates from a flow, not a 500 ms poll (M10)
20. Nits: unused `GettingStarted`, `titleSmall` drift, Connect radius

## Done

`assembleDebug` and `testDebugUnitTest` green; radio exercised on the emulator; README updated.

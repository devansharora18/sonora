# Recon — Radio feature and audit fixes

## Stack

Kotlin, Jetpack Compose, Media3, AGP 9.4.1, compileSdk 37, minSdk 26, targetSdk 35.
Single module `:app`, package `dev.sonora`. No DI framework, no ViewModel layer: a singleton
`SonoraBackend` holds `StateFlow`s and the composables collect them.

## Build and test

```bash
export JAVA_HOME="$HOME/development/android-studio/jbr"
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Emulator: `emulator-5554`, 1080×2424. `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Where the task lands

| Concern | File |
|---|---|
| Backend singleton, all `StateFlow`s | `backend/SonoraBackend.kt` (1710 lines) |
| Queue, autoplay refill | `backend/SonoraPlayer.kt` |
| Scoring, sampling, candidate pool | `backend/taste/TasteEngine.kt` |
| Persisted model | `backend/taste/TasteModel.kt`, `TasteStore.kt` |
| Track identity across sources | `backend/taste/TrackRef.kt`, `TasteTracks.kt` |
| YouTube radio endpoint | `ytm/YtmRadio.kt` |
| YouTube artist/album pages | `ytm/YtmBrowse.kt` |
| YouTube artist search | `ytm/YtmCatalogSearch.kt` |
| One-JSON-document store | `backend/JsonFile.kt` + `*Store.kt` |
| Full-screen page pattern | `ui/TasteScreen.kt` |
| Chips, cards, sheets | `ui/ChoicePills.kt`, `ui/Shelves.kt`, `ui/Components.kt` |

## Conventions to match

- A value that survives a restart is one `@Serializable` data class plus a `*Store` over `JsonFile`.
- Every store is atomic (temp + rename) and tolerates a corrupt document.
- Backend reads are `refreshX(context)` into a `MutableStateFlow`; writes are `setX(context, …)`
  that update the flow and the file.
- Pure logic lives outside Android types so it is JVM-testable: `TasteEngine` takes an injected
  clock and `Random`.
- Comments explain *why*, never *what*. No TODOs, no debug output.
- One-line commit messages, `<type>: <lowercase description>`.

## What already works for Radio

- `YtmRadio.related(videoId)` returns up to 25 tracks for a generated mix
  (`playlistId=RDAMVM<id>`), and degrades to an empty list on failure.
- `TasteEngine.next(cur, count, external)` builds a candidate pool, filters, scores, and softmax
  samples it.
- `SonoraBackend.autoplayNext` converts refs back to playable `LibraryTrack`s.
- `TasteModel.artists` already holds per-artist affinity, keyed by a normalised name.

## Risks

1. `YtmRadio` is seeded by a **video id**, not an artist id. A picked artist must be resolved to one
   of its tracks first, which costs one `YtmBrowse` call per seed.
2. `TasteModel.artists` is keyed by name, so a picked artist from the taste list needs a
   name → artist-entity lookup to get a `browseId` and artwork.
3. Discover costs up to `2 × seeds` radio calls plus one browse per seed. Needs a loading state.
4. `SonoraPlayer.play` is called from many places; the radio must not hijack refills for a queue the
   user started by hand.

## Assumptions

Recorded in `docs/DECISIONS.md` as they are made.

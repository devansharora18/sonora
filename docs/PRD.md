# PRD: Sonora — P2P Music Streaming App (Android)

**Status:** Draft v0.3 · **Owner:** TBD · **Last updated:** 2026-09-20

> v0.2 added §15 (Engineering Constraints & Decisions). v0.3 revises §1 and §7: the
> embedded-.NET backend was proven infeasible (D9) and the project has committed to a
> Kotlin-native Soulseek implementation. Decision-log entries D1, D4, D5, D7 and D8 still
> reflect the .NET architecture and are reconciled separately.

---

## 1. Summary

A native Android music player that connects to the Soulseek P2P network to search,
download, and play music. Unlike server-hosted P2P clients (e.g. slskd deployed on a
VPS), everything runs on-device: the Soulseek protocol is implemented natively in Kotlin
and runs inside an Android foreground service, in the same process as the UI. No server
infrastructure is required for a single user, and no foreign runtime is embedded.

## 2. Problem Statement

Existing Soulseek clients are either:

- **Desktop-only** (Nicotine+, SoulseekQt) — no mobile story.
- **Server/daemon-based** (slskd) — requires the user to run and maintain an always-on
  server (VPS/NAS), a high barrier for a casual mobile user.

There is no client that gives a phone-native, self-contained P2P music experience —
search, download, build a library, play — without external infrastructure.

## 3. Goals

- Ship a fully native Android app where search → download → playback all happen
  on-device.
- Run the backend (Soulseek protocol handling) in-process as a foreground service; no
  server dependency for core functionality.
- Standard music-player UX: library, playlists, now-playing, background playback,
  lock-screen controls.
- Respect P2P network norms: reshare downloaded files back to the network by default
  (matching Soulseek community expectations), configurable by the user.

## 4. Non-Goals (v1)

- **iOS.** Apple's background-execution model and App Store policy on file-sharing/P2P
  apps make an on-device daemon impractical. Out of scope until a server-backed
  architecture is considered separately (see §11).
- Multi-source P2P aggregation beyond Soulseek.
- Social features (chat, rooms, user profiles) beyond what basic transfers need.
- Desktop app.

## 5. Target User

Music enthusiasts already familiar with Soulseek-style P2P (or willing to learn) who
want:

- Access to a large, community-shared catalog, including lossless/rare files.
- A mobile-first experience without needing to self-host anything.
- Local library ownership (not a subscription-streaming model).

## 6. Core User Flows

1. **Onboarding** — enter Soulseek credentials → app starts the local backend service →
   connects to the network.
2. **Search** — query a track/artist/album → results list (file, bitrate/format, size,
   uploader, uploader's queue/speed).
3. **Download** — select a result → queued → downloaded to local storage → auto-added to
   the library.
4. **Library** — browse downloaded tracks/albums, sort/filter, basic metadata display.
5. **Playback** — queue, shuffle, repeat, background playback, lock-screen/notification
   controls, Bluetooth/media-session integration.
6. **Reshare** — downloaded files are shared back to the network while the app/service
   is running (matches Soulseek etiquette; mirrors Nicotine+/slskd).

## 7. Architecture

```
┌──────────────────────────────────────────────┐
│                 Android app                  │
│                                              │
│   Compose UI  ───────────────┐               │
│                              │  Kotlin call  │
│   Foreground Service         ▼               │
│   └─ Soulseek client (Kotlin)                │
│      ├─ server connection (TCP)              │
│      ├─ peer connections (TCP)               │
│      └─ search / transfers / sharing         │
│                                              │
│   Room DB (library)  ·  Media3 (playback)    │
└──────────────────┬───────────────────────────┘
                   │
                   ▼
          Soulseek P2P network
```

**Key decision (revised):** the backend is Kotlin and lives in the same process as the
UI, so the original "the UI never talks to C# directly" constraint no longer applies —
there is one language and one runtime. The backend is still kept behind a narrow
interface so the UI never reaches into protocol internals, but whether that interface is
a plain Kotlin API or an HTTP/WebSocket boundary is an open decision (see D10).

> **Superseded:** the original §7 embedded slskd's .NET core in the APK and had the UI
> talk to it over `localhost` HTTP. That was proven infeasible — see D9.

### 7.1 Backend (Kotlin, native Soulseek implementation)

- Implement the Soulseek protocol natively: server connection, peer connections, search,
  transfers, and file sharing.
- Run inside an Android foreground service, with a persistent notification (Android
  requirement, and good UX — "Sharing N files, downloading M").
- Handle Android lifecycle: Doze, battery optimization exemptions, service restart on
  crash.
- Persist credentials, shared folders, and download path in app-private storage, with
  credentials backed by Android Keystore.

The protocol is publicly documented and has several reference implementations to work
from — Nicotine+ (Python), slskd and Soulseek.NET (C#). See D4 for the licensing
consequences of using them as references.

### 7.2 Frontend (Kotlin / Jetpack Compose)

- Compose UI, MVVM/MVI, Kotlin Flow for reactive state.
- Media3/ExoPlayer for playback, integrated with MediaSession for lock-screen,
  Bluetooth, and Android Auto controls.
- Room DB for local library metadata.
- The app owns both the library and the transfer layer — there is no second state store
  to reconcile against (see D5).

### 7.3 Storage

- Downloads land in app-scoped storage, or a user-chosen shared-storage location via SAF
  if files need to be visible outside the app.
- Reshared files come from configured shared folders — open question whether that equals
  the download folder (see §13).

## 8. Feature Scope (MVP vs. Later)

| Feature                                            | MVP | Later |
| -------------------------------------------------- | --- | ----- |
| Search Soulseek network                            | ✅  |       |
| Download + queue management                        | ✅  |       |
| Local library (downloaded tracks)                  | ✅  |       |
| Background playback + lock-screen controls         | ✅  |       |
| Reshare downloaded files                           | ✅  |       |
| Playlists                                          | ✅  |       |
| Lyrics display                                     |     | ✅    |
| Fake-FLAC / bitrate authenticity analysis          |     | ✅    |
| Chat / private messages with peers                 |     | ✅    |
| Multiple P2P source aggregation                    |     | ✅    |
| iOS client (server-backed architecture)            |     | Reassess |

## 9. Non-Functional Requirements

- **Battery** — the foreground service must be efficient when idle (no active
  transfers). Whether to throttle/pause the connection when backgrounded is an open
  question (§13).
- **Storage** — respect device storage limits, warn before large downloads, support
  external storage where available.
- **Network** — handle Wi-Fi/mobile-data switching gracefully; consider a Wi-Fi-only
  downloads setting.
- **Reliability** — resume interrupted downloads; recover from process death without
  losing queue state.
- **Privacy/security** — local API bound to loopback only; Soulseek credentials stored
  via Android Keystore-backed encrypted storage, never plaintext.

## 10. Distribution & Compliance

- This is a P2P file-sharing client — the app is a conduit, not a content host; it
  doesn't curate or host files itself.
- **Google Play policy risk:** Play has previously removed or rejected apps that
  facilitate copyright-infringing file sharing. Decide the distribution channel early.
- Recommend an explicit in-app disclaimer placing legal responsibility for downloaded
  content on the user (as comparable projects do).
- **Not legal advice.** Given the app both downloads *and* reshares by default, a real
  legal review is warranted.

## 11. Future: iOS

Out of scope for v1. No equivalent to Android's foreground service for a persistent P2P
daemon, and App Store review has historically rejected Soulseek-style clients.

If pursued later, the likely path is server-backed: slskd runs remotely (user's own
VPS/NAS, or a hosted backend), and the iOS app is a thin client to that server's API.
Architecturally different from the on-device Android model — treat it as a separate PRD.

## 12. Success Metrics (draft)

- Search → download completion rate.
- Background service stability (crash-free sessions, service-kill rate under
  Doze/OEM battery managers).
- Retention / DAU-WAU for early testers.
- Reshare uptime (time spent contributing back to the network vs. leeching).

## 13. Open Questions

| # | Question | Status |
| - | -------- | ------ |
| 1 | Throttle/disconnect P2P when backgrounded with no active transfers, or stay connected for reshare uptime? | **Narrowed by §15 D3** — Android 15 caps `dataSync` foreground services, so "always connected" is not fully achievable. Remaining choice is how to spend the cap. |
| 2 | Play Store vs. sideload-first? | **Answered — see §15 D2.** |
| 3 | Shared-folder model: is "download folder" == "shared folder," or user-curated? | Open |
| 4 | Fork/vendor slskd's core, or track upstream? | **Reframed by §15 D4** — upstream ships an app, not a library, so this is a fork-maintenance decision. |
| 5 | Single Soulseek account per install, or account switching? | Open |
| 6 | Who reconciles finished transfers into the library if slskd's state is lost? | **New — see §15 D5.** |

## 14. Milestones (draft)

1. **Spike** — get slskd's .NET core running as an Android foreground service, confirm
   the localhost API is reachable from a bare Kotlin test app.
2. **MVP backend integration** — search + download end-to-end.
3. **MVP player** — library + playback + background/lock-screen controls.
4. **Polish** — queue-management UX, reshare settings, battery/Doze handling.
5. **Alpha distribution** — sideload/internal testing track.
6. **Distribution decision & compliance review** before wider release.

---

## 15. Engineering Constraints & Decisions

Findings from a technical review of v0.1, verified 2026-09-20. This section is the only
part of the document that commits to things; §1–14 describe intent.

### D1 — License: AGPL-3.0, not GPL-3.0 · **Decided**

Upstream `slskd` is **AGPL-3.0** (verified against the GitHub API). Sonora previously
declared GPL-3.0.

- AGPLv3 §13 technically permits combining an AGPL work with a GPLv3 work while keeping
  the GPLv3 portion under GPLv3.
- But the combined work still carries AGPL's network-use obligations for the slskd
  portion — and Sonora is itself a network service (it serves files to the Soulseek
  network), so GPL-3.0 is a poor fit regardless of how slskd is linked.

**Decision:** license Sonora under **AGPL-3.0**. `LICENSE` has been replaced accordingly.
This is the simplest and safest option, not a strictly forced one — if the project
later drops the embedded-slskd approach, GPL-3.0 becomes viable again.

Also unresolved: if `slskd` is to be distributed inside the APK, its source-offer
obligations apply and must be honored in the distribution channel.

### D2 — Distribution: sideload-first · **Decided**

Two independent reasons to avoid Play as the first channel:

1. The P2P/copyright policy risk already noted in §10.
2. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (desired in §7.1) is a **restricted permission**
   requiring a Play declaration and justification.

Sideload/internal-track first. It also removes Play's constraints on APK size and
background execution while the architecture is still being proven.

### D3 — Background execution has a hard platform ceiling · **Constraint (accepted)**

`targetSdk 34+` requires a declared foreground-service type. A P2P transfer daemon's
only plausible fit is `dataSync`.

- **Android 15 caps `dataSync` foreground services (~6 hours per 24h window)**, after
  which the service is stopped. Verify against the final `targetSdk` before designing
  around it.
- `mediaPlayback` has no such cap but only legitimately covers actual playback.

**Consequence:** the v0.1 goal of "stay connected to keep reshare uptime high" is not
fully achievable on Android 15+. Reshare uptime is best-effort and bounded. This should
be stated in the app's UX rather than designed around.

### D4 — slskd is a fork to maintain, not a dependency to consume · **Constraint (accepted)**

slskd has no reusable "core" package — it is a monolithic ASP.NET Core web app. "Package
slskd's core minus the web UI" therefore means **maintaining a fork**. Budget for
upstream divergence and periodic rebases; decide early whether to vendor a pinned
snapshot or track upstream.

Related technical unknown for the spike: ASP.NET Core (Kestrel, SignalR, and slskd's DI
graph) is reflection- and codegen-heavy, while .NET for Android Release builds
trim/AOT. **Whether this survives trimming is the spike's real question**, not whether
the runtime starts.

### D5 — Library reconciliation is undefined · **Open — needs design**

slskd owns transfer state; Room owns library metadata. If slskd's state is lost or its
config is recreated, queued/completed transfers disappear while Room still claims the
files exist — producing ghost library entries, or downloads that never reach the
library.

Pick one and document it:

- On-disk file presence is the source of truth for the library, or
- A stable transfer ID shared between slskd state and Room records.

### D6 — Onboarding is "enter credentials," not "create credentials" · **Constraint**

Soulseek accounts are created out-of-band; there is no self-registration API path. §6's
"create/enter Soulseek credentials" is really "enter existing credentials," so the
onboarding flow needs a link out to account creation.

### D7 — APK size is a first-class design input · **Constraint**

The .NET runtime baseline is tens of MB before slskd, its dependencies, or any assets.
Combined with D2 (sideload-first), this is a UX/budget concern rather than a blocker —
but it should be measured during the spike, not after.

### D8 — Cleartext to loopback is blocked by default · **Constraint (mitigated)**

Android blocks cleartext HTTP from API 28 onward, and **`127.0.0.1` is not exempt**.
Verified on API 35 at `targetSdk 35`:

```
IOException: Cleartext HTTP traffic to 127.0.0.1 not permitted
```

This breaks the PRD's core contract (§7: the UI talks to the slskd API over `localhost`
HTTP) unless the app explicitly opts in. Mitigated with a narrowly scoped
`res/xml/network_security_config.xml` permitting cleartext for `127.0.0.1` and
`localhost` only — everything else stays cleartext-blocked. A blanket
`android:usesCleartextTraffic="true"` was deliberately avoided.

If Kestrel is ever put behind HTTPS on loopback this exemption becomes unnecessary, but a
local self-signed certificate introduces its own trust-anchor problem, so plain HTTP over
loopback remains the simpler choice.

### D9 — .NET cannot be hosted inside a Kotlin app · **Decided — option B**

Spike result, 2026-09-20. Built with .NET SDK 10.0.401 and `android` workload 36.1.69.

| Test | Result |
| ---- | ------ |
| `dotnet new androidlib` + `dotnet build -c Release` | Emits `NetProbe.dll` **only** — no AAR, JAR, `.so`, or Java stubs |
| AAR target inputs (`Microsoft.Android.Sdk.AndroidLibraries.targets`) | `AndroidAsset`, resources, `AndroidEnvironment`, `AndroidJavaLibrary`, `AndroidManifest`, `EmbeddedJar`, `EmbeddedNativeLibrary`, `ProguardConfiguration` — **no managed assemblies, no runtime** |
| `dotnet new android` app APK | Contains `libmonodroid.so` (1.4 MB), `libmonosgen-2.0.so` (3.1 MB), `libxamarin-app.so`, and a 21 KB `classes.dex` |

.NET for Android is an **application** framework. The Mono runtime, the JNI bootstrap, and
the generated app glue are injected at the application level by the .NET Android SDK, into
an APK whose entry point is .NET-owned Java code. A .NET Android *library* carries only
managed IL and is consumable only by another .NET Android app (via NuGet `lib/<tfm>/`).
There is no supported path to boot the Mono runtime from a Kotlin-hosted Android `Service`.

**Therefore §7's "package slskd's core, embed it in the APK, run it inside a Kotlin-hosted
foreground service" is not achievable as written.** The rest of §7 (Kotlin/Compose UI,
Media3, Room) is unaffected.

**Options:**

- **A — Flip ownership.** Make the app a .NET Android app that owns the APK, the
  foreground service, and the P2P backend; expose the Kotlin/Compose UI as an Android
  library (AAR) it references. Keeps on-device P2P and reuses a working protocol
  implementation. Cost: the primary build system becomes MSBuild, and hosting a Compose
  AAR inside a .NET app needs its own validation spike.
- **B — Implement the protocol in Kotlin.** Drop .NET entirely; implement the Soulseek
  protocol natively (it is publicly documented; Nicotine+, slskd and Soulseek.NET are
  references). Single toolchain, smallest APK, and §7's Kotlin-side design survives
  intact — only the backend language changes. Cost: implementing server protocol, peer
  connections, search, transfers and sharing is a substantial body of work.
- **C — Two apps** (Kotlin UI + .NET backend). Rejected: two APKs, contradicts §1, and
  cross-app service control is fragile.
- **D — Server-backed.** That is §11's iOS path; rejected for Android v1, since the entire
  premise is needing no server.

**Refines D4:** `jpdillingham/Soulseek.NET` is a real, actively maintained .NET *library*
implementing the Soulseek protocol (GPL-3.0, 229★, last pushed 2026-09-17); slskd is a web
app built on top of it. So a reusable core does exist — it simply is not reachable from
Kotlin. Under option A, Soulseek.NET is the dependency and slskd need not be forked at all.

**Affects D1:** Soulseek.NET is GPL-3.0, not AGPL-3.0. If slskd leaves the picture, GPL-3.0
becomes viable again. AGPL-3.0 remains a defensible choice (Sonora is itself a network
service), but it is no longer *required*.

**Decision (2026-09-20): option B — implement the protocol in Kotlin.**

Chosen over option A to keep a single toolchain and language, avoid MSBuild as the primary
build system, produce a far smaller APK, and leave §7's Kotlin-side design intact. The cost
is owning a protocol implementation; the reference implementations above are inputs to that
work, not dependencies.

### D10 — Internal API boundary: in-process Kotlin vs. loopback HTTP · **Open**

With a Kotlin-native backend the UI and backend share a process and a language. The original
localhost HTTP contract existed only because the backend was a different runtime.

- **In-process Kotlin interface** — simpler: no serialization, no port management, no
  network policy. Makes D8 moot. Loses the ability to point the UI at a remote backend.
- **Loopback HTTP/WebSocket** — preserves §11's future iOS path (server-backed, thin client)
  and keeps a hard seam for testing. Costs a serialization layer, and keeps D8's cleartext
  exemption relevant.

Recommendation: start in-process behind a narrow interface. The seam can be introduced later
if the server-backed iOS path in §11 becomes real; it does not need designing now.

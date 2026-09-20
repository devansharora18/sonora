# PRD: Sonora — P2P Music Streaming App (Android)

**Status:** Draft v0.3 · **Owner:** TBD · **Last updated:** 2026-09-20

> v0.2 added §15 (Engineering Constraints & Decisions). v0.3 revises §1 and §7: the
> embedded-.NET backend was proven infeasible (D9) and the project has committed to a
> Kotlin-native Soulseek implementation. §11, §13, §14 and decision-log entries D1, D4, D5,
> D7 and D8 have been reconciled with that decision.

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

If pursued later, the likely path is server-backed: a Soulseek backend runs remotely (the
user's own VPS/NAS, or a hosted service), and the iOS app is a thin client to that
server's API. Architecturally different from the on-device Android model — treat it as a
separate PRD.

This is the main reason to keep the backend behind a narrow interface rather than letting
the UI reach into protocol internals — see D10.

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
| 4 | In-process Kotlin API or loopback HTTP boundary? | **New — see §15 D10.** |
| 5 | Single Soulseek account per install, or account switching? | Open |
| 6 | How much of the Soulseek protocol is required for MVP? | **New** — scope before committing to milestone 2 in §14. |

## 14. Milestones (draft)

1. ~~**Spike** — get slskd's .NET core running as an Android foreground service.~~
   **Done — infeasible.** See D9. Alongside it the Kotlin shell was built and verified:
   Gradle/Compose project, foreground service with the `dataSync` type, persistent
   notification, and loopback reachability.
2. **Protocol spike** — connect to a Soulseek server, authenticate, and run a search
   against the real network. The smallest end-to-end slice that proves the protocol work.
3. **MVP backend integration** — search + download end-to-end.
4. **MVP player** — library + playback + background/lock-screen controls.
5. **Polish** — queue-management UX, reshare settings, battery/Doze handling.
6. **Alpha distribution** — sideload/internal testing track.
7. **Distribution decision & compliance review** before wider release.

---

## 15. Engineering Constraints & Decisions

Findings from a technical review of v0.1, verified 2026-09-20. This section is the only
part of the document that commits to things; §1–14 describe intent.

### D1 — License: AGPL-3.0 · **Decided**

Sonora is licensed **AGPL-3.0**. The original rationale was that embedding slskd (AGPL-3.0)
forced it; that reasoning is now obsolete (D9), so the choice stands on its own merits:

- Sonora is itself a network service — it serves files to the Soulseek network — which is
  precisely the case AGPL's network-use clause exists for.
- It stays compatible with both slskd (AGPL-3.0) and Soulseek.NET (GPL-3.0), which matter
  as *references* for the protocol work.

**GPL-3.0 is now viable** and would be a legitimate alternative if a weaker copyleft is
preferred. Not worth churning the LICENSE file over without a reason.

**Open consequence:** if any protocol code is *ported* (rather than written from the
protocol specification) from Nicotine+ (GPL-3.0), slskd (AGPL-3.0) or Soulseek.NET
(GPL-3.0), the result is a derivative work and those terms bind the ported portion. Writing
from the published spec keeps licensing clean. Decide this per component — it is easy to
contaminate accidentally.

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

### D4 — No third-party backend; the protocol is implemented in Kotlin · **Decided**

Superseded. This originally recorded that slskd has no reusable "core", making "package
slskd's core" a fork-maintenance problem. That is moot: the project has dropped the
embedded-.NET approach entirely (D9) and implements the Soulseek protocol natively in
Kotlin.

Reference implementations to work from — inputs, not dependencies:

| Project | Language | License |
| ------- | -------- | ------- |
| Nicotine+ | Python | GPL-3.0 |
| slskd | C# (.NET) | AGPL-3.0 |
| Soulseek.NET | C# (.NET) | GPL-3.0 |

The protocol is publicly documented (Nicotine+ publishes an `SLSKPROTOCOL` reference). See
D1 for the licensing consequences of porting rather than reimplementing.

### D5 — Library reconciliation · **Simplified — mostly resolved by D9**

Originally this flagged a cross-system problem: slskd owned transfer state while Room owned
library metadata, so losing slskd's state produced ghost library entries or downloads that
never reached the library.

With a Kotlin backend there is **one state store**, so that class of divergence disappears.
What remains is ordinary local design: transfers and library records live in the same
database, and a completed transfer creates its library row in the same transaction.

One residual case still needs a rule: a file deleted outside the app (by the user, or by OS
storage cleanup) while its library row survives. Decide whether on-disk file presence is
authoritative and reconciled on scan, or the database is authoritative and missing files
are marked unavailable. Small, but it should be explicit.

### D6 — Onboarding is "enter credentials," not "create credentials" · **Constraint**

Soulseek accounts are created out-of-band; there is no self-registration API path. §6's
"create/enter Soulseek credentials" is really "enter existing credentials," so the
onboarding flow needs a link out to account creation.

### D7 — APK size · **Largely resolved by D9**

This was a real concern while a .NET runtime was to be embedded — a measured baseline of
tens of MB before any application code. Dropping .NET removes it.

Reference point from the spike: a hello-world .NET Android app with AOT produced a **7.2 MB
APK**, containing a 3.1 MB Mono runtime and a 1.4 MB JNI bridge. Sonora's current Compose
shell is **11.4 MB** with no protocol code and no Media3/Room yet, so size is worth watching
but is no longer architecture-defining.

### D8 — Cleartext to loopback is blocked by default · **Conditional on D10**

Android blocks cleartext HTTP from API 28 onward, and **`127.0.0.1` is not exempt**.
Verified on API 35 at `targetSdk 35`:

```
IOException: Cleartext HTTP traffic to 127.0.0.1 not permitted
```

This mattered because §7's original contract sent the UI to the backend over `localhost`
HTTP. **If D10 settles on an in-process Kotlin interface, this constraint disappears
entirely** — there is no HTTP hop to block.

If a loopback HTTP boundary is kept (for example to preserve the §11 server-backed path),
the mitigation is already in place: a narrowly scoped `res/xml/network_security_config.xml`
permitting cleartext for `127.0.0.1` and `localhost` only. A blanket
`android:usesCleartextTraffic="true"` was deliberately avoided.

`SonoraService` currently still serves a fixed response on `127.0.0.1:5030`. That was a
probe for this exact constraint and is expected to be deleted once D10 is settled.

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

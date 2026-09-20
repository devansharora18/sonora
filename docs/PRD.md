# PRD: Sonora — P2P Music Streaming App (Android)

**Status:** Draft v0.2 · **Owner:** TBD · **Last updated:** 2026-09-20

> v0.2 adds §15 (Engineering Constraints & Decisions), which records findings from a
> technical review of v0.1. Sections 1–14 are the original product intent, lightly
> tightened. §15 is the only section that introduces new commitments.

---

## 1. Summary

A native Android music player that connects to the Soulseek P2P network to search,
download, and play music. Unlike server-hosted P2P clients (e.g. slskd deployed on a
VPS), the backend runs on-device: the Soulseek protocol daemon (slskd, .NET) is
embedded inside the Android app process as a foreground service, and the Kotlin UI
talks to it over a local HTTP/WebSocket API (`localhost`). No server infrastructure is
required for a single user.

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
│   Kotlin UI (Compose)                        │
│        │  localhost HTTP + WebSocket         │
│        ▼                                     │
│   Foreground Service                         │
│   └─ embedded .NET runtime                   │
│      └─ slskd core (Kestrel → 127.0.0.1)     │
│                                              │
│   Room DB (library)  ·  Media3 (playback)    │
└──────────────────┬───────────────────────────┘
                   │
                   ▼
          Soulseek P2P network
```

**Key decision:** the UI layer never talks to slskd's C# code directly — no
application-level JNI bridging, no deep interop. It talks to slskd's existing
REST/WebSocket API over localhost, exactly as if slskd were a remote server. This keeps
the Kotlin and .NET codebases decoupled.

> **Scope note (v0.2):** this is one narrow native bootstrap boundary, not zero interop.
> Kotlin still has to start the .NET runtime and invoke a managed entry point. Treat
> that boundary as the single highest-risk integration point — see §15, D3.

### 7.1 Backend (.NET / embedded slskd)

- Package slskd's core (minus web-UI-specific code) via .NET for Android, embedded in
  the same APK.
- Run inside an Android foreground service (`StartForegroundService()`), with a
  persistent notification (Android requirement, and good UX — "Sharing N files,
  downloading M").
- Bind Kestrel to `127.0.0.1` only — never expose the local API beyond the device.
- Handle Android lifecycle: Doze, battery optimization exemptions, service restart on
  crash.
- Persist slskd config (credentials, shared folders, download path) in app-private
  storage.

### 7.2 Frontend (Kotlin / Jetpack Compose)

- Compose UI, MVVM/MVI, Kotlin Flow for reactive state from the local API (polling or
  WebSocket subscription for transfer progress).
- Media3/ExoPlayer for playback, integrated with MediaSession for lock-screen,
  Bluetooth, and Android Auto controls.
- Room DB for local library metadata — the app owns the music-library layer; slskd owns
  the P2P transfer layer only.
- Retrofit/OkHttp (or Ktor) client for the local API.

### 7.3 Storage

- Downloads land in app-scoped storage, or a user-chosen shared-storage location via SAF
  if files need to be visible outside the app.
- Reshared files come from slskd's configured shared folders — open question whether
  that equals the download folder (see §13).

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

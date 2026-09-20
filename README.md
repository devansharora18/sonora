# Sonora

A native Android Soulseek client where the P2P daemon runs **on the device**. Search,
download, build a library, and play — no server, no VPS, no self-hosting.

The `slskd` .NET core is embedded in the APK and runs inside an Android foreground
service, bound to `127.0.0.1`. The Kotlin/Compose UI is a localhost HTTP + WebSocket
client of that API — the two layers share no application-level interop.

> **Status: pre-alpha / design.** No application code exists yet. The specification is
> the current deliverable, and the first milestone is a spike proving the embedded
> runtime works on Android.

## Docs

- [Product requirements](docs/PRD.md) — scope, architecture, milestones, and the
  engineering constraints and decisions that shape them.

## Planned stack

| Layer | Choice |
| ----- | ------ |
| UI | Kotlin, Jetpack Compose, MVVM/MVI, Flow |
| Playback | Media3 / ExoPlayer + MediaSession |
| Library | Room |
| Local API client | Retrofit/OkHttp or Ktor |
| P2P backend | embedded `slskd` (.NET for Android), Kestrel on loopback |

## License

AGPL-3.0. See [LICENSE](LICENSE).

This is a file-sharing client, not a content host. You are responsible for what you
download and share.

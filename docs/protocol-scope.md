# Soulseek protocol — MVP scope

Which parts of the Soulseek protocol Sonora's Kotlin client must implement for MVP, and
what is deliberately excluded.

Source: Nicotine+ `SLSKPROTOCOL` reference (last updated 2026-08-27). Line/message numbers
below are that document's. This scopes milestone 2 in `docs/PRD.md` §14.

## Framing: five schemes, not one

The single most error-prone thing about this protocol is that framing differs per
connection type. Get this wrong and nothing else matters.

| Connection | Framing | Used for |
| ---------- | ------- | -------- |
| Server (`S`) | `uint32 length \| uint32 code \| payload` | login, search, peer address, status |
| Peer init | `uint32 length \| uint8 code \| payload` | opening a `P` / `F` / `D` connection |
| Peer (`P`) | `uint32 length \| uint32 code \| payload` | search results, transfer negotiation, sharing |
| Distributed (`D`) | `uint32 length \| uint8 code \| payload` | search propagation |
| File (`F`) | **no length, no code** — raw payload | file bytes |

Primitives: all integers **little-endian**; `string` = `uint32` length + bytes; `bool` = 1
byte. Connection type is a single ASCII char: `P`, `F`, or `D`. Only **one active
connection per peer** is allowed.

## MVP scope — in

### A. Connect and authenticate

| Code | Message | Dir | Why |
| ---- | ------- | --- | --- |
| S `1` | Login | send | username, password, major+minor version, MD5 hex of username+password |
| S `2` | SetWaitPort | send | advertise the port we listen on for peer connections |
| S `28` | SetStatus | send | online / away |
| S `35` | SharedFoldersFiles | send | advertise share counts (required for network citizenship) |
| S `121` | SendUploadSpeed | send | report upload speed; feeds stats |
| S `32` | ServerPing | send | keepalive — Nicotine+ uses TCP keepalive instead; evaluate |

Login response carries the greeting, our own IP, and a `bool` success flag. On failure the
server sends a rejection reason (`INVALIDUSERNAME`, `EMPTYPASS`, `INVALIDPASS`,
`INVALIDVERSION`, `SVRFULL`, `SVRPRIVATE`).

### B. Search

| Code | Message | Dir | Why |
| ---- | ------- | --- | --- |
| S `26` | FileSearch | send | token + query |
| P `9` | FileSearchResponse | recv | the result payload (see below) |
| S `3` | GetPeerAddress | send | resolve a result's uploader to an IP + port |
| S `18` | ConnectToPeer | send | indirect connection request |
| Init `1` | PeerInit | send | direct connection request |
| Init `0` | PierceFireWall | recv | response to an indirect request |

`FileSearchResponse` is the payload the results list is built from: filename, file size,
extension, and a variable attribute list (`0` bitrate, `1` duration, `2` VBR, `4` sample
rate, `5` bit depth), plus `slotfree`, `avgspeed`, and `queue length`.

`FileSearchResponse` compresses **everything after the message code** with zlib (RFC 1950)
at level 4. The length prefix covers the code plus the compressed body, and the body is
inflated before parsing.

Resolved from Nicotine+'s `slskmessages.py` rather than guessed: `make_network_message()`
returns `zlib.compress(packed_body, 4)`. Done this way because the prose reference does not
specify where the compression boundary falls.

### C. Download

The reference gives this flow explicitly:

1. Send `FileSearch` (S `26`), store the token.
2. Peers with a match open a `P` connection and send `FileSearchResponse` (P `9`).
3. To fetch a file, send `QueueUpload` (P `43`) to the peer.
4. Peer replies with `TransferRequest` (P `40`).
5. Send `TransferResponse` (P `41a`) accepting, and **store the file size**.
6. Peer opens an `F` connection.
7. Send `FileOffset` (F) with bytes already downloaded (`0` for a fresh download).
8. Peer streams file data in chunks until complete or the connection drops.
9. **The downloader closes the `F` connection** to signal completion. The uploader must not.

| Code | Message | Dir |
| ---- | ------- | --- |
| P `43` | QueueUpload | send |
| P `40` | TransferRequest | recv |
| P `41a` | TransferResponse (download) | send |
| P `46` | UploadFailed | recv |
| P `44` / P `51` | PlaceInQueueResponse / Request | recv / send |
| F | FileOffset | send |
| F | FileTransferInit | recv |
| F | (raw bytes) | recv |

Resume is supported via `FileOffset`. The reference warns explicitly against retrofitting
chunked/segmented downloading — no chunk hashing exists, and every request consumes an
upload slot.

### D. Reshare — the expensive part

Resharing is an MVP feature (PRD §8) but is the largest single chunk of protocol work,
because being *searchable* means participating in the distributed search network:

| Code | Message | Dir |
| ---- | ------- | --- |
| S `71` | HaveNoParent | send |
| S `100` | AcceptChildren | send |
| S `102` | PossibleParents | recv |
| S `126` / `127` | BranchLevel / BranchRoot | send/recv |
| S `129` / `130` | ChildDepth / ResetDistributed | send/recv |
| S `93` | EmbeddedMessage | recv |
| D `3` | DistribSearch | recv (and forward) |
| D `4` / `5` | BranchLevel / BranchRoot | send/recv |
| P `4` / `5` | SharedFileListRequest / Response | recv / send |
| P `43` | QueueUpload | recv |
| P `40` | TransferRequest (direction 1) | send |
| P `41b` | TransferResponse (upload) | recv |
| F | FileOffset | recv |
| F | (raw bytes) | send |

**This is a milestone on its own.** Search requests from other users arrive through the
distributed tree, so without distributed participation the app cannot be found and cannot
reshare. Recommend splitting reshare out of the initial spike.

## MVP scope — out

- Chat: rooms, private messages, tickers, room membership (S `13`–`17`, `22`, `23`, …)
- Recommendations and interests (S `50`–`57`, `110`–`118`)
- Wishlist searches (S `103`, `104`)
- Folder browsing and user info (P `36`/`37`, `15`/`16`)
- Legacy peer connection order — implement **modern** only
- Obfuscated connections (obfuscation type `1`) — Nicotine+ doesn't support them either
- Exact file search, room search, global user list, admin messages
- All `OBSOLETE` / `DEPRECATED` messages

## Risks and cautions

- **The reference explicitly discourages this.** It says: *"Please use existing client
  implementations when possible instead of implementing your own… The risk of introducing
  bugs that have a negative effect on the network is also high."* That is a real signal
  about the cost of option B, and worth re-reading before committing to the full protocol.
- **Client identity.** The server distinguishes clients by major/minor version. `177` is
  reserved for *experimental development and testing*; established clients have unique
  numbers. We must choose one deliberately — using a reserved number would impersonate
  another client.
- **Login has no password reset.** Credentials must be validated and stored locally.
- **First login registers the account.** Verified against the live server on 2026-09-20: a
  `Login` with an unknown username succeeds and creates the account. This is why the server
  rules forbid randomly generated usernames — junk logins create junk accounts. It also
  means onboarding can offer account creation directly (see PRD D6).
- **Zlib on search responses** — resolved (see above). Everything after the code is
  compressed; the body must be inflated before parsing.
- **Real-world wire quirks the prose spec omits.** Nicotine+'s implementation carries
  workarounds that are not in the reference document. One found so far: for files over
  2 GiB, Soulseek NS writes the top four bytes of the file size as `0xFFFFFFFF` instead of
  zeros, which would otherwise unpack as roughly 16 EiB. Expect more of these; the
  implementation is the authority where the two disagree.
- **Paths use backslash separators** on the wire, regardless of the peer's platform.

## Spike progress

1. ~~TCP connect, `Login`, parse the response~~ — **done.** Framing and MD5 confirmed
   against the live server: the returned password hash and `ownIp` both matched exactly.
2. ~~`SetWaitPort`, `SetStatus`, `SharedFoldersFiles`~~ — **done.** The server pushed six
   well-formed messages in reply (64, 83, 84, 104, 69, 160), confirming sequential frames
   parse off a single stream without desyncing.
3. **`FileSearch` → `FileSearchResponse`** — message formats implemented and unit-tested
   offline, zlib included. **Not yet proven live.** Delivery needs a peer connection, and
   this network has no inbound reachability (PRD D11), so the outbound fallback is what will
   matter: the peer's `ConnectToPeer` arrives on our server connection and we dial *out* to
   it with `PierceFireWall`. Both directions are required, not just the listener.

Download and reshare come after. If step 3 works, the rest is mechanical; if it doesn't,
nothing later matters.

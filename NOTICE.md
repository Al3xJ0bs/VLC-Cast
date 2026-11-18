# AirCast — NOTICE / attribution

AirCast is licensed under the GNU Affero General Public License v3.0
(see LICENSE). It was built from scratch for Alexey, reusing protocol
knowledge from the following open-source projects (not copying PigeonCast,
which is a closed commercial app, and not using any paid/closed SDK):

- **pyatv** (MIT, https://github.com/postlund/pyatv) — the reference AirPlay
  client. The AirPlay 2 video flow in `AirPlayVideoClient.kt`
  (pair-verify → RTSP SETUP → event channel → POST /play → /rate →
  poll /playback-info), the AirPlay 1 `/play` flow, the feature-bit
  definitions and the NTP timing packet layout follow pyatv's
  `protocols/airplay/`, `protocols/raop/protocols/airplayv1.py`,
  `airplayv2.py` and `support/timing.py`.
- **CenturyPlay** (AGPL-3.0, https://github.com/g8row/centuryplay) — the
  open-source Android/Kotlin AirPlay 2 audio app. Its HAP pairing flow
  (SRP-6a, TLV8, HAP session framing, transient pairing), the
  AirPlay2Connection transport and the event channel were adapted for video;
  the SRP-6a implementation follows its `Srp6a.kt`. Because CenturyPlay is
  AGPL-3.0, AirCast is AGPL-3.0 too.
- **doubletake** (LGPL-3.0-or-later, https://github.com/omarroth/doubletake,
  commit 5e2f1cb) — the reference for AirPlay screen mirroring and the
  FairPlay SAP handshake. The following were translated from doubletake
  into Kotlin and kept as a distinct LGPL-3.0 component
  (`app/src/main/java/com/alexey/aircast/fairplay/`, plus the mirroring
  client `MirrorClient.kt` / `MirrorClock.kt`):
  `internal/airplay/fairplay_sap.go`, `fairplay_md5.go`,
  `fairplay_message.go`, `fpsap.go`, `fpsap_tables.go`,
  `fairplay_crypto.go`, `fairplay.go` (FairPlay SAP: modified MD5,
  SAP hash, message cipher, white-box exchange, wrapping key) and the
  mirroring protocol from `internal/airplay/mirror.go`,
  `client.go`, `compatibility.go`, `event_channel.go`, `latency.go`
  (SETUP sequence, PTP media clock, frame sender, ChaCha/AES-CTR data
  cipher, heartbeat/feedback, event channel). The fpsap tables are
  ~100KB constants recovered from Apple's binary (same caveat as the
  RPiPlay playfair tables — "legal status unclear"). The Python mock
  receiver in `tests/mock_fairplay.py` is an independent translation of
  the same doubletake code, verified against the same golden vectors.
- **PiP** (repository MIT; `airplay/playfair` and `fairplay_client.c`
  LGPL-2.1, https://github.com/amitv87/PiP) — second opinion on the
  FairPlay SAP handshake and the mirroring SETUP shapes; where it
  disagreed with doubletake, doubletake (tested on AppleTV14,1) was
  followed.
- All cryptography in this app is dependency-free and written here:
  HKDF-SHA512 (javax.crypto.Mac), ChaCha20-Poly1305 (platform cipher),
  X25519/Ed25519 (pure Kotlin, `Curve25519.kt`), binary plist
  (`Bplist.kt`) — verified against RFC 5869 / RFC 8439 / RFC 7748 /
  RFC 8032 vectors and Python plistlib in `tests/`.

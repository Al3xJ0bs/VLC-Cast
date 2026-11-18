# VLC Cast — NOTICE / attribution

VLC Cast is licensed under the GNU Affero General Public License v3.0
(see LICENSE). It was built from scratch, reusing protocol knowledge
from the following open-source projects (not copying PigeonCast,
which is a closed commercial app, and not using any paid/closed SDK):

- **pyatv** (MIT, https://github.com/postlund/pyatv) — the reference AirPlay
  client. The Companion protocol (OPACK framing, Pair-Setup/Verify,
  `_launchApp`), the AirPlay 2 video flow (pair-verify → RTSP SETUP →
  event channel → POST /play → /rate → poll /playback-info), the
  AirPlay 1 `/play` flow, the feature-bit definitions and the NTP
  timing packet layout follow pyatv's `protocols/companion/`,
  `protocols/airplay/`, `protocols/raop/` and `support/`.
- **CenturyPlay** (AGPL-3.0, https://github.com/g8row/centuryplay) — the
  open-source Android/Kotlin AirPlay 2 audio app. Its HAP pairing flow
  (SRP-6a, TLV8, HAP session framing, transient pairing), the
  AirPlay2Connection transport and the event channel were adapted for
  video; the SRP-6a implementation follows its `Srp6a.kt`. Because
  CenturyPlay is AGPL-3.0, VLC Cast is AGPL-3.0 too.
- **doubletake** (LGPL-3.0-or-later, https://github.com/omarroth/doubletake,
  commit 5e2f1cb) — the reference for AirPlay screen mirroring and the
  FairPlay SAP handshake. Its mirroring protocol and FairPlay SAP
  (modified MD5, SAP hash, message cipher, white-box exchange,
  wrapping key) were used as the reference for the screen-mirroring
  implementation in v0.4.x. Mirroring was removed from the app in
  v0.6.0; the direct-cast code (`/play`, `/command`) was removed in
  v0.5.2. The Python mock receiver in `tests/mock_fairplay.py` is an
  independent translation of the same doubletake code, verified
  against the same golden vectors.
- **PiP** (repository MIT; `airplay/playfair` and `fairplay_client.c`
  LGPL-2.1, https://github.com/amitv87/PiP) — second opinion on the
  FairPlay SAP handshake and the mirroring SETUP shapes; where it
  disagreed with doubletake, doubletake (tested on Apple TV 4K) was
  followed.
- All cryptography in this app is dependency-free and written here:
  HKDF-SHA512 (javax.crypto.Mac), ChaCha20-Poly1305 (platform cipher),
  X25519/Ed25519 (pure Kotlin, `Curve25519.kt`), binary plist
  (`Bplist.kt`) — verified against RFC 5869 / RFC 8439 / RFC 7748 /
  RFC 8032 vectors and Python plistlib in `tests/`.

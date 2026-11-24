# Changelog

## 1.4.0 — 2026-11-24
Critical transport fixes, now covered by an executable engine test suite in CI:
- **TCP sequence numbers corrected**: the SYN consumed a sequence number, so server data started one byte early and every client dropped it. Data now starts at `SYN+1`.
- **SYN-ACK is sent immediately**, then the upstream socket is opened; client data is buffered, duplicates are ACKed, and connect failures return RST.
- **TCP receive-window flow control**: the relay never sends beyond the client's advertised window and waits for window updates.
- **Half-close fixed**: a client FIN shuts down upstream output only; the server response is still delivered before the final FIN.
- **Direct sockets no longer inherit a 15 s read timeout** (this reset idle/streaming connections).
- **SOCKS5 greeting fixed** for unauthenticated proxies (exact method count/length).
- **DNS-over-TCP read timeout** (5 s) so the DNS pool cannot hang.
- Added `tools/run-tests.sh`: packet checksums, TCP handshake/data/half-close, window control, DNS TTL/case/expiry. CI fails if any test fails.

## 1.3.0 — 2026-11-24
- **TTL-aware DNS cache** (256 entries, LRU): answers are cached per resolver+question, TTLs age correctly, and the query ID/question case are rewritten so strict (0x20) clients accept cached replies.
- DNS worker pool raised to 4; the pool is recreated safely on tunnel restart.
- Combined flow cap lowered to 240 (TCP+UDP admission control); UDP can no longer grow unbounded.

## 1.2.0 — 2026-11-23
- **Per-app policy editor** (tap any app): firewall on/off, block, speed limit, route, DNS.
- **Per-app VPN/proxy route**: direct, inherit global, or a saved SOCKS5/HTTP profile.
  OpenVPN/WireGuard profiles are listed and will be enforced when native support lands (P6).
- **Per-app DNS override** with validation and automatic fallback to the global resolver.
- **Unit selector** for limits: kbit/s, KB/s, Mbit/s, MB/s — global and per-app; values convert live when the unit changes.
- Legacy settings are migrated automatically to the new per-app rule model.

## 1.1.0 — 2026-11-23
- **Critical fix:** downlink TCP payload was built with a wrong buffer offset, throwing on every packet, sending RST and killing all connectivity. Fixed to offset `0`.
- IPv6 block is now controlled by the setting: when disabled, no IPv6 route is installed (helps IPv6-only carriers).
- DNS server is validated at tunnel start with a safe fallback.
- `protect()` result is checked for TCP/UDP sockets.
- Lower memory under load: 64-segment queues and a 400-flow cap.

## 1.0.1 — 2026-11-23
- Raised `minSdk` to 29 (Android 10+) per product decision; UID attribution is now always available.
- Added continuity docs: `AGENTS.md`, `docs/HANDOFF.md`, `docs/ROADMAP.md`, `docs/TESTPLAN.md`.
- CI: replaced the failing `android-actions/setup-android@v3` with the runner's bundled `sdkmanager`.
- Added Arabic launcher label (`values-ar`).
- Fixed: versionCode/versionName are now injected by `aapt2` (manifest no longer pins 1.0).
- Release now publishes the stable alias `FASGuard-latest.apk`.

## 1.0.0 — 2026-11-23
- First working build: per-app firewall, global/per-app upload+download shaping,
  SOCKS5/HTTP proxy chaining, DNS relay, IPv6 leak drop, QUIC block,
  `.ovpn/.conf/.wg` import with secure `content://` hand-off, 5-tab UI, logs, stats.
- Built and signed via GitHub Actions; rolling `latest` release.

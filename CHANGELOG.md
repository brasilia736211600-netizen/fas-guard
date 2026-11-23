# Changelog

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

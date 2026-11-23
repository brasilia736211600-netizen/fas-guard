# Changelog

## 1.0.1 — 2026-11-23
- Raised `minSdk` to 29 (Android 10+) per product decision; UID attribution is now always available.
- Added continuity docs: `AGENTS.md`, `docs/HANDOFF.md`, `docs/ROADMAP.md`, `docs/TESTPLAN.md`.
- CI: replaced the failing `android-actions/setup-android@v3` with the runner's bundled `sdkmanager`.
- Added Arabic launcher label (`values-ar`).

## 1.0.0 — 2026-11-23
- First working build: per-app firewall, global/per-app upload+download shaping,
  SOCKS5/HTTP proxy chaining, DNS relay, IPv6 leak drop, QUIC block,
  `.ovpn/.conf/.wg` import with secure `content://` hand-off, 5-tab UI, logs, stats.
- Built and signed via GitHub Actions; rolling `latest` release.

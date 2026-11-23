# FAS Guard — Roadmap

Order is intentional. Do not start a phase before the previous one is accepted. **P6 (native OpenVPN) is last by explicit user decision.**

Legend: `[ ]` todo · `[x]` done · `[~]` in progress

---

## P0 — Delivery baseline ✅ (done)

- [x] Buildable pure-Java Android project (no AndroidX/Gradle deps)
- [x] Signed APK via GitHub Actions + rolling `latest` release
- [x] Stable signing key stored in repo secrets
- [x] Continuity docs (`AGENTS.md`, `docs/HANDOFF.md`, this file, `docs/TESTPLAN.md`)
- [x] `minSdk` raised to 29, version `1.0.1` (code 2)

**Acceptance:** CI green; release contains `FASGuard-1.0.1.apk`; cert SHA-256 unchanged.

---

## Delivered early in 1.2.0 (from P2/P3 scope)

- [x] Per-app policy editor: firewall, block, speed limit, route, DNS
- [x] Per-app proxy routing (inherit/direct/profile) — SOCKS5 & HTTP enforced now
- [x] Per-app DNS override
- [x] Unit selector (kbit/s, KB/s, Mbit/s, MB/s) with live conversion

## P1 — On-device validation & triage (next)

- [x] Fixed the global-connectivity TCP offset bug (1.1.0)
- [ ] User installs `FASGuard-latest.apk` and runs `docs/TESTPLAN.md`
- [ ] Collect `adb logcat -s FASGuard AndroidRuntime` output if a test fails
- [ ] Fix every P0/P1 failure in the test plan (crash, no-traffic, leak, wrong blocking)
- [ ] Confirm per-app blocking on at least 3 apps (browser, YouTube, a system app)
- [ ] Confirm shaper actually limits (speed test before/after)
- [ ] Confirm SOCKS5 and HTTP proxies (local test server or real proxy)

**Acceptance:** all TESTPLAN "must pass" items pass on the user's device; no ANR/crash for 10 min of browsing + video.

**Note:** engine fixes belong here, not later.

---

## P2 — Engine robustness & performance

- [ ] Replace per-flow thread explosion with bounded executor / flow cap
- [ ] DNS LRU cache with TTL; negative caching optional
- [ ] TCP: receive-window tracking, MSS clamp, better FIN/RST handling, wrap-safe sequence math
- [ ] UDP: idle timeout tuning; optional UDP-over-SOCKS5 (UDP ASSOCIATE)
- [ ] Per-app byte counters + top-talkers UI
- [ ] Export/clear log; cap memory
- [ ] Battery/CPU budget check on the weak phone

**Acceptance:** 30-min soak at 1080p video + browsing with no stall; CPU not pegged; memory stable.

---

## P3 — Bilingual UI (Arabic + English) & polish

- [ ] Extract every hardcoded string from `MainActivity` into `res/values/strings.xml` (EN) + `res/values-ar/strings.xml` (AR)
- [ ] Replace programmatic literals with `getString(...)`
- [ ] Full RTL layout verification (paddings, icons, lists, dialogs)
- [ ] In-app language switch (per-app locale or `AppCompatDelegate`-free approach: `Configuration` override + recreate)
- [ ] Material-ish theming, adaptive launcher icon, empty states, accessibility labels

**Acceptance:** switching phone language changes the whole UI; no truncated/clipped text in Arabic; RTL correct.

---

## P4 — More protocols

- [ ] SOCKS4/4a
- [ ] Shadowsocks (AEAD) → exposes a local SOCKS5 to the tunnel
- [ ] Trojan / VLESS-lite via local SOCKS5 bridge (or document integration)
- [ ] SSH tunnel (optional, via JSch-equivalent; evaluation required)
- [ ] DNS-over-HTTPS and DNS-over-TLS
- [ ] IPv6 relay (or explicit "IPv6 blocked" badge in UI)

**Acceptance:** each protocol verified against a real endpoint; failures degrade gracefully with a clear log.

---

## P5 — Release hardening

- [ ] Replace `QUERY_ALL_PACKAGES` with `<queries>` where possible
- [ ] R8/ProGuard or manual shrink; reduce APK
- [ ] Versioning automation (tag `v*` + versionCode bump)
- [ ] Backup/restore settings & profiles
- [ ] F-Droid metadata (optional)
- [ ] Security review: proxy auth storage, log redaction, content provider scope

**Acceptance:** production-signed APK, reproducible CI, documented install/update path.

---

## P6 — Native OpenVPN integration (**LAST STEP**)

Goal: connect `.ovpn` **inside** FAS Guard, no external app.

- [ ] Evaluate and vendor `ics-openvpn` (GPLv2) or `openvpn3` JNI; confirm license compatibility and APK size
- [ ] Add native build to CI (NDK + CMake or prebuilt `.so` per ABI)
- [ ] Keep `VpnService` ownership: OpenVPN runs inside our service or via a second interface coordinated by us
- [ ] Import auth (certs, `auth-user-pass`, inline keys, obfs, TLS-crypt if supported)
- [ ] Map profile → runtime config; handle reconnect, DNS, routes
- [ ] Coexist with firewall + shaper + proxy chain (order matters: OpenVPN outer, FAS policy inner)
- [ ] Regression: P1 test plan still passes with an OpenVPN profile

**Acceptance:** a real `.ovpn` connects from the app with no third-party client; per-app blocking + limits still work; CI builds ABIs.

---

## Backlog (unscheduled)

- WireGuard native (wireguard-go) as a second native tunnel
- Split tunneling by domain
- Scheduled profiles / rules
- Widgets & quick-settings tile
- Import from QR/URL

# FAS Guard — Handoff Dossier (continuity file)

> Purpose: allow **any agent or human** to resume this project after a crash, disconnect or hand-off, without losing context. Update this file at the end of every work session.

- **Last updated:** 2026-11-24 (session 5 — critical TCP repair, resumable)
- **Owner account:** `brasilia736211600-netizen`
- **Repository:** https://github.com/brasilia736211600-netizen/fas-guard (public)
- **Rolling release (direct APK):** https://github.com/brasilia736211600-netizen/fas-guard/releases/latest
- **Current version:** `1.4.0` (versionCode `6`)
- **Release asset policy:** only `FASGuard-latest.apk` in the rolling release (versioned APKs are CI artifacts; tags `v1.0.1` … `v1.4.0` mark the sources)
- **minSdk / target:** 29 (Android 10) / 35
- **Signing cert SHA-256:** `d6cbd8db8aee40a088f8f388afa33fa9b5f592b4d1135db87e5c76df27000445`
- **CI status at hand-off:** ✅ green — run `45424513592`, workflow `Build APK` (commit `bd0785e`); **Engine self-tests passed before the APK build**
- **Git tags:** `v1.0.1` … `v1.4.0` (v1.4.0 = `bd0785e`, the CI-verified source)
- **Local source:** `/data/data/com.termux/files/home/FAS-Test/FasGuard`
- **Installable APK:** download from the rolling release URL above (the user does not want files written to `Downloads` by the agent)

---

## 0) TL;DR for the next agent (do this first)

1. Read `AGENTS.md`, this file, then `docs/ROADMAP.md` and `docs/TESTPLAN.md`.
   - **This session ended at the STOP POINT in §10. Resume there.**
2. `cd /data/data/com.termux/files/home/FAS-Test/FasGuard && git status && git log --oneline -5`.
3. `gh run list -R brasilia736211600-netizen/fas-guard -L 3` → confirm last build green.
4. Ask the user for the result of the **manual device test** (`docs/TESTPLAN.md`) before changing engine code.
5. Resume at the first unchecked task in `docs/ROADMAP.md`. **Do not build locally.** Push → CI → verify release (see §10).
6. Update this file (status table, commit, next task) before ending the session.

---

## 1) User decisions (locked)

| # | Decision | Consequence |
|---|---|---|
| 1 | Integrate **OpenVPN natively inside the app**, but as the **final** step | Keep delegation for now; do it only in P6 |
| 2 | Support **Android 10+ only** | `minSdk 29`; UID attribution is always available |
| 3 | **Bilingual UI (Arabic + English)** | Needs string extraction + RTL pass in P3 |
| 4 | **Direct APK installation** (sideload), not a store release | Keep GitHub release APK as delivery |
| 5 | GitHub Actions allowed and preferred for builds | Phone must never run heavy builds |
| 6 | No loss of workflow on disconnect/failure | This dossier + small atomic pushes + green CI gate |

---

## 2) Environment & constraints

- Host: **Termux on a low-end Android phone**. No Android SDK, no Gradle wrapper, limited RAM/CPU.
- Local JDK: `openjdk 21`; `aapt2`, `d8`, `apksigner`, `zipalign` available via Termux packages.
- `android.jar` (API 35) is kept for **compile checks only** at `build/local-sdk/android.jar` inside the repo (ignored by git). It was copied from the temporary SDK; if it disappears, re-copy or rely on CI.
- **Build policy:** CI only. A **javac-only** syntax check is allowed/light:
  ```bash
  mkdir -p build/check && javac -nowarn -source 11 -target 11 -encoding UTF-8 \
    -cp build/local-sdk/android.jar -d build/check \
    $(find app/src/main/java build/gen -name '*.java') && rm -rf build/check
  ```
  Never run a full APK build (`./build.sh`) on the phone.
- **File policy (user requirement):** do not create/write files under `~/storage/downloads`, `/tmp` or Termux temp dirs. Work inside this repo; deliver builds via the GitHub release URL.
- Network: GitHub reachable; `gh` authenticated with `repo` + `workflow` scopes.

---

## 3) Build & release pipeline

### GitHub Actions (primary)
`.github/workflows/build.yml`
- `ubuntu-latest` + Temurin JDK 17.
- Installs `platforms;android-35` + `build-tools;35.0.0` using the **runner's bundled sdkmanager**.
  - ⚠️ Do **not** re-add `android-actions/setup-android@v3`: it fails (`Failed to find package 'tools'`).
- Decodes signing key from secrets, then `./build.sh`.
- Uploads artifact `FASGuard-apk` and updates the rolling `latest` release.

### Repo secrets (already configured)
| Secret | Meaning |
|---|---|
| `ANDROID_KEYSTORE_B64` | base64 of `fasguard.keystore` (alias `fasguard`) |
| `ANDROID_KEYSTORE_PASS` | store password |
| `ANDROID_KEY_PASS` | key password |

If unset, the workflow generates an ephemeral key (updates then require uninstall). Do not print secret values.

### Local build (for emergencies only; normally CI)
```bash
export ANDROID_JAR="$PWD/build/local-sdk/android.jar"
./build.sh          # → dist/FASGuard-<version>.apk
```

### Install on the phone (user action)
Open the rolling release URL → download `FASGuard-latest.apk` → allow unknown sources → install. The certificate matches previous builds, so in-place updates work without uninstalling.

---

## 4) Architecture (plain Java, no AndroidX)

```
app/src/main/java/com/fas/guard/
├─ vpn/FasVpnService.java   tunnel engine: TUN, UID→package, blocking, shaping,
│                           TCP relay, UDP relay, DNS, notifications, stats
├─ net/Net.java             IPv4/IPv6 + TCP/UDP parse/build, checksums
├─ net/Proxy.java           outbound sockets; SOCKS5(+auth), HTTP CONNECT(+Basic), Config
├─ net/Routing.java         per-app route resolution: inherit/direct/SOCKS5/HTTP
├─ net/DnsCache.java        TTL-aware LRU DNS cache (256), question/ID rewrite
├─ net/TcpRelay.java        pure-Java TCP relay (handshake, window control, half-close)
├─ tools/                   executable engine tests (run in CI, no Android deps)
├─ net/Limits.java          combines global + per-app token buckets
├─ net/Rate.java            token bucket (kbit/s → bytes/s, burst 1 s)
├─ core/AppRule.java        per-app policy model (firewall/limit/unit/route/DNS) + JSON
├─ core/Prefs.java          settings + per-app rules cache/migration in SharedPreferences
├─ core/Profile.java        VPN/proxy profile model
├─ core/ProfileStore.java   import/parse .ovpn/.conf/.wg, private files
├─ core/Apps.java           installed-app catalog (label/uid/icon)
├─ data/ProfileProvider.java read-only content:// hand-off, not exported
├─ ui/MainActivity.java     programmatic UI, 5 tabs
└─ BootReceiver.java        optional auto-start
res/values{,-ar}/strings.xml, themes, vector icons
```

### Runtime flow
1. `VpnService.Builder`: `10.111.222.1/24` + `fd00:1:2::1/64`, routes `0.0.0.0/0` + `::/0`, DNS, MTU 1500, self excluded, blocking.
2. Reader thread parses each packet (`Net.parse`). IPv6 is **dropped** (leak-safe, not relayed).
3. UID: `ConnectivityManager.getConnectionOwnerUid` → package (cached per session in `uidPackages`) → `Prefs.isBlocked` → drop + counter + throttled log (1/s per package).
4. TCP: `TcpFlow` delegates to `TcpRelay`: immediate SYN-ACK, server ISN advances by 1, client data buffered from the first SYN, receive-window accounting, duplicate ACK, half-close, RST on upstream failure, 5-min reaper. `Proxy.open` uses the app's `Routing` config.
5. UDP: `UdpFlow` (protected connected `DatagramSocket`, bounded outbound queue, 60 s idle). DNS is special-cased: per-app resolver, direct UDP, or DNS-over-TCP through the app's proxy; answers are served from a TTL-aware LRU cache (256 entries) scoped by upstream route.
6. Shaping: `Limits.delay(pkg, dir, bytes)` sleeps in the per-flow thread (never in the reader).

### Security notes
- Upstream sockets and datagrams are `protect()`ed.
- The app excludes itself from the tunnel to avoid loops.
- Profile hand-off uses `content://` with a temporary URI grant and `exported="false"`.
- No network permissions beyond VPN needs; `QUERY_ALL_PACKAGES` is required to list/block apps (Play-policy caveat for a future store release).

---

## 5) Feature matrix

| Area | Status |
|---|---|
| Per-app firewall (block list) | ✅ implemented |
| Per-app policy editor (firewall/block/limit/route/DNS) | ✅ implemented |
| Per-app VPN/proxy route (inherit/direct/SOCKS5/HTTP) | ✅ implemented (VPN profiles: P6) |
| Per-app DNS override | ✅ implemented |
| Global + per-app up/down shaping | ✅ implemented |
| Units: kbit/s, KB/s, Mbit/s, MB/s (live conversion) | ✅ implemented |
| IPv6 leak protection | ✅ (drop) |
| QUIC blocking (optional) | ✅ |
| DNS relay (UDP / TCP-over-proxy) | ✅ + TTL-aware LRU cache (256) |
| SOCKS5 + username/password | ✅ |
| HTTP CONNECT + Basic auth | ✅ |
| Import `.ovpn` / `.conf` / `.wg` | ✅ |
| OpenVPN/WireGuard **tunnel** | ⚠️ delegated to official apps |
| Shadowsocks/Trojan/SSH/SOCKS4/V2Ray | ❌ |
| UDP over SOCKS5 | ❌ |
| IPv6 relay | ❌ |
| Per-app live traffic stats | ❌ (totals only) |
| Root mode | ❌ |
| Arabic UI / RTL | ❌ (planned P3) |

---

## 6) Known issues & risks

| ID | Severity | Issue | Where | Next action |
|---|---|---|---|---|
| K1 | **High** | Never tested on a physical device | — | Run `docs/TESTPLAN.md`, collect `adb logcat` |
| K2 | Low | No TCP retransmission/TIME_WAIT/window-scale; flow control prevents window overrun and TUN is lossless | `TcpRelay` | Revisit only if device logs show drops; see `docs/ENGINEERING.md` |
| K3 | Med | Per-flow threads; many flows cost CPU/battery on weak phones | `FasVpnService` | Cap lowered to 240 (TCP+UDP) + per-session UID cache; shared executor remains P2 |
| K4 | **Fixed** | — | `DnsCache` / `resolve()` | 256-entry TTL-aware LRU; route-scoped keys; short TTLs never extended |
| K5 | Low | Blocked log is throttled per package, not per connection | `handlePacket` | Fine for now |
| K6 | Low | `QUERY_ALL_PACKAGES` may block Play distribution | manifest | `<queries>` / F-Droid build in P5 |
| K7 | Low | UI strings hardcoded (not bilingual yet) | `MainActivity` | P3 string extraction |
| K8 | Info | `BootReceiver` auto-start requires prior VPN consent | `BootReceiver` | Documented |
| K9 | Fixed | versionCode/Name were pinned in the manifest and ignored `aapt2` flags | `AndroidManifest.xml` | Fixed in 1.0.1; inject via `build.sh` only |
| K10 | **Fixed** | Two independent connection killers: (a) downlink payload offset wrong (1.1.0); (b) SYN did not consume a sequence number, so all server data was one byte early and clients discarded it (1.4.0) | `TcpRelay` | Regression test `tools/TcpRelayTest` fails if either invariant is broken |
| K14 | **Fixed** | Direct upstream sockets kept a 15 s read timeout, resetting idle/streaming connections | `Proxy.open` | Fixed in 1.4.0 (`SO_TIMEOUT=0` after connect) |
| K15 | **Fixed** | Unauthenticated SOCKS5 greeting declared 1 method but sent 2 bytes | `Proxy.socks5` | Fixed in 1.4.0; exact RFC 1928 framing |
| K16 | Info | No MSS option/clamp and no window-scale negotiation; baseline TCP only | `Net/TcpRelay` | Usually harmless (1400-byte chunks, unscaled 65535 window); add if device evidence demands |
| K11 | Info | Per-app VPN profiles are selectable but fall back to global until P6 | `Routing` | Documented in UI |
| K12 | Info | Per-app limits/routes/DNS are snapshotted at tunnel start; firewall changes apply live | `FasVpnService` | Reconnect after changing shaping/routing/DNS; documented in UI |
| K13 | Info | `AndroidManifest` must not pin versionCode/Name; `build.sh` injects them | `AndroidManifest.xml` | Keep pinned values out; verify with `aapt2` after CI |

---

## 7) Roadmap pointer

Phases, tasks, acceptance criteria and estimates live in **`docs/ROADMAP.md`**. Strict order:
P1 device validation → P2 engine robustness → P3 bilingual UI → P4 protocols → P5 release hardening → **P6 native OpenVPN (last)**.

---

## 8) Resume protocol

When resuming (human or agent):
1. `git fetch && git status` — never rebase force-push `main`.
2. Check CI + release with the two `gh` commands in §0.
3. Read the **"Next task"** line below.
4. Work in small commits; push each green step; update this file in the same commit.
5. Keep the phone cool: no local builds, no big downloads, no long-running loops.

**Next task:** P1 — user installs **1.4.0** and runs `docs/TESTPLAN.md` (T1 first: browsing, video, idle keep-alive >30 s, then T15–T19). If it still fails, capture the **Logs** tab (tunnel/blocked/TCP failure lines) and the Android version/device; do not change transport code without a new failing test. P2 remainder: shared executor, per-app counters, log export.

---

## 10) STOP POINT — resume here

**State at stop (2026-11-24):**
- `main` = `bd0785e` (code) + docs commit (this update); working tree clean.
- Last CI: **green** run `45424513592` (workflow `Build APK`); the **Engine self-tests** step passed before the build.
- Rolling release asset updated `2026-11-24T15:22:15Z`; **verified** by downloading into `build/` (deleted afterwards):
  - `versionCode=6`, `versionName=1.4.0`, `minSdk=29`, `package=com.fas.guard`
  - cert SHA-256 = `d6cbd8db8aee40a088f8f388afa33fa9b5f592b4d1135db87e5c76df27000445`
  - APK SHA-256 = `8d3148a1985e7ec8ed2f32283374c74b7641dece259f8515d8fe399f9868b22c`
- Features included: per-app policy/route/DNS + units, UID cache, TTL-aware DNS cache, flow caps, **TCP sequencing/flow-control/half-close repair**, **CI engine self-tests**.
- Evidence: `TcpRelayTest` fails if the SYN+1 invariant is reverted (verified by a mutant build).

**Resume steps:**
1. `cd /data/data/com.termux/files/home/FAS-Test/FasGuard && git fetch && git status && git log --oneline -3`
2. `gh run list -R brasilia736211600-netizen/fas-guard -L 3` → confirm green.
3. Ask the user for the device test result (T1 first). If they have not installed 1.4.0, remind them of the release URL.
4. On failure: reproduce/triage from `docs/TESTPLAN.md`, fix in a small commit, push, wait for green CI, re-verify release metadata (versionCode/minSdk/cert), update this file.
5. On success: mark P1 done in `docs/ROADMAP.md`, then continue P2 (shared executor, per-app counters, log export) unless the user reprioritizes. On failure: add a failing test first, then fix; never ship transport changes without it.

**Do not:** build locally, write files to `Downloads`/temp, force-push, or start P6 (native OpenVPN) before P1–P5.

---

## 9) Session log

### Session 5 — 2026-11-24 (critical repair)
- Root-caused the persistent “internet dies”: the SYN did not consume a sequence number, so server data started at `SYN` instead of `SYN+1`; every client dropped it (DNS/UDP still worked, masking the fault).
- Extracted `TcpRelay` as a pure-Java, testable relay; fixed immediate SYN-ACK + buffering, receive-window flow control, duplicate ACK, client-FIN ACK, half-close, and per-flow sequence locking.
- Fixed direct-socket `SO_TIMEOUT=0`, SOCKS5 no-auth greeting framing, DNS-over-TCP timeout, and conditional flow-map removal.
- Added `tools/run-tests.sh` (checksums, TCP handshake/data/half-close/window, DNS TTL/case/expiry) as a **CI gate**; verified a mutant (SYN not incremented) makes it fail.
- CI `45424513592` green; release 1.4.0 metadata verified; tagged `v1.4.0`. See `docs/ENGINEERING.md` for the RCA and invariants.

### Session 4 — 2026-11-24 (stop point)
- Added `DnsCache` (TTL-aware LRU, 256 entries, route-scoped keys, short-TTL-safe, question/ID rewrite) and integrated it into `resolve()`; DNS pool 4, recreated safely per tunnel.
- Combined TCP+UDP flow cap 240 with UDP admission control.
- Verified with synthetic DNS tests (TTL aging, expiry, negative responses, case/ID rewrite) and a javac compile check.
- CI run `45413720491` green; release APK metadata re-verified; tagged `v1.3.0`.

### Session 3 — 2026-11-23 (stop point)
- Verified 1.2.0 end-to-end: CI run `45234623423` green; release asset metadata checked with `aapt2`/`apksigner` (versionCode 4, minSdk 29, cert matches).
- Tagged `v1.1.0` and `v1.2.0` at their CI-verified commits.
- Documented the stop point, resume steps, file/build policies, and the v1.2.0 feature set.
- No uncommitted code; safe to stop.

### Session 2 — 2026-11-23
- Fixed the connectivity-killing TCP offset bug (K10) → 1.1.0.
- Added the per-app policy engine + editor, per-app DNS/proxy routing, unit selectors → 1.2.0.
- Hot-path fix: UID→package names cached per tunnel session (`uidPackages`), avoiding a PackageManager call per packet; per-app proxy switch off now inherits the global route so the global proxy keeps working; UID block checks read the rule map without copying.
- Standby: awaiting the user's device retest.

### Session 1 — 2026-11-23
- Built the MVP engine + UI, then set up CI (fixed the `setup-android@v3` failure).
- Locked the six user decisions (§1); raised `minSdk` to 29.
- Fixed version injection (manifest pinning) and added stable `FASGuard-latest.apk`.
- Added continuity docs and secrets for a stable signing key.
- Handed off at: **waiting for device test results (P1)**.

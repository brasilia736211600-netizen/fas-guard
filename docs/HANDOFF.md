# FAS Guard — Handoff Dossier (continuity file)

> Purpose: allow **any agent or human** to resume this project after a crash, disconnect or hand-off, without losing context. Update this file at the end of every work session.

- **Last updated:** 2026-11-23 (session 1)
- **Owner account:** `brasilia736211600-netizen`
- **Repository:** https://github.com/brasilia736211600-netizen/fas-guard (public)
- **Rolling release (direct APK):** https://github.com/brasilia736211600-netizen/fas-guard/releases/latest
- **Current version:** `1.2.0` (versionCode `4`)
- **Release asset policy:** only `FASGuard-latest.apk` in the rolling release (versioned APKs are CI artifacts; git tag `v1.0.1` marks the source)
- **minSdk / target:** 29 (Android 10) / 35
- **Signing cert SHA-256:** `d6cbd8db8aee40a088f8f388afa33fa9b5f592b4d1135db87e5c76df27000445`
- **CI status at hand-off:** ✅ green — run `45228440422`, workflow `Build APK`
- **Git tag:** `v1.0.1` (later tags: `v1.1.0`, `v1.2.0` when published)
- **Local source:** `/data/data/com.termux/files/home/FAS-Test/FasGuard`
- **Installable APK on device:** `~/storage/downloads/FASGuard-latest.apk`

---

## 0) TL;DR for the next agent (do this first)

1. Read `AGENTS.md`, this file, then `docs/ROADMAP.md` and `docs/TESTPLAN.md`.
2. `cd /data/data/com.termux/files/home/FAS-Test/FasGuard && git status && git log --oneline -5`.
3. `gh run list -R brasilia736211600-netizen/fas-guard -L 3` → confirm last build green.
4. Ask the user for the result of the **manual device test** (`docs/TESTPLAN.md`) before changing engine code.
5. Resume at the first unchecked task in `docs/ROADMAP.md`. **Do not build locally.** Push → CI → download release.
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
- `android.jar` (API 35) was downloaded to `/data/data/com.termux/files/usr/tmp/opencode/sdk/android.jar` (temporary; may disappear after reboot — re-download `platform-35_r02.zip` if needed, or rely on CI).
- **Build policy:** CI only. A local build is possible (`./build.sh` with `ANDROID_JAR=…`) but must be avoided on the phone.
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

### Local build (avoid; for emergencies)
```bash
export ANDROID_JAR=/data/data/com.termux/files/usr/tmp/opencode/sdk/android.jar
./build.sh          # → dist/FASGuard-1.0.1.apk
```

### Install on the phone
`~/storage/downloads/FASGuard-latest.apk` → tap → allow unknown sources → install. The certificate matches previous builds, so in-place updates work.

---

## 4) Architecture (plain Java, no AndroidX)

```
app/src/main/java/com/fas/guard/
├─ vpn/FasVpnService.java   tunnel engine: TUN, UID→package, blocking, shaping,
│                           TCP relay, UDP relay, DNS, notifications, stats
├─ net/Net.java             IPv4/IPv6 + TCP/UDP parse/build, checksums
├─ net/Proxy.java           outbound sockets; SOCKS5(+auth), HTTP CONNECT(+Basic)
├─ net/Limits.java          combines global + per-app token buckets
├─ net/Rate.java            token bucket (kbit/s → bytes/s, burst 1 s)
├─ core/Prefs.java          all settings/rules/limits in SharedPreferences+JSON
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
3. UID: `ConnectivityManager.getConnectionOwnerUid` → package → `Prefs.isBlocked` → drop + counter + throttled log (1/s per package).
4. TCP: `TcpFlow` (connect via `Proxy.open`, SYN-ACK, seq/ack tracking, ACK-before-shaping, 1400-byte downlink chunks, FIN/RST, 5-min reaper).
5. UDP: `UdpFlow` (protected connected `DatagramSocket`, bounded outbound queue, 60 s idle). DNS is special-cased: direct UDP, or DNS-over-TCP through the proxy.
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
| Global + per-app up/down shaping | ✅ implemented |
| IPv6 leak protection | ✅ (drop) |
| QUIC blocking (optional) | ✅ |
| DNS relay (UDP / TCP-over-proxy) | ✅ (no cache yet) |
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
| K2 | Med | Simplified TCP stack (no window scaling/SACK, no retransmit recovery) | `TcpFlow` | Soak test; improve in P2 |
| K3 | Med | Per-flow threads; many flows cost CPU/battery on weak phones | `FasVpnService` | Shared executor / flow cap in P2 |
| K4 | Med | No DNS cache; DNS-over-TCP adds latency when proxy is on | `resolve()` | Add LRU cache + TTL in P2 |
| K5 | Low | Blocked log is throttled per package, not per connection | `handlePacket` | Fine for now |
| K6 | Low | `QUERY_ALL_PACKAGES` may block Play distribution | manifest | `<queries>` / F-Droid build in P5 |
| K7 | Low | UI strings hardcoded (not bilingual yet) | `MainActivity` | P3 string extraction |
| K8 | Info | `BootReceiver` auto-start requires prior VPN consent | `BootReceiver` | Documented |
| K9 | Fixed | versionCode/Name were pinned in the manifest and ignored `aapt2` flags | `AndroidManifest.xml` | Fixed in 1.0.1; inject via `build.sh` only |
| K10 | **Fixed** | TCP downlink payload used `off=n` instead of `0`, throwing on every data packet and resetting all connections (the reported "internet dies") | `TcpFlow.pump` | Fixed in 1.1.0; verify with T1 |
| K11 | Info | Per-app VPN profiles are selectable but fall back to global until P6 | `Routing` | Documented in UI |

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

**Next task:** P1 — user installs 1.2.0, re-runs `docs/TESTPLAN.md` (T1 first) and reports; then triage. P2 work may continue in parallel if the device is unavailable.

---

## 9) Session log

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

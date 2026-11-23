# FAS Guard — Device test plan

Target device: the user's phone (Android 10+). Install `~/storage/downloads/FASGuard-latest.apk`.
Record results as `PASS/FAIL` + a short note. For failures attach:

```bash
adb logcat -d -s FASGuard AndroidRuntime > fasguard-log.txt
adb shell dumpsys activity services com.fas.guard > fasguard-service.txt
```

(If `adb` is unavailable, use any on-device logcat app and filter `FASGuard`.)

## Preconditions
- [ ] VPN consent granted on first start.
- [ ] Notification permission granted (Android 13+).
- [ ] Mobile data or Wi-Fi is on.

## Must pass (blocking)

| ID | Test | Steps | Expected |
|---|---|---|---|
| T1 | Start tunnel | Tap power | Status `PROTECTED`, key icon in status bar, browsing works |
| T2 | Stop tunnel | Tap power again | Status `STOPPED`, key icon gone, browsing still works |
| T3 | DNS | With tunnel on, open 3 sites | Pages resolve; Logs show no DNS error storm |
| T4 | Per-app block | Apps tab → uncheck browser → open browser | Traffic blocked; Logs show `blocked <pkg>` |
| T5 | Unblock | Re-check browser | Browsing returns immediately (no reconnect) |
| T6 | Global limit | Home → download 500 kbit/s → Restart tunnel → speed test | Measured speed ≤ ~0.6 Mbit/s |
| T7 | Per-app limit | Limit one app to 200 kbit/s → Restart → use it | That app throttled, others normal |
| T8 | IPv6 leak | Visit an IPv6-only test page | No IPv6 connectivity while tunnel is on |
| T9 | QUIC block | Enable QUIC block → Restart → YouTube | Video still plays (TCP fallback); no hang |
| T10 | Proxy | Configure SOCKS5/HTTP → Test → Apply → Restart | Test says reachable; browsing goes through proxy |
| T11 | Import `.ovpn` | VPN tab → Import file | Profile listed; "Open with app" accepts the file |
| T12 | Logs | Browse for a minute | Log updates, no crash, memory stable |
| T13 | Auto-start | Enable, reboot | Tunnel returns only if VPN consent was already granted |
| T14 | Notification | While running | Shows ↑/↓ rates; Stop action works |

## Nice to have
- Airplane mode on/off while tunnel is running.
- Switch Wi-Fi ↔ mobile data; confirm flows recover.
- 20+ apps open simultaneously; UI stays responsive.
- Screen off 10 min; battery drain acceptable, tunnel alive.

## Failure triage
1. `establish() returned null` → VPN consent/permission issue, reinstall and accept.
2. No traffic at all → capture logcat; check reader thread exceptions.
3. Some apps broken → likely TCP relay edge case (K2); note app + site.
4. Device-wide slowdown → shaping/thread load (K3); reduce limits and report.

# AGENTS.md — rules for any coding agent continuing this repo

**Read `docs/HANDOFF.md` first, then `docs/ROADMAP.md`.** They are the single source of truth for state, decisions and next tasks.

## Hard rules

1. **Never run a full Android build on the user's phone.** The device is a low-end Android/Termux machine. All APK builds go through GitHub Actions (`.github/workflows/build.yml`) on push to `main`.
2. **Do not commit secrets or keystores.** Signing key lives in GitHub repo secrets. `.gitignore` already excludes `*.keystore`, `*.jks`, `build/`, `dist/`.
3. **No AndroidX, no Compose, no external dependencies.** The project intentionally uses plain `android.app`/`android.widget` Java so it builds with `aapt2 + javac + d8` only.
4. **Keep `minSdk = 29` (Android 10)** unless the user explicitly changes the decision. UID attribution depends on API 29.
5. Java 11 source level. No comments unless they add real value. Keep files focused.
6. Every push to `main` must keep CI green. Check with `gh run list -R brasilia736211600-netizen/fas-guard`.
7. **Work only inside this repo.** Do not write to `~/storage/downloads`, `/tmp`, or Termux temp dirs. Deliver APKs through the GitHub release URL; verify them by downloading into `build/` and deleting afterwards.
8. At the end of every session, update `docs/HANDOFF.md`: commit, CI run, verified release metadata, next task, and a **STOP POINT** if pausing. Leave the tree clean.

## Publish path

```bash
# from the repo root
git add -A
git commit -m "<type>: <short description>"
git push origin main
# then wait for CI and verify the rolling release
gh run list -R brasilia736211600-netizen/fas-guard -L 3
gh release view latest -R brasilia736211600-netizen/fas-guard
```

## What "done" means

A task is done only when:
- CI run for the pushed commit is green,
- `FASGuard-<version>.apk` exists in the `latest` release,
- the APK is signed with the certificate `SHA-256 d6cbd8db…00445` (see HANDOFF),
- `docs/HANDOFF.md` is updated with the new state and the next task.

## Do not

- Do not add OpenVPN native integration before the user's "last step" order (see ROADMAP P6).
- Do not touch unrelated repos under `/data/data/com.termux/files/home/FAS-Test`.
- Do not create a new GitHub repo; this one is connected.

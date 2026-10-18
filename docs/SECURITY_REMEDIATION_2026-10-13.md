# Security remediation status — 13 October 2026

Follow-up to [the store compliance review](STORE_COMPLIANCE_REVIEW_2026-10-13.md). It records what the
code now does for each finding, what was deliberately kept to preserve App²'s purpose (running and managing
custom subapps), and what still needs the owner or a store account. Nothing here is a store approval.

Verification: `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes, and the complete connected suite
(19 tests, including the new `SecurityRegressionDeviceTest`) passes on the `Medium_Phone` emulator (Android 36).
No physical device was touched and no real AI provider was called. Release minification is off, so the
ProGuard keep rule for the bridge has not been exercised.

## Findings

| ID | Fix | Where |
| --- | --- | --- |
| S1 | `addJavascriptInterface` storage objects removed. One token-gated dispatcher (`__app2NativeBridge.call(token, method, args)`) with an argument-validated allowlist. A document-start script, injected only for the subapp's own origin, recreates `AndroidStorage`, `AndroidMemory` and `AndroidNotification` with the same synchronous methods, so existing subapps run unchanged. A sandboxed or foreign frame gets no wrapper, and the raw object rejects a call without the token. If the WebView lacks document-start scripts the bridge is not exposed. | `bridge/TokenGatedBridge.kt`, `runtime/SubappWebViews.kt` |
| S2 | One shared hardened WebView set-up for the editor and the runtime. Main-frame navigation off the app origin is blocked; a deliberate tap on an http(s), mailto or tel link opens the external app instead. File and content URL access off, mixed content blocked, Safe Browsing on. A navigation that commits anyway (form POST) is reloaded back to the app, and a foreign page never receives the shim. | `runtime/SubappWebViews.kt` |
| S3 | Per-subapp grants for AI and notifications, stored outside workspaces. First use asks in a native dialog (provider, data sent, cost source); refusal fails with `permission`. AI is rate-limited to 6 starts per minute per app. Revocation cancels running AI requests, posted notifications and scheduled alarms. Grants are cleared on deletion and after a GitHub update that changes code. Library → app menu → Permissions lists and revokes them. | `grants/`, `ai/AiWebMessageBridge.kt`, `ai/AppAiManager.kt` |
| S4 | New Internet capability, off for new and imported apps. Without it the app origin serves a Content-Security-Policy restricting loads to itself, and any other host gets a logged 403 (covers scripts, images, `fetch`, XHR; the CSP covers WebSockets and frames). Known loopback, private and link-local literals are filtered even when granted; this is not complete isolation against DNS or redirect paths. | `runtime/SubappWebViews.kt` |
| S5 | GitHub import resolves the branch once to a commit SHA and reads only that revision. Shared bounded client: manual redirects limited to GitHub hosts over https, caps on entries (1024), depth (16), file size (8 MiB), total (32 MiB), session bytes and time (2 min). Staged and cleaned on failure. | `workspace/GitHubFolderImporter.kt`, `updates/GitHubClient.kt` |
| S6 | ZIP import counts every entry and the bytes of skipped entries, checks time and interruption, extracts to a staging directory and swaps by rename, restoring the old workspace on any failure. Startup recovers an interrupted swap. | `workspace/WorkspacePackageManager.kt` |
| S7 | Bridge paths: exact path-boundary containment, symlinks refused, `..`, control characters, backslashes, empty and over-long paths rejected with the failure value and a log line. Text 16 MiB, binary 6 MiB, 512 MiB per-workspace quota for bridge writes. | `workspace/BridgeWorkspaceFiles.kt` |
| S8 | Missing files return a static 404 page; the requested path is only logged. | `runtime/SubappWebViews.kt` |

## Other review items addressed

- Standard `assembleRelease` now builds an unsigned APK without credentials; publishers use `assembleSignedRelease`, which fails unless all four signing properties are set.
- Stale instrumentation tests compile again (speech-selection tests for the removed Speak action were deleted).
- Gradle wrapper distribution checksum pinned (`distributionSha256Sum`).
- Added `LICENSE` (MIT, copyright holder from git history and the README), `THIRD_PARTY_NOTICES.md` (84 resolved modules, all Apache-2.0 per POM) and `PRIVACY.md`, linked from Settings → About.

## Deliberately kept, with mitigation

- **Synchronous bridge for the subapp's own page.** Removing it would break every installed subapp. Kept, but only the app's own origin can reach it.
- **Imported apps can run arbitrary JavaScript.** That is the product. Mitigated by permissions, offline default, local-network block, and a note after import. A subapp with Internet granted can send its own data to the hosts it contacts.
- **Existing apps keep Internet** (and notifications when reminders exist) when the permission system first runs, so nothing breaks on upgrade. Only new or imported apps start offline.
- **Silent `_` mapping of unusual file-name characters** (spaces, accents) stays, otherwise files existing apps already saved would become unreachable.
- **Same-origin child frames of an app get the bridge**, as they are part of the app.

## Still open

- Explicit licence from the LingoMage owner; asset-rights review.
- AI content reporting and moderation process for Play (not built; depends on which AI features ship).
- Provider terms and payment-link review (OpenAI, OpenRouter), Play Data safety form, content rating, AAB and Play App Signing, foreground-service declaration, F-Droid `fdroiddata` metadata and signing strategy, reproducible-build check.
- Dependency verification metadata beyond the wrapper checksum.
- Editor, asset serving and bridge now share `WorkspaceFiles` path validation and symlink rejection; current ownership is documented in [ARCHITECTURE.md](ARCHITECTURE.md).
- Library import now stages and verifies copies before registration; native metadata retains the pinned import commit.
- The development server and native storage policy now share path fixtures and matching file limits. Mock/browser checks still do not establish native isolation.
- No physical-device run; older Android/WebView versions untested.

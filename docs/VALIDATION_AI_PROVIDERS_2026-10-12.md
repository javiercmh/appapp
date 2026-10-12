# OpenRouter and provider exposure validation — 2026-10-12

Implemented independent ChatGPT/OpenRouter connections and host-selected ChatGPT,
OpenRouter or Combined exposure. ChatGPT credentials and OAuth storage are unchanged.
OpenRouter defaults to explicitly zero-priced text models and refreshes eligibility
before generation. Zero prompt/completion/request price ceilings constrain upstream
routing in free mode. No App² model/provider fallback or automatic inference retry.

## Automated checks

- Checked-in Gradle 9.6.0 wrapper with repository-configured JBR 21.0.11.
- `:app:testDebugUnitTest :app:assembleDebug` passed: 50 JVM tests, zero failures.
- `node --test tests/*.mjs` passed, including starter demo provider discovery and
  save-before-acknowledgement recovery.
- `/home/melo/projects/lingomage/scripts/check.py` passed, including host exposure
  selection, existing generation/safeguard/checkpoint/reader tests and 12 Python tests.
- Mocked OpenRouter HTTP tests cover key verification without inference, explicit
  model/auth/instructions, zero-price enforcement, fresh pricing, paid opt-in,
  output normalization, truncation/refusal, malformed output, credit/quota errors,
  request cancellation and credential-safe validation of header characters.
- Registry/ledger tests cover stable unique Combined IDs, hidden calls, disconnected
  modes, stale catalogs after exposure changes, captured routing, provider-scoped
  cancellation, retained terminal results, queued cancellation and late publication.

## Medium_Phone device checks

Target: `emulator-5554`, `sdk_gphone64_x86_64`, Android 36. No physical device was
connected or updated. Replaced the existing emulator installation with the debug APK.

- Inspected AI Settings screenshots, provider connection controls, cost policy and
  ChatGPT/OpenRouter/Combined exposure dropdown. Confirmed Combined persisted across
  force-stop/relaunch.
- Three focused Android tests passed: actual WebView provider discovery, hidden
  provider rejection and exposure events; native encrypted-key recreation, absence
  of plaintext, retained corrupt record and explicit reset; imported disposable
  LingoMage Preview setup using OpenRouter and Combined with no provider selector.
- Preview staging used the packaging script's empty user-data seeds. Source/device
  `js/app.js` SHA-256 matched:
  `ad406d01a3f2a5a5d71fad1e679510a02e8219286232ab618bfb68ae6b7fc60b`.
  Instrumentation also compared imported HTML/app/bridge bytes against staged files.
  Disposable subapps were removed by test cleanup; existing subapps were not used.
- The full Android test compilation is blocked by pre-existing references to the
  removed `SpeechWebView` in `SpeechDeviceTest` and `SubappRuntimeBackDeviceTest`.
  The three AI tests were built in a disposable `/tmp` project containing copied
  current production sources and only the AI test source. No permanent build
  exclusions or changes to those unrelated tests were made.

## Browser and limitations

Inspected LingoMage Settings at 1280px desktop and 390px mobile widths, using a
seeded disposable preview and offline mock. Japanese story generation completed
both prose and reading stages and saved successfully. Existing reader/save/recovery
flows were additionally verified by LingoMage's regression suite.

No real API key was supplied and no live ChatGPT/OpenRouter inference was run.
Live account access, quota availability and model-specific story/JSON quality are
not established by these checks. Existing installed subapp workspaces are not
rewritten by an APK update; LingoMage's separate repository changes must be packaged
or published explicitly. No commits or pushes were performed.

Follow-up UI check: replaced the exposure button/dialog with a Material exposed
dropdown. Debug build passed. On Medium_Phone, verified the arrow/popup, all three
choices and persisted Combined selection, then restored ChatGPT.

Provider readiness follow-up: the dropdown now lists only connected sources and
offers Combined when both are connected. JVM cases cover zero/one/two connected
sources and retained saved modes. On Medium_Phone with no credentials, verified
“No providers connected” and disabled input/arrow with setup guidance.

## Owner-authorized phone update

Installed the signed release update with `adb install --user 0 -r` on
SM-S921B, serial `adb-R3CXC04E53K-qSdWHG._adb-tls-connect._tcp`. Confirmed the
existing user-0 package before installation. Installed/new APK SHA-256 signer
matched `d3a30c1acecc9008f11711c39013353f00fc58b540a26eba95bb3556eb02e89a`.
Release build passed with the existing signing configuration.

Before installation, exported through Settings → Back up all subapps, copied off
the phone and verified ZIP integrity and every manifest entry's size/SHA-256.
Backup contains LingoMage and Hours: 37 files, 8 data files, 13 stories and 20
working-hours entries. Retained at the task artifact directory as
`AppApp-subapps-before-update.zip` and a `.verified.json` checksum summary.

After installation, library names/order/count remained LingoMage, Hours (2 apps),
and ChatGPT remained connected. The owner began configuring OpenRouter while the
agent was checking Settings; observed “Key verified and saved.” No key material
was retrieved and the agent did not invoke inference. Post-update full data hash
comparison was not completed while the owner was using the phone. Existing
subapp code/data were not edited, imported or replaced by the agent.

## OpenRouter interface refinement

Matched the ChatGPT provider row with an icon, title, connection subtitle and
three-dot menu. The menu contains only Remove saved key. Removed the separate
OpenRouter options and pricing/limits buttons; Get a key is inside the key-entry
dialog and opens the official key page without dismissing that dialog.

Debug/release builds and 50 JVM tests passed. Medium_Phone screenshots confirmed
matching row alignment, the one-item overflow menu and the Get a key / Cancel /
Verify and save dialog actions. No key was entered or removed during these UI
checks. The UI refinement was subsequently installed on the phone; see the verified
update record below.

## Verified phone update: refined provider UI

Owner explicitly requested sending the ready build. Reconfirmed SM-S921B serial
`adb-R3CXC04E53K-qSdWHG._adb-tls-connect._tcp`, the existing user-0 package, and
matching installed/release signing certificate. Installed with `--user 0 -r`.

Fresh pre/post full-library ZIP exports were copied off the phone and independently
verified for ZIP integrity and every manifest entry's size and SHA-256. Both have
LingoMage and Hours in the same order: 37 files and 8 data files. Every data-file
hash matches before/after. Sentinels remain 13 stories, zero pending generation
checkpoints, and 20 Hours entries. Backups are retained in the task artifact
directory as `AppApp-subapps-before-ui-refinement.zip` and
`AppApp-subapps-after-ui-refinement.zip`, with verified JSON summaries.

Read-only UI confirmed both ChatGPT and OpenRouter remain Connected and the new
OpenRouter three-dot control is present. Left the phone showing provider Settings.
No subapp code/data was imported or edited; no inference or credential removal
was invoked. Signed APK SHA-256: `d2af9efb1bfa26bc31bfbfa88f9037fd73d59ae463575eefb39ba7b570fed209`.

## Free-model catalog fix

The initial filter incorrectly required an explicit per-request price. The live
public `https://openrouter.ai/api/v1/models` catalog returned 19 free text models
(including `openrouter/free` and individual `:free` variants), all advertising only
zero prompt/completion prices. The optional request field was absent, causing all
19 to be excluded.

Require explicit zero prompt/completion prices; check optional advertised charges
for zero without requiring their presence. Retain native paid opt-in, pricing
refresh before inference and zero prompt/completion/request ceilings in free mode.
A captured public-metadata fixture now covers all 19 models, and mocked generation
through the Free Models Router checks the unchanged zero-price ceilings. All 51
JVM tests and debug/release builds passed. No live inference was invoked.

Installed the matching signed release into the existing SM-S921B user-0 package
on the previously recorded serial. Fresh pre/post full-library backups verified
ZIP integrity, all manifest entries and identical hashes for all 8 data files.
App order/count remains LingoMage, Hours (2); sentinels remain 13 stories, zero
pending generation checkpoints and 20 Hours entries. Retained backups are
`AppApp-before-free-model-fix.zip` and `AppApp-after-free-model-fix.zip` in the task
artifact directory. The agent did not launch/edit a user subapp to test the list;
model-list and generation regressions used public metadata and mocked HTTP.

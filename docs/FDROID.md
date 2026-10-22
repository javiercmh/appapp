# F-Droid submission preparation

Status: **prepared, not submitted, not ready to submit.** Nothing has been sent to F-Droid. Tag `v1.7.0`
(commit `7bca71aa2155645b184b4cfcceace2c9cf5c0cde`) is untouched; the changes below are uncommitted and
only take effect in the *next* release.

## What is in the repository

| Path | Purpose |
| --- | --- |
| `app/build.gradle.kts` | `dependenciesInfo { includeInApk = false; includeInBundle = false }`: the encrypted dependency-metadata block in the APK signing block is not added. |
| `settings.gradle.kts` | The unused `org.gradle.toolchains.foojay-resolver-convention` plugin is removed (F-Droid's scanner flags it). No toolchain is configured; builds use the JDK 21 environment described in `AGENTS.md`. |
| `fastlane/metadata/android/en-US/` | `short_description.txt`, `full_description.txt`, `changelogs/12.txt` (from the 1.7.0 release notes) and `images/icon.png`. |
| `scripts/render_fdroid_icon.py` | Regenerates the store `icon.png` as an unmasked 512x512 export of `ic_launcher_background.xml` and `ic_launcher_foreground.xml`. Runs are deterministic; the output was inspected visually. |
| `THIRD_PARTY_NOTICES.md`, `AGENTS.md` | Artwork provenance section; Temurin 21 note for release builds. |
| `docs/fdroid/metadata/com.wynh.appapp.yml` | Draft fdroiddata recipe (route B), identical to what `fdroid rewritemeta` produces. |

**Screenshots:** the README now uses three fresh captures from the existing `Medium_Phone` emulator
(Android 36 / Android 16, installed App² 1.7.0 / 12), taken on 22 October 2026: library, editor and
Starter Demo runtime. They replace the older phone captures with personal status-bar details.
The library includes the emulator's existing LingoMage icon; these README images have not been added to
fastlane. Store screenshots still require a separate review and selection.

## Recipe decisions

- The single build entry is **disabled** and pins the existing v1.7.0 commit. It records the intended
  shape and the reason v1.7.0 cannot be built for F-Droid. Do not repoint it; activation adds a new entry
  for the next release.
- `AntiFeatures: NonFreeNet` (optional ChatGPT/OpenRouter AI; basis: `STORE_COMPLIANCE_REVIEW_2026-10-13.md`
  section 3 and `ai/`). No other label is claimed; final assignment is up to F-Droid maintainers.
- `UpdateCheckMode: Tags ^v[0-9]+\.[0-9]+\.[0-9]+$` ignores the `dev-N` tags. `AutoUpdateMode: None` until
  activation, then `Version`. `gradle` is a list; no `output:`.
- No Summary or Description in the recipe: F-Droid reads them from the fastlane files.

### Examples are maintained separately

Working Hours now lives at https://github.com/javiercmh/working-hours and is linked from the README.
The local `examples/` directory has been removed. The APK already contained no examples: only the
starter template ships as subapp content.

No example-removal step is needed for releases, APK builds or F-Droid preparation. The recipe has no
`rm` entry. The historical 1.7.0 build stays disabled for its existing build blockers.

## Signing: route B, existing production signature (selected by the owner, 21 October 2026)

Updates must keep working between GitHub releases and F-Droid, so F-Droid publishes the APK signed with the
production certificate `d3a30c1acecc9008f11711c39013353f00fc58b540a26eba95bb3556eb02e89a`
(`PRODUCTION_BUILDS.md`) rather than its own key. The recipe therefore sets, at app level,
`Binaries: https://github.com/javiercmh/appapp/releases/download/v%v/AppApp-%v.apk` (release naming: tag
`v1.7.0`, asset `AppApp-1.7.0.apk`) and `AllowedAPKSigningKeys` with that fingerprint. The 1.7.0 build entry
stays disabled, so neither field is exercised until a new release exists.

How it works (fdroidserver 2.4.5 `build.py` and `common.verify_apks`): F-Droid downloads the GitHub APK,
rebuilds the same commit unsigned, and publishes the GitHub APK only if the rebuild matches it apart from the
signature. A mismatch discards that version; F-Droid never signs it itself. Reproducibility is therefore a hard
requirement for any F-Droid availability, and cross-store updates are only proven by the real run below.

- Verified here: the published `AppApp-1.7.0.apk` signer, read with `fdroidserver.common.apk_signer_fingerprint`,
  is `d3a30c1a…e89a`, equal to `AllowedAPKSigningKeys`.
- Not verified: an F-Droid rebuild of any release matching its GitHub APK; production-certificate update
  compatibility on a device (a gate, no phone was touched).

### Reproducibility finding (baseline profiles)

A comparison of the published `AppApp-1.7.0.apk` with a local unsigned build (JBR 21.0.11 on Fedora, made before
the opt-out) found the same entries in the same order. Only `assets/dexopt/baseline.prof` and
`baseline.profm` differ in content (published 3940 / 274 bytes, local 3830 / 268).

- **Cause: the zlib stream, not the profile.** The profile files embed a zlib-compressed payload. Inflating the
  published and local streams gives identical payloads (`.prof` 23,236 bytes, `.profm` 563 bytes), so the
  profile content, its ordering and its dex hashes agree. Only the compressed bytes differ.
- The published streams equal the output of **Eclipse Temurin 21.0.12.1's `java.util.zip.Deflater` at level 1**
  exactly, for both files. No `Deflater` level of that JDK reproduces the local streams. Python's system
  zlib-ng (1.3.1.zlib-ng) at level 1 does reproduce the local ones and none of the published ones.
- Reading: the JDK's zlib implementation changes the output (the local JBR uses the system zlib-ng, Temurin
  bundles classic zlib). This does not prove which JDK built the published APK, or that F-Droid's Debian
  OpenJDK (expected to link classic zlib, unchecked) matches it. The JBR's own `Deflater` was not run here.
- Consequences: the baseline profiles are **kept**, with no AGP transform, because the evidence points at a
  JDK property and not at nondeterminism. Release APKs that F-Droid will compare against must be built with a
  classic-zlib JDK (Temurin 21, as `production-build.yml` uses); `AGENTS.md` now says so.
- Metadata signing block, direct parse of the published APK: pairs `0x7109871a` (v2 signature),
  `0x504b4453` (dependency metadata, 4,877 bytes) and `0x42726577` (padding). The metadata block is why
  v1.7.0 is disabled and what the `dependenciesInfo` opt-out removes. Codex reports that fdroidserver's scanner
  returns no problem for this signed APK anyway, so a clean scanner result does not show that such blocks are
  absent; a new release's signing block must be parsed directly.

## Checks run

Independently run by Codex on 21 October 2026:

- JDK 21 (`/home/melo/.jdks/jbr-21.0.11`), Gradle wrapper 9.6.0, release signing properties blank.
- Baseline before the changes: `assembleRelease`, `testDebugUnitTest`, `lintRelease` passed; 160 unit tests,
  no failures, errors or skips; lint 240 warnings, no errors. No release unit-test task exists in this project.
- After the `dependenciesInfo` change: `assembleRelease` and `lintRelease` passed.
- fdroidserver 2.4.5 `scanner --exit-code` on the actual unsigned APK passed (SDK 36 `apksigner` and `dexdump`).
  `aapt`: `com.wynh.appapp`, version 12 / 1.7.0, minSdk 26, targetSdk 37.
- After the foojay removal: `assembleRelease`, `testDebugUnitTest` and `lintRelease` passed again.
  The final unsigned APK also passed `fdroid scanner --exit-code`. An independent source scan of
  a disposable snapshot of the final tree passed in "current source state" mode, with no build
  entries so the disabled recipe could not cause a skipped scan.

Run while preparing (scratch directory outside the repository, fdroidserver 2.4.5 in a private venv, fdroiddata's
real `config/categories.yml` and `antiFeatures.yml`):

- `fdroid readmeta`, `rewritemeta` and `lint` pass for the recipe.
- Source scan of v1.7.0: **1 problem**, `Found usual suspect 'org.gradle.toolchains.foojay-resolver'` in
  `settings.gradle.kts`.
- Source scan of the current tree (v1.7.0 checkout plus the modified and new files, fdroidserver's
  "current source state" mode): **no problems**. This is a source scan only. It says nothing about the APK
  signing block (Codex's APK scan covers that) and the scanner itself deletes `gradlew` and the wrapper jar.
  A scan that skips a disabled build does not count as a pass; the recipe's only build is disabled.
- Gradle: fdroid's `gradlew-fdroid` maps AGP 9.4 to Gradle 9.6.0, and the wrapper's `distributionSha256Sum`
  equals the 9.6.0 `-bin.zip` entry in F-Droid's gradle-transparency-log.

Also run while preparing route B and the store icon:

- Recipe with `Binaries` and `AllowedAPKSigningKeys`: `rewritemeta` normalises a single key to a scalar,
  `lint` passes, and the repository copy is byte-identical to the normalised one.
- Source scan of a fresh copy of the current work tree (296 files, "current source state" mode, no build entries,
  exit code 0): no problems, with the foojay removal, the `dependenciesInfo` change and the store icon included.
- `scripts/render_fdroid_icon.py` writes the store icon (SHA-256 `d9d23c5a…f168`, 512x512, 10,665 bytes),
  identical across runs; inspected visually.
- Zlib experiment (above): Temurin 21.0.12.1 was downloaded from the official Adoptium release, its SHA-256
  matched Adoptium's published value (`ce79869e…ee94`), and it was extracted to a private scratch directory only.

**Independent route B verification by Codex:**

- Two separate clean source exports, A and B, built successfully with Temurin 21.0.12.1, using
  `--no-daemon --no-build-cache --no-configuration-cache` and explicitly empty release signing properties.
  Dependency caches were reused. Both unsigned APKs have SHA-256
  `6a3673bf7b4d64fe617de252e94d7cf595accfe3e50862143260471ff4a1a00f`.
- A third clean source export, C, had `examples/` removed and F-Droid's actual
  `common.remove_signing_keys` applied before building. It built successfully and produced the same
  SHA-256. This validates those local source-preparation changes; it is not a full F-Droid server build.
- Both compiled profile files match the published 1.7.0 profile bytes exactly. Running the same Java
  `Deflater` probe independently with JBR and Temurin confirms the compressor-output difference.
- A was then signed through `assembleSignedRelease` with a newly generated disposable test key.
  `apksigcopier compare --unsigned` verified its signature against independent build B, using
  build-tools 34.0.0 `apksigner` for verification. No production key was accessed.
- Direct parsing of the AGP test-signed APK finds only the v2 signature and padding blocks; the
  dependency-metadata ID `0x504b4453` is absent. The APK contains no examples.

**Not done:**

- A clean-cache or full F-Droid server-container `fdroid build`, and confirmation that F-Droid's buildserver has
  Android platform 37 and which JDK it selects.
- Note for any pre-release comparison: both the published and the local APK contain
  `META-INF/version-control-info.textproto`, which AGP writes from the git commit. A build from an exported
  directory without `.git` contains different commit metadata: that was the only shared ZIP entry differing
  from the published APK after using Temurin. Compare against a production APK only from a git checkout of
  the exact release commit. The local test-key proof above does not establish production reproducibility.

## Licensing and asset provenance

| Item | Evidence | Status |
| --- | --- | --- |
| Source and starter template | `LICENSE` (MIT, "Copyright (c) 2026 Javier Melo"); `README.md` says it covers the starter unless a file says otherwise | Covered. Original assets are within that MIT grant; a missing provenance note is a review follow-up, not evidence they are unlicensed. |
| Runtime libraries | `THIRD_PARTY_NOTICES.md`: 84 modules, Apache-2.0 per POM | Unchanged; since it was generated only test dependencies were added to the catalog. |
| `ic_chatgpt.xml` | Comment: "OpenAI mark from Simple Icons v14.0.0 (CC0); trademarks belong to their owners." | Copyright: CC0 per that comment (not re-checked upstream). The mark is OpenAI's trademark, which `THIRD_PARTY_NOTICES.md` ("Trademarks") says is not covered by the MIT license. |
| `ic_openrouter.xml` | One stroked arrow-shaped path, no provenance comment | **Unresolved.** Origin not recorded and unrelated to the logo statement; copyright and any trademark relationship to OpenRouter are separate questions for the owner. |
| App² logo: launcher drawables and the PNG exports derived from them | Owner's report, 21 October 2026: the App² logo was AI-generated. Tool, date and prompt are not recorded. | Recorded in `THIRD_PARTY_NOTICES.md` ("Artwork provenance"). Its copyright status is unassessed; no assurance of originality or exclusive rights is given. The MIT grant applies to the extent the owner holds rights. |
| `fastlane/.../icon.png` | Script export of the same launcher vectors | Same design, same status. |
| Other `ic_*` UI vectors, `ic_app_logo.xml` | No provenance comment | Not individually assessed; the root MIT license covers original contributions. |

A trademark on a depicted mark does not by itself make an asset non-free, so no `NonFreeAssets` label is
proposed. LingoMage is not bundled and is not needed to license the host.

## Gates before activation

1. Local verification is complete: `assembleRelease`, `lintRelease`, `testDebugUnitTest`, and F-Droid's
   source and APK scans pass after the foojay removal. This does not replace the server build below.
2. The changes above (opt-out, foojay removal, fastlane files, docs) are committed on `main` and shipped in a
   new release: version bump, `RELEASE_NOTES.md`, tag. Until then F-Droid cannot see them; update
   `changelogs/` to the new versionCode.
3. On that release's signed APK, confirm the dependency-metadata block is absent, and run `fdroid build`
   for the new entry in F-Droid's server container.
4. Open factual follow-ups, not established blockers: the recorded provenance of `ic_openrouter.xml`, and
   maintainers' view of the AI-generated logo once they have seen the provenance statement. No evidence
   found so far that either prevents inclusion.
5. Fresh emulator screenshots.
6. Route B proof: two independent clean builds in git checkouts of the release commit with Temurin 21 that match
   byte for byte; a signature-copy test with a throwaway test key through the AGP signing task; then the real
   comparison of the release's GitHub APK with an F-Droid rebuild. The release APK must be built with a
   classic-zlib JDK (see the reproducibility finding). Check the actual production signer against the allowed
   certificate; no personal-phone mutation is required for the local proof. Parse the new APK's signing
   block directly for the dependency-metadata ID `0x504b4453`.
7. Add a new build entry (`versionName`, `versionCode`, full commit hash of the new tag, `subdir: app`,
   `gradle: [yes]`), keep 1.7.0 disabled, set `AutoUpdateMode: Version`, and rerun `readmeta`, `rewritemeta`,
   `lint`, `checkupdates`, `build`.
8. Only then fork fdroiddata and open a merge request (a separate, explicitly authorised step).

## Draft merge request body (do not send before the gates)

> New app: App² (`com.wynh.appapp`)
>
> App² creates, edits and runs small HTML/CSS/JavaScript web apps ("subapps") in an Android WebView.
> Source: https://github.com/Javiercmh/appapp (MIT; see `LICENSE` and `THIRD_PARTY_NOTICES.md`). Fastlane
> metadata is in the repository.
>
> - Build: Gradle (AGP 9.4, Gradle 9.6.0, compileSdk 37, JDK 21), `subdir: app`. No bundled native libraries
>   or proprietary runtime SDKs found. The tracked `gradle-wrapper.jar` is removed by F-Droid's scanner.
>   `dependenciesInfo` is disabled and the foojay plugin removed. <build result>
> - Anti-feature: `NonFreeNet` for the optional ChatGPT/OpenRouter integration. Connecting a provider contacts
>   it for sign-in, key validation and model lists; prompts are sent only for subapps the user has allowed
>   to use AI. Subapps start with AI, notifications and Internet off.
> - Other network use: GitHub (user-initiated import and update of public repositories).
> - Subapps are user-supplied code, not reviewed by F-Droid. They run in a restricted WebView and reach the
>   host only through a native bridge limited to their own origin. No native or DEX code is loaded.
> - Signing: upstream signature kept (`Binaries` plus `AllowedAPKSigningKeys`) so GitHub and F-Droid installs
>   update each other. <reproducibility result for the release>
> - Verified: `fdroid lint`, `readmeta`, `rewritemeta`, source scanner, <APK scan and build result>.
> - Assets: the App² logo was AI-generated per the owner; tool and date unrecorded, copyright status
>   unassessed (see `THIRD_PARTY_NOTICES.md`). The ChatGPT mark is Simple Icons CC0 with OpenAI's trademark. The
>   OpenRouter icon's provenance is still unresolved.

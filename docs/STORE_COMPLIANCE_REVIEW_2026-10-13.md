# App²: F-Droid and Google Play readiness review

Review date: **13 October 2026**. This is a source and policy review with targeted Android tests, not a store approval or a complete penetration test. No application fixes or submissions were made.

## 1. Decision

**Do not submit the current build unchanged.** The web-subapp architecture is not inherently disallowed, but the current native storage bridge exposes user files to untrusted frames and external editor pages. Licensing documentation, independent release builds, and Play privacy/AI requirements also need work.

| Store | Assessment | First actions |
| --- | --- | --- |
| F-Droid main repository | Plausible candidate after remediation; not ready for a clean submission | Complete licensing, permit independent unsigned release builds, fix bridge exposure, declare external-service anti-features, validate F-Droid metadata/build |
| Google Play | JavaScript interpreter exception supports the architecture; current bridge behavior is a serious policy conflict | Remove untrusted-frame/external-page privilege, add privacy disclosures and AI reporting/safeguards, verify provider authorization and payment-link compliance, prepare AAB and declarations |

**Evidence labels:** **Requirement** means explicit official policy; **Gap** means observed implementation/documentation fails or lacks a needed control; **Concern** means a reasoned review risk rather than a final store ruling; **Unverified** means evidence is insufficient. A security test that reproduces a vulnerability counts as a successful reproduction, not a security pass.

### Source baseline

- AppApp: public GitHub `main` and clean local checkout both **44e01a1d5a1fa75e00134626db0ed7d87a9c8cc8**. [Pinned source](https://github.com/Javiercmh/appapp/tree/44e01a1d5a1fa75e00134626db0ed7d87a9c8cc8/). Package `com.wynh.appapp`, version **1.6.1 / 11**, minimum API 26, target/compile API 37.
- Latest GitHub release: **v1.6.1**, published **12 October 2026**, tag commit **ce7ac8f57d9d1352d081b1f5a9abe0aee2f14ecf**. The only difference between that tag and reviewed `main` is `AGENTS.md`; application code is identical.
- Working Hours: [source within the same AppApp commit](https://github.com/Javiercmh/appapp/tree/44e01a1d5a1fa75e00134626db0ed7d87a9c8cc8/examples/working_hours/), manifest version **1.0.0**. It is an example subapp, not a separate APK or a bundled runtime dependency.
- LingoMage: public GitHub `main` and clean `/home/melo/projects/lingomage` both **f49ef506dd622ba3e07351050742602bc79cb375**, [pinned source](https://github.com/Javiercmh/lingomage/tree/f49ef506dd622ba3e07351050742602bc79cb375/), manifest version **1.2.14**. GitHub's latest-release endpoint returned 404; this does not imply absence of tags.
- GitHub API confirmed both repositories are public and returned `license: null` for both. Local source inspection, not that heuristic alone, underpins the licensing findings.

## 2. Store policies and applicability

### F-Droid

**Requirement:** The host must be FLOSS, publicly buildable with acceptable tooling and dependencies, and legally redistributable. Proprietary SDKs cannot be excused by an anti-feature label. The current policy permits appropriately licensed prebuilts from specified trusted repositories; Google Maven hosting is not itself disqualifying. [Inclusion policy: Free Software Requirement; Build Transparency and Reproducibility](https://f-droid.org/docs/Inclusion_Policy/).

**Gap — licensing evidence:** AppApp's [README](https://github.com/Javiercmh/appapp/blob/44e01a1d5a1fa75e00134626db0ed7d87a9c8cc8/README.md#license) explicitly says MIT, but the tracked tree has no full LICENSE/COPYING or copyright notice for the project. Add the MIT text, copyright holder/year, and third-party notices; clarify coverage of the starter, Working Hours, icons and other assets. Treat the request against ads/trackers as a nonbinding preference, not an extra license condition. Do not equate an incomplete license package with proof that the host is proprietary. F-Droid's submission guide explicitly requests a FOSS license file. [Preparation checklist](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/#prepare-and-compliance-check).

LingoMage has neither a license file nor a README license declaration in the reviewed tree. Public source alone does not establish redistribution/modification rights. Its owner must explicitly license its code and assets before it is presented as a FLOSS add-on. A user importing it separately is different from F-Droid distributing it inside the host APK.

**Gap — release task:** With all four signing properties explicitly empty, compilation, release lint and packaging complete, and `app-release-unsigned.apk` exists; `assembleRelease` then fails at the custom signing guard. Make standard release assembly succeed unsigned when credentials are absent, and enforce signing in a separate publisher-only task. Do not give F-Droid the release key. A recipe patch or alternative task might work, but is unnecessary maintenance. [Build script evidence](https://github.com/Javiercmh/appapp/blob/44e01a1d5a1fa75e00134626db0ed7d87a9c8cc8/app/build.gradle.kts#L62).

**Requirement versus best practice:** F-Droid builds from source. Bit-for-bit reproducibility is encouraged, not a universal inclusion prerequisite. Matching the developer's published APK is needed for the upstream-signature route. This review did not verify that match or execute F-Droid CI. Choose the signing strategy before first publication; different store signing keys prevent normal cross-store updates. [Submission guide: reproducible builds](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/#setup-reproducible-build), [reproducible-build documentation](https://f-droid.org/docs/Reproducible_Builds/).

**User-supplied scripts:** No blanket ban on a local HTML/JavaScript interpreter was found. F-Droid explicitly requires meaningful opt-in and a bypass-of-review explanation for additional executable binary downloads. HTML/JS source packages are not the policy's binary examples; applying that exact clause to them is a reviewer interpretation, not a confirmed blanket prohibition. Nevertheless, add a clear warning that imported/updated subapps are not reviewed by F-Droid and show their capabilities before running them. Existing ZIP and GitHub imports are user initiated. [Inclusion policy: Security & Legal Compliance, item 5](https://f-droid.org/docs/Inclusion_Policy/#security--legal-compliance).

### Google Play

**Requirement:** Play's dynamic-code prohibition has an explicit interpreter exception, including JavaScript in WebView. That exception does not excuse harmful runtime behavior. No native/DEX loader or host APK self-updater was found in the reviewed production source; home-screen “Install” creates a shortcut. Subapp updates replace workspace files, not the host APK. This supports eligibility, rather than guaranteeing acceptance. [Device and network abuse, full policy](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en-GB).

**Gap — sensitive bridge:** The editor accepts external top-level navigation without removing its native bridge. Both screens expose storage through `addJavascriptInterface`, including to opaque iframes. These are demonstrated, not hypothetical. Play specifically addresses sensitive interfaces exposed to untrusted content; the interpreter exception does not override it. [Sensitive JavaScript Interface: Options 1–3](https://support.google.com/googleplay/android-developer/answer/10768383?hl=en).

**Concern — limits of remediation:** Origin checks prevent a foreign frame from calling a bridge, but an imported script runs under the app-assigned local origin. That origin does not establish the publisher's trustworthiness. Main-frame-only messaging must be combined with restricted capabilities, native consent and strong network controls. Merely displaying an “I trust this ZIP” checkbox, replacing the transport, or using HTTPS cannot guarantee compliance with the sensitive-interface guidance. Present the restricted capability design explicitly to reviewers.

**Requirement / gap — privacy:** No privacy policy or in-app privacy link was found in the reviewed resources/source. Publish one covering workspace content, logs/backups, authentication, AI prompts/responses, providers, external translation and network voices, retention/deletion and a contact. Add an in-app link and contextual disclosure/consent where data use is unexpected. Complete Data safety using actual flows. Local-only storage is distinct from provider-bound transmission. [User data: Privacy policy; Prominent disclosure and consent](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en-gb).

The Data safety guidance distinguishes developer-controlled WebViews from users browsing the open web; do not assume that all imported apps qualify for the browser exception. AppApp controls its native AI transport and its shipped starter regardless. Provider/service-provider and user-initiated-transfer exceptions require evaluation per flow; an exception from “sharing” is not automatically an exception from “collection.” No final Console answers were invented. [Data safety: collection and sharing definitions](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en-GB).

**Requirement / gap — AI-generated content:** The starter generates AI text, and LingoMage generates stories and explanations. No in-app offensive-content reporting mechanism was found in either. Implement a report action that reaches the developer without leaving the app, with preview/consent for submitted content and an operational review process. Validate prevention of prohibited output across offered models; provider refusals and LingoMage's linguistic validation alone do not establish this. The reporting rule is explicit; treatment of arbitrary privately imported AI tools is a separate reviewer concern. [AI-generated content](https://support.google.com/googleplay/android-developer/answer/13985936).

**Concern — payment links and service permission:** The host sells nothing itself, but Settings opens `https://openrouter.ai/settings/keys` and can enable paid models. A consumption-only client can be permissible; audit the complete destination and sign-in journey for routes to paid digital purchases against territory/program rules. Do not assume “bring your own key” either violates or exempts the policy. Separately verify that provider terms authorize AppApp's OAuth scopes and service usage; successful authentication is not contractual proof. [Payments FAQ: administrative links and consumption-only apps](https://support.google.com/googleplay/android-developer/answer/10281818?hl=en), [API/service abuse rule](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en-GB).

**Other applicability checks:**

- The manifest requests `SCHEDULE_EXACT_ALARM`, not restricted `USE_EXACT_ALARM`; do not apply the latter's narrow eligibility test to this build. Preserve user-granted access and refuse scheduling when absent. [Exact-alarm policy](https://support.google.com/googleplay/android-developer/answer/16558241?hl=en&rd=2).
- AI uses a `dataSync` foreground service with a Cancel notification and timeout handling. Explain why the transfer must continue, show user initiation and termination, and complete the Console declaration/video. Script-triggered requests without a native grant weaken that justification. [Foreground service use](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en-GB).
- The host offers an editor, local storage, import/export and shortcuts; it is more than an unapproved website wrapper. Describe this accurately. “WebView” alone does not establish spam. [Webviews and Affiliate Spam](https://support.google.com/googleplay/android-developer/answer/9899034?hl=en).
- It hosts web files, not other Android APKs or a simulated Android OS; the Android-container policy is not an automatic match. Reassess if that changes. Private files and Sharesheet export also do not automatically make it a public social-content platform.
- Target API 37 exceeds the current API 36 minimum for ordinary new apps/updates effective **31 August 2026**. This is not a full Android 17 compatibility test. [Target API requirements](https://developer.android.com/google/play/requirements/target-sdk).

## 3. Dependencies, services and anti-features

The host uses AndroidX/Material, Kotlin/coroutines, OkHttp/Okio, Gson and Nimbus JOSE/JWT. No Firebase, GMS, advertising SDK, telemetry SDK, bundled native `.so`, or dynamic native-code loader was identified. The only tracked JAR found is the Gradle wrapper. These observations do not replace F-Droid's scanner or a vulnerability scan.

Both debug and release runtime resolution produced **84 module components** (including platform/constraint modules). Official Google Maven/Maven Central POMs declare **Apache 2.0** for all 84, including the inherited Guava parent license. [Machine-readable inventory with POM URLs](store-review-2026-10-13/dependency-licenses.json). This is a metadata/license screening, not verification of every source archive, asset origin, notice obligation or build-plugin transitive dependency. Complete those checks and F-Droid scanning before submission.

Build inputs are versioned in Gradle/catalog files; the wrapper is **9.6.0**, AGP **9.4.0**, and compilation succeeded using **JBR/OpenJDK 21.0.11** with JVM target 17. The host shell defaults to JDK 25; it was used only for the initial version query, then explicitly replaced for builds. There is no checked-in wrapper distribution checksum or dependency verification metadata. Add verification/checksums and a documented clean build environment. Validate availability of these recent tool versions on F-Droid infrastructure instead of assuming local success proves support.

| Component / data flow | Observed behavior | F-Droid assessment |
| --- | --- | --- |
| Offline host / starter | Files remain in each workspace; starter has optional AI; no automatic analytics found | No evidence for Ads or Tracking from source inspection; not a packet-capture finding |
| ChatGPT | Native OAuth; requests to `auth.openai.com` and `api.openai.com`; prompt/instructions sent on generation; `store:false` requested | **Likely NonFreeNet**: explicitly promoted proprietary service; optionality and free pricing do not remove that concern |
| OpenRouter | User's encrypted key; catalogs/key validation and generation through `openrouter.ai/api/v1`; provider routing may involve further operators | **Likely NonFreeNet**; disclose backend routing. Free models are not necessarily libre services |
| GitHub import/update | Public API and raw-content downloads; user selects repository; ZIP alternative exists | Disclose integration; label applicability is a **reviewer concern**, not an automatic conclusion from hosting source on GitHub |
| Working Hours | Local records/settings; native reminders; no active AI/network calls found in its app logic | No independent AI anti-feature indicated by this example; unused generic wrapper methods do not prove use |
| LingoMage | Sends topics, selected text, learner profile/context and story material through the host AI API; saves results locally; speech/translation optional | Its generation depends on host-backed proprietary services; assess separately from the offline host. Missing license also needs resolution |
| TTS / translation | Configured Android speech engine may use network voices; text is sent to an external translation handler on request | No hard dependency on a particular proprietary engine found; document the selected engine/handler's privacy implications |

**Other labels:** Do not assign `NonFreeDep` merely for talking to a proprietary server. `TetheredNet` needs a dependency on an irreplaceable service, so it is not automatically established for an offline-capable host. `NonFreeAdd` could matter if promoted add-ons are non-libre; first resolve LingoMage's license. Asset rights are unverified, not proof of `NonFreeAssets`. Native shared credentials are encrypted in Keystore-backed files under `noBackupFilesDir`; user-supplied keys are not build-time secrets F-Droid must obtain. Final label assignment belongs to maintainers. [Anti-feature definitions](https://f-droid.org/docs/Anti-Features/).

## 4. Security findings and practical changes

| ID / priority | Evidence and status | Minimum change and acceptance criterion |
| --- | --- | --- |
| S1 **P0** | **Gap, reproduced:** opaque `sandbox="allow-scripts"` srcdoc iframe reads `data/audit.txt` and writes another file in both screens. `AndroidStorage` is an object; `AppAI` is undefined. [Editor registration](https://github.com/Javiercmh/appapp/blob/44e01a1d5a1fa75e00134626db0ed7d87a9c8cc8/app/src/main/java/com/wynh/appapp/MainActivity.kt#L410), [runtime registration](https://github.com/Javiercmh/appapp/blob/44e01a1d5a1fa75e00134626db0ed7d87a9c8cc8/app/src/main/java/com/wynh/appapp/SubappRuntimeActivity.kt#L118) | Remove unrestricted `addJavascriptInterface` storage/notification exposure; use exact-origin, main-frame message handlers with native capability checks. Foreign/opaque frames must neither read nor mutate workspace files. Update wrappers and bridge documentation together |
| S2 **P0** | **Gap, reproduced:** navigation from editor fixture to `https://example.com/` retains access to its `AUDIT-ONLY` file. No sentinel was transmitted to that website. Runtime blocks the same navigation. [Editor client](https://github.com/Javiercmh/appapp/blob/44e01a1d5a1fa75e00134626db0ed7d87a9c8cc8/app/src/main/java/com/wynh/appapp/MainActivity.kt#L473), [runtime client](https://github.com/Javiercmh/appapp/blob/44e01a1d5a1fa75e00134626db0ed7d87a9c8cc8/app/src/main/java/com/wynh/appapp/SubappRuntimeActivity.kt#L137) | Share one hardened runtime configuration. Block non-owning navigation inside privileged WebViews; open deliberately selected external links in a browser. Disable file/content URL access unless a narrowly justified path exists. Verify redirects, forms, frames and reloads cannot retain privileges |
| S3 **P1** | **Gap, source confirmed:** exact-origin AI checks exist, but a connected provider is usable by imported main-frame scripts without a per-subapp grant or per-request native confirmation. Notifications similarly rely on global Android permission. [AI start](https://github.com/Javiercmh/appapp/blob/44e01a1d5a1fa75e00134626db0ed7d87a9c8cc8/app/src/main/java/com/wynh/appapp/ai/AiWebMessageBridge.kt#L52), [manager](https://github.com/Javiercmh/appapp/blob/44e01a1d5a1fa75e00134626db0ed7d87a9c8cc8/app/src/main/java/com/wynh/appapp/ai/AppAiManager.kt#L137) | Native per-subapp grants with revocation; explicit provider/cost/data disclosure; no generation before approval; user-visible activity/cancellation; rate, queue and spending limits. Reconsider grants when code/publisher/capabilities change. Test with mock providers, never real billable requests |
| S4 **P1** | **Gap, source confirmed:** arbitrary HTTPS resources are not restricted by host policy and no runtime CSP is injected. A remote script executes as the local page and can use its grants. [Runtime interceptor](https://github.com/Javiercmh/appapp/blob/44e01a1d5a1fa75e00134626db0ed7d87a9c8cc8/app/src/main/java/com/wynh/appapp/SubappRuntimeActivity.kt#L142) | Default imported apps to offline; grant explicit network destinations where needed. Enforce at the host/network boundary with CSP as defense in depth; cover scripts, frames, redirects, fetch, WebSockets and service workers. Test attempted exfiltration to a controlled endpoint and private-network targets |
| S5 **P1** | **Gap, source confirmed:** GitHub initial importer recursively buffers files with no total/file/count/depth bound, follows default redirect behavior, and downloads a moving branch. The update downloader already has stronger limits and pins a SHA. [Initial importer](https://github.com/Javiercmh/appapp/blob/44e01a1d5a1fa75e00134626db0ed7d87a9c8cc8/app/src/main/java/com/wynh/appapp/workspace/GitHubFolderImporter.kt#L57), [updater](https://github.com/Javiercmh/appapp/blob/44e01a1d5a1fa75e00134626db0ed7d87a9c8cc8/app/src/main/java/com/wynh/appapp/updates/GitHubUpdateDownloader.kt#L33) | Reuse bounded streaming/staging and commit pinning for imports; validate redirect destinations; cap file count, depth, bytes and elapsed time. Test oversized responses, nested trees, redirects and branch changes with a mock server |
| S6 **P1** | **Concern:** ZIP import has canonical containment, duplicate checks and bounded extraction, but ignores metadata entries before byte accounting and replaces files with delete/copy rather than an atomic swap. [Importer](https://github.com/Javiercmh/appapp/blob/44e01a1d5a1fa75e00134626db0ed7d87a9c8cc8/app/src/main/java/com/wynh/appapp/workspace/WorkspacePackageManager.kt#L138) | Bound work for skipped entries too; use cancellation and transactional replacement; retain validated old files on failure. Test skipped-entry compression bombs with small configured limits and injected copy/disk failures |
| S7 **P2** | **Concern:** bridge paths are silently sanitized and canonical containment uses a string prefix without a separator. Direct `..` probes did not escape. This is not a demonstrated cross-subapp breach. [Path handling](https://github.com/Javiercmh/appapp/blob/44e01a1d5a1fa75e00134626db0ed7d87a9c8cc8/app/src/main/java/com/wynh/appapp/workspace/WorkspaceManager.kt#L110) | Reject invalid paths instead of rewriting; use exact path-boundary containment, prohibit links, cap native writes/reads and workspace disk use. Test sibling-prefix paths, encodings, symlinks, empty paths and oversized writes |
| S8 **P2** | **Concern:** editor missing-file HTML interpolates the requested path into markup. Exploitation was not tested. [Error response](https://github.com/Javiercmh/appapp/blob/44e01a1d5a1fa75e00134626db0ed7d87a9c8cc8/app/src/main/java/com/wynh/appapp/MainActivity.kt#L512) | Return static escaped 404 content with correct status/type; verify encoded markup cannot execute |

The demonstrated attacks reach the owning subapp's workspace, not proven access to other subapps, Android private files or encrypted provider credentials. Unique origins/directories reduce accidental sharing but are not separate Android application sandboxes. Imported code can intentionally misuse its own saved personal data; “all files local” does not mean “no exfiltration.” Android documents the all-frame exposure of `addJavascriptInterface`. [Native bridge security guidance](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges).

Preserve useful controls already present: ZIP staging/limits, scoped state/notification tags, origin/main-frame restrictions for AI/update/speech/translation, encrypted credentials, API-result ownership, update review/rollback and no provider fallback. Harden the common implementation before considering store-specific variants. An AI-free F-Droid flavor is optional, not a proven inclusion requirement; a more restricted Play flavor is a contingency if the capability design cannot satisfy review.

## 5. Verification and limits

Environment: **Medium_Phone**, Android **36**, x86_64, serial **emulator-5554**, WebView **153.0.8010.36**. No physical device was connected or changed.

| Check | Result |
| --- | --- |
| Repository debug assembly | Passed |
| Existing JVM suite in fresh temporary source snapshot | **52 tests, 11 suites; zero failures/errors/skips** |
| Existing Android test compilation | **Failed**: removed `SpeechWebView` referenced by `SubappRuntimeBackDeviceTest` and `SpeechDeviceTest` |
| Temporary audit instrumentation | **9/9 reproductions/checks passed**; production source unchanged; test suite substituted only in `/tmp` snapshot |
| Opaque iframe read/write, editor and runtime | Unsafe behavior reproduced twice |
| External editor navigation / runtime navigation block | Editor read succeeds; runtime remains at local origin |
| Cross-workspace relative-path reads | Two traversal variants return empty; control read succeeds |
| Disconnected AI start | Returns `authentication` error; no generation or paid request |
| ZIP traversal / 8 MiB + 1 entry / 1,025 entries | All rejected; existing audit sentinel preserved |
| Release assembly without signing properties | Compilation/lint/package succeed, custom assembly task fails; unsigned artifact exists |
| APK native-library inspection | No `.so` entries in debug or unsigned release APK |
| Reproducibility / F-Droid CI / Play pre-launch | **Not performed** |

The temporary source snapshot came from `git archive HEAD`; only its Android test sources were replaced. It built the actual activities and bridge, then installed them with `adb -s emulator-5554 install -r`. Tests created named disposable apps through the repository, launched both activities and used JavaScript evaluation; cleanup deleted only their own generated IDs. This was instrumentation, not manual end-to-end tapping of import/settings controls. Example repositories were inspected statically; neither Working Hours nor LingoMage was exercised on-device in this review.

Evidence and rerun instructions: [audit evidence](store-review-2026-10-13/README.md). Test fixtures use inert sentinel strings. No live provider credentials, notification spam, destructive stress test, or public exploit publication was involved. Remaining checks include controlled exfiltration, same-origin AI abuse with mocks, cross-app browser-storage tests, GitHub bounds, notification limits, crash recovery and older Android/WebView versions. These limits do not weaken the two directly reproduced bridge findings.

## 6. Submission checklists

### Shared remediation, in order

- [ ] Fix S1/S2 and rerun equivalent tests expecting denial; implement S3/S4 before claiming imported apps are isolated.
- [ ] Add complete host/example/asset licensing and dependency notices. Obtain an explicit LingoMage license from its owner; do not choose one on their behalf.
- [ ] Allow unsigned standard release builds; fix stale Android tests; document JDK/SDK/AGP and dependency verification.
- [ ] Add import/update provenance, capability review, grant revocation and a clear warning that packages are unreviewed executable web code. Keep personal data intact on failed imports/updates.
- [ ] Publish accurate privacy documentation; audit provider terms, data flows, retention and account-link journeys. Add AI reporting/safeguards if shipping generation.
- [ ] Address S5–S8 and run regression/security checks across supported Android versions.

### F-Droid main repository

- [ ] Use a tagged, publicly accessible source revision with a license file; supply maintainable build instructions and acceptable dependencies/toolchain.
- [ ] Add store descriptions, screenshots, icon and version-code changelog in supported metadata layout.
- [ ] Prepare `fdroiddata` metadata for `com.wynh.appapp`: license, source/issue links, categories, version/code, pinned commit, `subdir: app`, Gradle build, update checks and evidence-backed anti-feature explanations.
- [ ] Run F-Droid scanning, metadata lint and an isolated build; resolve actual findings rather than broad scanner exclusions.
- [ ] Decide F-Droid signing versus reproducible upstream signatures. For the latter, publish matching signed APKs, record allowed certificate fingerprints and verify reproducibility with F-Droid tooling.
- [ ] Disclose ChatGPT/OpenRouter and the GitHub importer to maintainers; explain offline operation and import consent. Ask only unresolved policy questions, with the capability design and test evidence attached.
- [ ] Submit metadata through the documented process and respond to review; no submission or reviewer contact occurred here. [Official submission workflow](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/).

### Google Play

- [ ] Resolve bridge/privacy/AI gaps; run pre-launch testing and verify all advertised functions work without hidden setup. Provide safe reviewer access/instructions for optional AI without sharing personal credentials.
- [ ] Build and validate a signed **AAB** for a new Play listing; configure Play App Signing and account for cross-store signing compatibility. An APK GitHub release is not a Play submission. [Bundle requirement](https://support.google.com/googleplay/android-developer/answer/9844279?hl=en-en).
- [ ] Complete Data safety, public/in-app privacy policy, app access, content rating, target audience, ads declaration and applicable foreground-service declarations/video. Confirm account-deletion applicability: connecting a third-party account is not automatically creation of an AppApp account.
- [ ] Confirm exact-alarm requests are user driven; keep Storage Access Framework pickers rather than adding broad storage/media permissions.
- [ ] Demonstrate AI content reporting and a moderation response process; review provider/model safeguards. Label store/promotional AI assets when applicable; this is separate from runtime AI reporting. [Asset declaration guidance](https://support.google.com/googleplay/android-developer/answer/17262077?hl=en).
- [ ] Resolve external payment-link behavior for intended distribution regions. Verify authorization for provider APIs and avoid unsupported claims of partnership.
- [ ] Explain that “install” means a web-subapp shortcut and that imports do not install APKs or update host native code. Supply a harmless local demo and a short capability/security explanation.
- [ ] Verify current target-API rules, developer verification, identity/contact requirements and Console-specific prerequisites immediately before submission. For personal accounts created after **13 November 2023**, the current production-access prerequisite is **12 opted-in testers for 14 continuous days**. [Testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en-en).

Official policies were consulted on the review date. Full policy text takes precedence over summaries, and reviewer discretion remains where explicitly identified above. No claim is made that a planned remediation guarantees acceptance.

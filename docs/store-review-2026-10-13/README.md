# Store review evidence — 13 October 2026

Companion to [the compliance review](../STORE_COMPLIANCE_REVIEW_2026-10-13.md).

## What ran

- Production source: AppApp `44e01a1d5a1fa75e00134626db0ed7d87a9c8cc8`.
- `git archive HEAD` extracted to `/tmp/appapp-store-audit/checkout`; its Android test source directory was replaced with `StoreAuditTest.kt`. No production-source differences were found when compared byte-for-byte with the repository.
- Gradle 9.6.0; JBR 21.0.11; Android SDK `/home/melo/Android/Sdk`.
- `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest`: successful in the temporary checkout. Original Android tests do not compile; their failure is retained separately.
- Emulator `Medium_Phone`, `emulator-5554`, Android 36, WebView 153.0.8010.36. The freshly built debug APK and instrumentation APK were installed with `install -r`.
- `adb -s emulator-5554 shell am instrument -w -e class com.wynh.appapp.audit.StoreAuditTest com.wynh.appapp.test/androidx.test.runner.AndroidJUnitRunner`: nine tests, no skips/failures.
- Original JVM tests: 52 tests in 11 suites, zero failures/errors/skips.
- Release assembly with all four `APPAPP_RELEASE_*` properties empty: fails at the project's explicit signing guard, after creating the unsigned APK. No release signing credentials were used.

**These are characterization tests.** Three tests intentionally assert unsafe reads/writes succeed. After fixes, invert those assertions; do not regard a green audit suite as proof of safety. The remaining tests verify disconnected AI rejection, simple path containment, standalone navigation blocking and ZIP bounds. No adversarial content was sent to an AI provider.

`editorAllowsExternalNavigationWithBridge` navigates to example.com, then evaluates a sentinel-only read locally through instrumentation. It does not send the read result to example.com. The iframe tests use opaque sandboxed srcdoc frames and return observations by postMessage. They do not use a remote exploit server. Cross-workspace probes use only the second disposable fixture; they do not read existing apps.

## Evidence files

| File | Contents |
| --- | --- |
| `StoreAuditTest.kt` | Exact temporary instrumentation source |
| `device-tests.txt` | Instrumentation runner result |
| `device-observations.txt` | Only logcat lines explicitly marked `AUDIT` |
| `jvm-tests.json` | Existing JVM suite counts |
| `existing-android-tests-failure.txt` | Original instrumentation compilation errors |
| `audit-build.log` | Successful fresh snapshot build/test log |
| `unsigned-release.log` | Unsigned release experiment and signing-guard failure |
| `dependencies.log` | Resolved debug/release module coordinates |
| `dependency-licenses.json` | 84 modules with official POM URLs and license declarations; Guava parent inheritance resolved |

Built debug APK SHA-256: `a1ce98c2914ccc754b9725e315d8043622c84c8de392195cc25cd1a3614d3f24`.

Built unsigned release APK SHA-256: `834ed8041b5becb63a7f25a82b3ffb8a42ba8da507dd92237fe3168ed4e6ecdf`.

These hashes identify this run, not reproducibility. Neither APK contained `.so` entries. Artifacts stay in `/tmp`; they are not release deliverables.

## Rerun safely

1. Select only the Medium_Phone emulator. Check `adb devices -l`, boot completion, and the existing AppApp installation. Follow repository device rules; never run this suite on a physical phone.
2. Extract the pinned Git commit into a fresh temporary directory. Copy this test into that snapshot's `app/src/androidTest/java/` after removing only the snapshot's old Android test directory. Leave the repository source untouched. Set the snapshot's `local.properties` SDK path.
3. Run the checked-in wrapper with JDK 21: `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest`.
4. Install the snapshot's debug APK and test APK onto the selected emulator using `adb -s <serial> install -r`. Stop on a certificate mismatch; do not uninstall to bypass it.
5. Run the instrumentation command above with the selected serial. It creates and deletes only its own temporary subapps. Start with no AI provider connected; the disconnected test otherwise skips and must not be reported as passed.
6. Capture the runner output and audit-only logcat. Count skips separately. After security fixes, revise unsafe-behavior assertions into regression tests expecting denial.

The original tests' compilation failure was not fixed, and no full connected Android regression suite was run. No F-Droid buildserver, signature-reproduction check, vulnerability database scan, Play Console upload or manual example-app UI test is implied by this evidence.

# Signed production builds on GitHub

The **Build signed App² production** workflow builds the production package
`com.wynh.appapp` with the existing production signing key. Run it manually from
`main` in GitHub Actions, on any machine. Download the `app2-production-N` artifact
from the completed run; it contains the APK, its SHA-256 checksum and source commit.
Artifacts expire after 30 days. This builds the version declared in
`app/build.gradle.kts`; it does not bump versions, tag or publish a GitHub release.
Follow the separate release procedure in `AGENTS.md` to ship a release.

The workflow runs JVM, lint, JavaScript and Python checks and compiles Android
tests before a separate signing job. It does not run an emulator or install onto
a phone. Follow the phone backup and certificate checks in `AGENTS.md` before
installing. Updating production requires the same signing certificate:

```text
d3a30c1acecc9008f11711c39013353f00fc58b540a26eba95bb3556eb02e89a
```

## Signing secrets

The GitHub environment `production` allows deployments from `main` only and holds:

| Secret | Contents |
| --- | --- |
| `APPAPP_RELEASE_KEYSTORE_BASE64` | Base64 encoding of the existing production keystore |
| `APPAPP_RELEASE_STORE_PASSWORD` | Keystore password |
| `APPAPP_RELEASE_KEY_ALIAS` | Existing signing alias |
| `APPAPP_RELEASE_KEY_PASSWORD` | Signing key password |

Base64 is only the transport encoding; GitHub Actions encrypts the secret. The
workflow decodes the key into a private temporary file, passes passwords through
environment variables, and removes the file even on failure. Only the APK,
checksum and commit ID are uploaded. The signing job uses a separate hosted
runner without Gradle caching, and third-party actions are pinned to commits.

Keep a separate encrypted backup of the original key and passwords. GitHub secrets
cannot be downloaded for local builds; trigger the workflow from another machine
instead. To continue building locally, provision that machine's user-level Gradle
properties and keystore separately. Never generate a replacement production key
for an ordinary update.

People who can change trusted workflow or build code can access signing secrets
when that code runs. Restrict repository write access and review workflow/build
changes on `main`. Environment branch restrictions do not replace code review.

To replace a secret deliberately, use GitHub Settings → Environments → production,
or pipe its value into `gh secret set NAME --env production --repo javiercmh/appapp`.
Never put passwords on command lines, in commits, artifacts or logs.

# App² Dev distribution

App² Dev is an independently installed preview of the Android host. It uses
`com.wynh.appapp.dev`, the name **App² Dev**, the original App² launcher artwork
with an amber DEV badge, and `appapp-dev://subapp/…` shortcut links. Production keeps
`com.wynh.appapp` and `appapp://subapp/…`. Providers derive their authority from
the installed application ID. Kotlin source packages remain `com.wynh.appapp`.

The installations have separate files, WebView storage, grants, settings and AI
connections. Connect AI separately in Dev. Import a ZIP into Dev if you want to
try an existing subapp; importing creates an independent copy.

## Signing key maintenance

The Dev key and the four repository Actions secrets have been provisioned.
On the setup machine, `~/.local/share/app2-signing/dev.p12` holds the key and
`dev-signing.properties` in the same directory holds its local signing properties.
Both files have owner-only permissions. Keep an encrypted backup of both files;
do not regenerate the key or replace the GitHub secrets for routine releases.
The setup instructions below are for a new installation or disaster recovery,
not steps to repeat for each build.

## One-time signing setup

Use a dedicated, persistent Dev key, separate from the production signing key.
Keep an encrypted backup: losing this key prevents updates to existing Dev
installations. Never commit keystores or passwords.

Generate the key outside the repository, entering a strong password at the prompt:

```sh
mkdir -p "$HOME/.local/share/app2-signing"
chmod 700 "$HOME/.local/share/app2-signing"
keytool -genkeypair -storetype PKCS12 \
  -keystore "$HOME/.local/share/app2-signing/dev.p12" \
  -alias app2-dev -keyalg RSA -keysize 3072 -validity 10000 \
  -dname 'CN=App2 Dev'
chmod 600 "$HOME/.local/share/app2-signing/dev.p12"
```

In repository `javiercmh/appapp`, configure these GitHub Actions secrets:

| Secret | Value |
| --- | --- |
| `APPAPP_DEV_KEYSTORE_BASE64` | Base64 of the entire PKCS12 file |
| `APPAPP_DEV_STORE_PASSWORD` | Keystore password |
| `APPAPP_DEV_KEY_ALIAS` | `app2-dev` |
| `APPAPP_DEV_KEY_PASSWORD` | Same password for this PKCS12 key |

With an authenticated GitHub CLI, send the key directly into the encrypted secret:

```sh
base64 < "$HOME/.local/share/app2-signing/dev.p12" | \
  gh secret set APPAPP_DEV_KEYSTORE_BASE64 --repo javiercmh/appapp
```

Set the other secrets in GitHub Settings → Secrets and variables → Actions,
or use interactive `gh secret set NAME --repo javiercmh/appapp` prompts. Do not
put passwords on command lines or print the encoded key. The workflow removes
the decoded keystore from the temporary runner directory even if a step fails.

## Publish and install

Once the workflow is committed and pushed, open GitHub Actions → **Publish App²
Dev** → **Run workflow**, selecting `main`. Publishing from other refs is disabled.
The workflow runs JVM, lint, JavaScript and Python checks and compiles the existing
Android tests. It does not run an emulator; native checks remain separate.

Each successful run publishes a new `dev-N` prerelease containing `app2-dev.apk`
and `app2-dev.apk.sha256`, pinned to the checked-out commit. `N` is the workflow's
increasing run number, used as the Android version code; the displayed version
is `1.6.1-dev.N` for the current base version. Keep this workflow's numbering
continuous. Do not delete/recreate its history or publish a lower version code.
Existing tags are never replaced, so rerunning an already published run fails at
release creation rather than silently replacing a download.

On your phone, open the prerelease, download `app2-dev.apk`, and open it. Android
may ask you to allow installations from that browser or file manager. App² Dev
appears beside App². Future Dev APKs update Dev and preserve its data when signed
with the same Dev key. These prereleases are explicitly not marked Latest.

## Local checks

```sh
./gradlew :app:assembleDev :app:lintDev :app:testDebugUnitTest
```

Without Dev signing properties, `assembleDev` makes a local preview signed with
this machine's debug key. That APK is for local checks, not distribution: it
cannot update an installation signed by the persistent publishing key.

For a matching locally signed Dev APK, put `APPAPP_DEV_STORE_FILE`,
`APPAPP_DEV_STORE_PASSWORD`, `APPAPP_DEV_KEY_ALIAS`, and
`APPAPP_DEV_KEY_PASSWORD` in user-level Gradle properties outside the repository.
Set `APPAPP_DEV_VERSION_CODE` to the intended version code and run
`:app:assembleSignedDev`. This task refuses to publish-build with missing Dev
signing properties, even if production signing properties are configured.

The output is `app/build/outputs/apk/dev/app-dev.apk`. Verify its package ID,
version code and signing fingerprint before distributing it. Local builds default
to Dev version code 1; CI supplies the workflow run number. This configuration does
not change the production version or production signing key.

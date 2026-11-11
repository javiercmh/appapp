# App² documentation

**Using App²:** the [project README](../README.md) and the [privacy policy](../PRIVACY.md).

**Building apps for App² (including agents):** read the Starter Demo's [`AGENTS.md`](../app/src/main/assets/starter_template/AGENTS.md)
(author workflow and data-safety rules), then [`PACKAGING.md`](../app/src/main/assets/starter_template/PACKAGING.md) (the runtime
contract: bridge, permissions, AI, speech, translation, sharing, save as, choosing a phone file, updates). Both ship inside every new subapp.

For subapp file import/export, read [choosing a file from the phone](../app/src/main/assets/starter_template/PACKAGING.md#choosing-a-file-from-the-phone)
and [Save as](../app/src/main/assets/starter_template/PACKAGING.md#save-as).
The development reference on GitHub is on the [`dev` branch](https://github.com/javiercmh/appapp/blob/dev/app/src/main/assets/starter_template/PACKAGING.md).
Existing subapps may contain an older bundled copy; consult the host repository when adding capabilities.

**Working on App²:** start with the root [`AGENTS.md`](../AGENTS.md), then:

| Document | Covers |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Ownership, transactions, threads, history and the security boundary |
| [SUBAPP_UPDATES.md](SUBAPP_UPDATES.md) | GitHub updates: publisher contract, review, recovery, publishing a subapp update |

**Releasing App²:**

| Document | Covers |
| --- | --- |
| [DEV_DISTRIBUTION.md](DEV_DISTRIBUTION.md) | App² Dev builds, signing key and prereleases |
| [PRODUCTION_BUILDS.md](PRODUCTION_BUILDS.md) | Signed production builds on GitHub |
| [FDROID.md](FDROID.md) | F-Droid submission ([draft metadata](fdroid/metadata/com.wynh.appapp.yml)) |
| [GitHub Releases](https://github.com/javiercmh/appapp/releases) | What changed in each release |
| [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) | Dependency licences ([data](dependency-licenses.json)) |

**[Archive](archive/README.md):** dated reviews kept for reference, not maintained.

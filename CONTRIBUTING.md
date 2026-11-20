# Contributing to App²

There are two ways to contribute: get your own app listed in Discover, or improve App² itself.

## Add your app to Discover

Discover is a curated list kept in [`app/src/main/assets/catalog/catalog.json`](app/src/main/assets/catalog/catalog.json). To suggest an app, open a pull request that adds it.

### 1. Prepare the repository

Your app must:

- live in a **public GitHub repository**, with `index.html`, `manifest.json` and a square `icon.png` at the root;
- be updatable: `manifest.json` declares `version` and an `app2Update` block with a unique `appId` (for example `com.yourname.yourapp`), `minimumHostVersionCode`, `managedFiles` and `seedFiles` ([publisher contract](docs/SUBAPP_UPDATES.md#publisher-contract));
- keep user records in `data/`, listed as `seedFiles` and never as `managedFiles`, so updates never overwrite them;
- work without the Internet permission, or explain in its description why it needs it;
- have an open-source licence, and contain no ads, non-consensual trackers or other invasive code.

Test it on a phone first: import it from GitHub, use it, push a change with a higher `version`, and check that **Update app** keeps your data.

### 2. Add a catalog entry

Fork this repository and add an entry to the `apps` list in `catalog.json`:

```json
{
  "id": "your-app",
  "name": "Your App",
  "author": "Your Name",
  "category": "Productivity",
  "license": "MIT",
  "summary": "One line shown on the card (up to 200 characters).",
  "description": "What it does and who it is for (up to 2000 characters).",
  "capabilities": ["reminders"],
  "offlineOnly": true,
  "minHostCompatibility": 1,
  "iconKey": "your-app",
  "source": {
    "type": "github",
    "owner": "your-github-name",
    "repository": "your-repo",
    "branch": "main",
    "publisherAppId": "com.yourname.yourapp"
  }
}
```

| Field | Rule |
| :--- | :--- |
| `id` | Unique, lowercase letters, digits, `-` or `_`, 2–64 characters. Never change it later: installed copies are matched by it. |
| `capabilities` | Any of `ai`, `speech`, `reminders`, `internet`, `files`, `share`. Unknown values are ignored. |
| `offlineOnly` | `true` if the app never needs the Internet permission. |
| `minHostCompatibility` | Same value as `app2Update.minimumHostVersionCode` in your manifest. |
| `iconKey` | Use your `id`. App² then downloads `icon.png` from the root of your repository on Refresh. |
| `source.publisherAppId` | Must equal `app2Update.appId` in your manifest. |
| `source.branch` | The branch people install from, usually `main`. |

### 3. Open a pull request

Open the pull request against the **`dev`** branch, with a link to your repository and a short note on what you tested. Check that `catalog.json` is still valid JSON.

Once it is merged, your app appears in Discover with the next App² release, and right away for anyone who taps **Refresh catalog** after that release. To remove or update your listing, open another pull request. Updates to the app itself need no pull request: they come from your repository.

## Improve App²

Bug reports and ideas are welcome as [issues](https://github.com/javiercmh/appapp/issues). For code changes, read [`AGENTS.md`](AGENTS.md) and the [developer documentation](docs/README.md), work from the `dev` branch, and open pull requests against `dev`.

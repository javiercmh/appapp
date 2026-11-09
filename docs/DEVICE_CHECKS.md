# Device checks

Manual checks on the `Medium_Phone` emulator for behaviour no automated test covers. Remove an item
once it is verified or a test covers it.

- **Cancelling imports.** Cancel a ZIP import during extraction and a GitHub import between files:
  no app is registered. Cancel as publication starts: the app is registered and the dialog never
  says it is cancelling.
- **File inputs.** Single and multiple files, mixed MIME accepts, a cancelled picker and a page
  change while picking, in preview and standalone.
- **Slow export.** Export to a slow cloud destination while the app runs: the app stays responsive.
- **Dialogs and accessibility.** Recovery, operation progress and error, and renderer-reload
  dialogs at narrow widths with large fonts; TalkBack names and touch targets.

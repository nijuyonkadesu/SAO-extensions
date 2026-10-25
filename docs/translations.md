# Translate source APKs through Crowdin

Use the **SAO Crowdin project** to translate Immich, Local Sticker, Moeview, FindThatMeme and Google Photos in your browser. Select a language, filter by the extension's label, enter translations, and click **Save**. Contributors do not need to edit XML, fork either repository, open pull requests, or build an APK. Crowdin's GitHub integration sends translated resources back for maintainer review.

> [!NOTE]
> The repository configuration is prepared; the owner still needs to connect a Crowdin account and verify the public project URL. The [main contributor guide](https://github.com/nijuyonkadesu/SAO/blob/feat/dynamic-stickers/docs/translations.md) will publish the verified link. Do not create a separate translation project for the extension repository.

English remains the fallback. Preserve placeholders such as `%1$s` and `%2$d`; product names and backend tags are read-only or remain as written. Shared form/intake wording is translated once in **Shared extension settings**. Source-specific forms, summaries, preset labels and suggestions have their own extension labels. New languages are enabled when requested; this setup does not add translations automatically.

## For extension maintainers

Native Android resources remain the underlying format. Crowdin exports files under each module's `src/main/res/values-<locale>/` directory, preserving resource names and filenames. The five modules are `immich-extension`, `local-extension`, `moeview-extension`, `findthatmeme-extension`, and `googlephotos-extension`. Shared strings belong to SAO's `source-settings` SDK; translate them there and rebuild consumers with the updated SDK. User-created names, credentials, backend query terms, tag IDs and media metadata are data, so they are preserved.

Every source settings Activity inherits `SourceSettingsActivity`, including host authentication and the host-supplied language. After caller authorization, the shared form uses `AppCompatDelegate.setApplicationLocales` to follow SAO’s effective language. Internal AppCompat Activities inherit that app language. Android 13+ owns its framework persistence; before Android 13 the extension uses AppCompat’s process setting and reapplies the host Intent on the next setup launch. Do not add mirrored language preferences or manually mutate `Locale.setDefault` / `Resources.updateConfiguration`. For each RPC that returns labels, use `SourceLocales.context(this, payload)` and that context's strings/numbers. Treat the request resource configuration as immutable; it can coexist with another request in a different language. Settings are per-app and follow the most recently authorized host that opens them. An older caller without the optional locale field uses the extension's normal Android resource configuration.

All five manifests enable RTL. Debug builds enable `en-XA`/`ar-XB` pseudolocales to exercise long strings and RTL without adding real translations; release builds do not include them. To review these screens, open them through SAO's authenticated source setup, not an unauthenticated shell Intent.

Build against the SDK staged from the sibling SAO checkout:

```sh
# In SAO:
./gradlew :source-api:publishSourceSdkPublicationToSourceSdkRepository :source-settings:publishSourceSdkPublicationToSourceSdkRepository
# In SAO-extensions:
./gradlew :immich-extension:assembleRelease :local-extension:assembleRelease :moeview-extension:assembleRelease :findthatmeme-extension:assembleRelease :googlephotos-extension:assembleRelease
./gradlew :immich-extension:lintDebug :local-extension:lintDebug :moeview-extension:lintDebug :findthatmeme-extension:lintDebug :googlephotos-extension:lintDebug
```

The SDK is currently 1.1.0 for both API and settings. Staging writes local build artifacts only; it does not publish a release. A remote SDK release is a separate maintainer action.

[`crowdin.yml`](../crowdin.yml) connects all five APK modules to the **same Crowdin project as SAO**, with one area label per extension. Both repositories use the same project ID. Configure Crowdin's native GitHub integration using the [owner activation guide](https://github.com/nijuyonkadesu/SAO/blob/feat/dynamic-stickers/docs/crowdin-setup.md). No GitHub Actions or APK-side Crowdin dependency is needed. CLI credentials, if used, belong in environment variables.

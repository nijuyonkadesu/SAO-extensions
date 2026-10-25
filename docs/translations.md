# Translating source APKs

Add translated entries from `<module>/src/main/res/values/*strings.xml` under the same module's `values-<locale>/` folders, with matching filenames and resource names. The five APK modules are `immich-extension`, `local-extension`, `moeview-extension`, `findthatmeme-extension` and `googlephotos-extension`. Ordinary XML pull requests are welcome; no translation service account or new runtime dependency is needed. English is the default, and incomplete translations fall back to it.

Follow the [shared translation guide](https://github.com/nijuyonkadesu/SAO/blob/feat/dynamic-stickers/docs/translations.md) for placeholders, plurals, Android locale folder names and RTL testing. Shared form/intake text lives in SAO's `source-settings` SDK; translate it there and rebuild the consumers with the updated SDK. Backend tag values, query terms, user names and credentials must not be translated. Category labels and profile summaries are separate presentation resources, so translating them leaves searches and profile identities unchanged.

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

[`crowdin.yml`](../crowdin.yml) optionally maps all five modules to Crowdin using Android XML and `%android_code%`. A hosted project has not been created. Maintainers can set `CROWDIN_PROJECT_ID` and `CROWDIN_PERSONAL_TOKEN` externally, then upload source strings/download translations with the Crowdin CLI. Review generated XML and run Android lint before merging. Do not bundle Crowdin's SDK or credentials in an extension.

# SAO extensions

First-party media sources for [SAO](https://github.com/nijuyonkadesu/SAO). Each Android APK owns its profiles, credentials and source-specific work. SAO owns browsing, caching and delivery to chats.

| Module | Source |
| --- | --- |
| immich-extension | Immich servers and named accounts |
| local-extension | Local folders, albums, tagging, OCR and Context search |
| moeview-extension | Supported image boards |
| findthatmeme-extension | Public keyword/caption meme search |

## Build

Use JDK 21 and Android SDK 37. The Gradle wrapper downloads the versioned source SDK from public SAO Release assets through Gradle's standard Ivy support. A SAO checkout or GitHub account is not needed to build.

```sh
./gradlew :immich-extension:assembleDebug :local-extension:assembleDebug :moeview-extension:assembleDebug :findthatmeme-extension:assembleDebug
```

The `repo` branch contains the generated public catalog and download page. Installable APKs live in GitHub Releases. Each source has an independent version and release. CI is deferred; publishing is manual.

## History

Extracted from SAO at `e1d9bf31f2175ffe803396a03951e54156b271d6`, preserving 46 relevant commits, authors and messages. The original SAO history is unchanged. `docs/history-map.txt` maps original commit IDs to filtered IDs. The extraction includes client libraries, source settings resources, third-party notices and source-specific documentation.

The shared `source-api` and `source-settings` SDK remains maintained in SAO and is consumed as versioned libraries here. Credentials, installed package IDs and APK signing identities are preserved during the move.

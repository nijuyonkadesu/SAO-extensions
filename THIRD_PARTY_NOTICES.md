# Third-party assets

## Local Context search

The Local Sticker extension uses the default [Immich ONNX export](https://huggingface.co/immich-app/ViT-B-32__openai/tree/a857c8de2c07bbcfa6646adfcf31b798845afa1e) of OpenAI CLIP ViT-B/32 and an optional [8-bit ONNX export](https://huggingface.co/Xenova/clip-vit-base-patch32/tree/d15189d7028b43f1d3e65039190477f6af591c2a), downloaded separately into a user-owned folder. CLIP is copyright OpenAI, 2021, [MIT](local-extension/src/main/assets/licenses/clip.txt). `ClipTokenizer` implements its byte-level BPE using the matching Hugging Face tokenizer vocabulary and merges. Inference uses Microsoft's [ONNX Runtime](https://github.com/microsoft/onnxruntime), [MIT](local-extension/src/main/assets/licenses/onnxruntime.txt). These license texts are bundled in the extension APK.

## Material Symbols

The shared `ic_picker_*.xml` and `ic_floating_picker.xml` vectors are Google Material Symbols Rounded, using optical size 24, weight 400, grade 0 and fill 0. Source: [google/material-design-icons, commit 49d4db3](https://github.com/google/material-design-icons/tree/49d4db35df873165d6bd6ba09b063c7dafbac2f4/symbols/android).

Copyright Google LLC. Licensed under the [Apache License 2.0](third_party/material-symbols/LICENSE). The vectors retain Google's path geometry; their root tint is removed and their base fill changed to black so SAO's Material component foreground roles control tinting.

## Extension artwork

Local Sticker (folder and peeled sticker), Moeview (stacked picture cards and a sparkle) and FindThatMeme (laughing message and search lens) use original SAO artwork, licensed under this repository’s AGPL-3.0 license. The previous shared photo-symbol placeholder is replaced.

Immich’s extension icon adapts the [upstream Immich flower](third_party/immich/README.md), copyright Immich contributors, AGPL-3.0. The adaptation preserves its path shapes/colors and adds a separate companion insert badge on a light tile. The original SVG, pinned source, license and modification details are preserved in `third_party/immich`; the notice and full license are also bundled in the Immich APK under `assets/licenses/immich-logo.txt`. This independent SAO extension is not endorsed by Immich.

Google Photos uses an original picture-and-cloud icon under the same license; it does not copy Google's app logo.

## Google Photos protocol

The Google Photos extension adapts the read-only request/response layouts of xob0t's MIT-licensed [google_photos_web_client](third_party/xob0t/README.md). The original copyright and full [MIT license](third_party/xob0t/LICENSE) are retained and bundled in the source APK. No Google APK contents or Python dependencies are distributed.

## Rays IPC contract

`com/skyd/rays/api/IApiService.aidl` follows the installed-app API from [Rays Android](https://github.com/SkyD666/Rays-Android/tree/9bd92a3ff8c9b4073531fed5d01639456df9136e), copyright SkyD666 and contributors, [GNU GPL v3](third_party/rays/LICENSE). The paged method is contributed in the companion Rays fork. Only the IPC definition is included; Rays' application, database and models remain in the separately installed app. Its license is also bundled under `assets/licenses/rays.txt`.

## Moeview reference client

The `moeview` library and its parser/transport checks adapt the user-provided `booru-android/booru` reference. [Provenance, integration changes and verification limits](docs/moeview-source.md#reference-and-validation). Its source directory is unchanged. The Moeview APK packages the existing shared Material Symbols license rather than maintaining another copy.

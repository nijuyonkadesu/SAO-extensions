# Source settings chips

All five extension settings pages and SAO’s Manage sources page use the shared `source-settings` 1.0.1 Views library. The extension IPC protocol remains v1. Editable names, credentials and descriptions remain text fields; Save source remains the primary button and Back stays navigation.

| Role | Appearance | Behaviour |
|---|---|---|
| Choice/toggle | Selected: secondaryContainer/onSecondaryContainer with checkmark. Unselected: transparent with outline. | Values come from the ViewModel; rendering cannot trigger writes. Single-choice groups require one selection. |
| Action | Transparent, outlined, leading Material icon. | Clicking does not leave a selected state. |
| Configured/status action | Filled only while the represented state is configured/active. | Album scope, model folder, browser-session section and paused processing reflect their actual state. |
| Destructive action | Transparent, error-colored text/outline/icon. | Existing confirmations and original-file preservation are unchanged. |
| Disabled | Dimmed theme colors; unavailable controls do not claim an active feature. | The existing capability, readiness and busy conditions still apply. |

Groups wrap at the available width. Chips retain 48 dp touch targets with compact visible bodies. Labels stay single-line and ellipsize at narrow widths; supporting descriptions remain outside the chips. Leading icons use Google Material Symbols Rounded and the shared theme tint; a selected choice displays its checkmark in the leading slot.

## Values

- Google Photos / Immich: visible **All media**, plus **Choose album** or the selected album’s name. Resetting to All media needs no network call. An album is selected only after the chooser returns an album.
- Local picker: **Folders**, **Photos**, **Files**. Model: **Immich default**, **CLIP 8-bit**; model sizes/details stay in supporting text. Context and NSFW are independent toggles.
- Moeview boards: **Yande.re**, **Konachan**, **Gelbooru**, **Danbooru**. Ratings: **Safe**, **Non-safe**, **All**. Proxy remains subject to the board and credential rules.
- FindThatMeme: **Include sensitive media** is an independent toggle.
- Manage sources: each profile has separate **Home**, **Floating**, **Edit** controls. Floating affects picker membership only. Global **All sources** and **Wi-Fi only** are independent toggles. Installed-extension actions include app info, trust review/revocation, install/update/cancel and uninstall.
- Processing/import actions retain Pause/Resume, Restart, Retry failed, Download, folder selection and metadata editing. Paused assist chips are filled while paused; one-time actions remain outlined.

All shipped APKs use minified, resource-shrunk release builds. Instrumentation uses debug builds where host internals must remain inspectable; cross-APK UI checks can use optimized extension APKs. Local SDK staging is documented in the repository README and does not publish an SDK or APK release.

## Validation (2026-10-24)

All five optimized extension APKs and the host release build succeed. Shared settings lint has zero errors. Seven targeted Android 15 checks pass: model-owned selections/theme colors, separate Home/Floating state, all five real settings Activities, three save/discard flows, and inline import authorization/pause/resume without permission polling. The five-Activity check also passes with dark mode and 130% text. Local light/dark screenshots were visually inspected; no screenshots or APKs were uploaded.

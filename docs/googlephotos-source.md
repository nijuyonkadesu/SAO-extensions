# Google Photos source (experimental)

An independent source APK using Source SDK 1.0.0. SAO itself needs no change. Install `googlephotos-sao.apk`, then add Google Photos under SAO's Media sources. Each named source has its own encrypted session and optional album scope.

## Preferred connection: Vector

1. Install the official Google Photos app and sign in normally.
2. In Vector (modern libxposed API 102), enable **SAO Google Photos**, with **Google Photos** selected as its scope. This source extension, rather than SAO's messaging module, owns authentication.
3. Force-stop/reopen Google Photos after changing the scope. Open the source settings in SAO, press **Connect through Google Photos · Vector**, and choose the account in the dialog inside Google Photos.
4. Return to the source settings if needed, choose Whole library or an album, then press **Save source**.

The module intercepts three framework lifecycle methods, with no account access, API request, polling or scheduled work during ordinary Photos use. A settings-initiated, random 256-bit nonce opens a connection valid for at most two minutes. The exported broker validates the real calling UID, official package and Google's signing certificate on every Binder transaction; the nonce can be claimed once. Cancellation/timeouts release the handshake and hooked-process connection.

For that explicit connection only, the scoped process uses `AccountManager.getAuthToken` with Photos' own `weblogin:service=gaia&continue=…` token type. It returns a short-lived MergeSession URL. The extension consumes it through at most eight HTTPS redirects on `accounts.google.com` and `photos.google.com`, using an isolated cookie jar and checking the account identity when supplied. It never reads the Google account database, exports a master/refresh token, invokes `su` or installs interception certificates. The one-use token is cleared from AccountManager's local token cache after retrieval, matching Photos' WebAuth flow.

**Live Google/Vector authentication still requires device verification.** Emulator fixture tests validate the broker, nonce, redirect restrictions and session conversion; they do not establish that Google Play services will issue this token on every device/account. If Google rejects it, the source reports the failure and offers an explicit browser-session import rather than silently changing authentication routes. This remains an experimental source.

## Search and media

- Whole-library Context passes the full query, including spaces and emoji, to `EzkLib`. No local embedding model or complete-library indexing is involved.
- OCR uses Google's quoted **Text matches** query. It also matches textual metadata such as captions/filenames; this API route does not provide an OCR-only filter. The limitation is explained in the settings page.
- Album profiles use `snAcKc` and browse album order. Search capabilities are disabled for these profiles because the reference client does not establish album-scoped server search. No client-side filtering of the whole library is attempted.
- Empty whole-library browsing uses `lcxiM`; album selection uses `Z5xsfc`. Batch metadata uses `EWgK9e`; original media links use `VrseUb`.
- JPEG/PNG, GIF/WebP and video records flow through the host media renderer. Still previews request up to 1600×1600 while preserving aspect ratio; GIFs retain animation. Video records advertise a separate optional `playback` variant: Google's 360p MP4 (`=m18`), bounded to 8 MiB before/during the transfer and validated as MP4. Missing, oversized or unprocessed renditions leave the still thumbnail; playback never falls back to the original. Scrolling away cancels the transfer. The host caches playback separately and detects its actual container for home Share. Copy/Paste/Download and conversion inputs retain original bytes/MIME, including MKV. This separation requires the matching host update; earlier hosts use the legacy original-video route. [FIFE parameter reference](https://gist.github.com/Sauerstoffdioxid/2a0206da9f44dde1fdfce290f38d2703) documents `m18`; this private rendition is not guaranteed for every asset.
- SAO drives pagination. A source reply holds at most 40 items and stays within the Binder response bound. If Google returns a larger page, encrypted cursors retain the input page token, offset and fingerprint; the next host request rereads that page and resumes without persisting its results. Cross-query, changed-profile, tampered and repeating continuations fail explicitly.
- Unsupported random, people/place filters, independent filename/description modes, suggestions, chronological neighbours and uploads are not advertised.

## Credentials, IO and errors

Profiles and opaque media/cursor references use Android Keystore AES-GCM and an `AtomicFile` in non-backed-up private storage. Only public profile information and granted `content://` media streams cross SAO's existing IPC. Cookie rotation updates authentication without changing source revision. Explicit credential/account/album changes change the revision and revoke old media grants.

A bounded, four-session memory map retains authentication/bootstrap data only. It caches neither search results nor media. SAO owns media caching and request/retry timing. HTTP uses the already existing OkHttp dependency with transport retries disabled, bounded bodies/timeouts and coroutine cancellation through body reads. Media streams use reliable socket pairs and cancel when SAO closes its reader. Cookies retain their original domain, host-only, path, expiry and Secure scope. Private media on `lh3.google.com` and `photos.fife.usercontent.google.com` can complete up to eight HTTPS media/Google Accounts redirect hops. Cookies are matched separately on every hop and are never sent to public `googleusercontent.com`/`ggpht.com` CDNs. Google sign-in HTML is an authentication failure, never an image. Older native connections need one reconnection because the previous profile format discarded account-only cookies; full Netscape imports preserve their scopes, while a pasted header cannot recover them. No background job, idle refresh, wake lock, Python/protobuf engine, Google SDK or new runtime dependency is added. libxposed is compile-only and supplied by Vector.

Expired/revoked sessions return `HTTP_401`/`HTTP_403`; the host can open this source's settings for reconnection. `HTTP_429` carries Retry-After, with no extension retry loop. Network, TLS, timeout, invalid-response and profile-change errors use the existing SDK codes without exposing response bodies, credential URLs or account tokens.

## Research and verification

Read-only protocol layouts come from xob0t's MIT-licensed [web client](https://github.com/xob0t/google_photos_web_client/tree/651aa268d50529cb6128c8a42c9cd4ce668f2189); [gpmc](https://github.com/xob0t/gpmc/tree/94b1b267ca6819da542d4330a7dd216747eb0437) informed mobile API/authentication research. Attribution is bundled in the APK.

Stock APK download attempts from APKMirror were blocked and Archive.org timed out. For method inspection only, a publicly distributed **patched** Photos 7.97.0.997578476 APK was examined; it was not installed as an official app or used for login. Its WebAuth adapter takes AccountManager and an executor, requests the quoted `weblogin` token types, invalidates the local token cache and returns an `accounts.google.com` URL. The integration uses Android APIs directly and does not depend on those obfuscated class names or include their code. The modified package/certificate is deliberately rejected by the production broker.

The synthetic Android checks cover independent/private profiles and idle behavior, Context/Text matches translation, album browsing, oversized-page continuation, cursor isolation/tampering, original PNG/GIF/MP4 streams, cookie isolation, native handshake replay/caller rejection, trusted login redirects, 429/auth failures and cancellation during slow response bodies. They do not access a user's account or photo library.

Validation: debug and optimized release builds pass; lint has zero errors. All 13 targeted Android checks pass on an isolated Android 15 emulator, including the unchanged host's home/floating rendering and source edit/save journey. The UI automation waits for cross-package window transitions before selecting controls. The signed optimized APK also opens its settings through the existing SAO host. Its size is approximately 2.23 MiB. APK inspection confirms the Vector entry point is retained, with no bundled libxposed implementation, Play services, Python classes or native libraries.

[Settings, light](screenshots/googlephotos/settings-light.png) · [Settings, dark](screenshots/googlephotos/settings-dark.png) · [Floating picker with synthetic media](screenshots/googlephotos/floating-light.png). These are UI/fixture checks, not screenshots of a connected Google library. Live Google account and Vector verification remains pending.

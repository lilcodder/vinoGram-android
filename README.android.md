# vinoGram for Android

Android port of the features shipped in vinoGram desktop release **1.0 Release**
(tag `Update`, commit `2a8d64abe899ad6f258490395bea5dc2ef62e0bf`).
Built on [Telegram for Android](https://github.com/DrKLO/Telegram), upstream
commit `0aae959806c57be8ad457980f2a5c6277ff18635`.

Included: vinoGram name, launcher icon, welcome banner, and **Subscribtion** in
Settings → Data and Storage → Proxy Settings. Import HTTP/HTTPS subscriptions as
URI lists, Base64, Xray JSON or AURA exports. Supported protocols: VLESS/Reality,
VMess, Trojan, Shadowsocks and SOCKS5. Embedded Xray v26.9.9 checks servers every
30 seconds and selects a working server for new connections. Lists refresh
hourly. Imported routing, inbound listeners and local file settings are excluded.
The authenticated local listener is bound to `127.0.0.1`; there is no direct
fallback while the subscription is enabled. Disabling it restores the previous
proxy configuration. A manually selected proxy takes priority.

Only vinoGram traffic uses the subscription; this does not create a system VPN.
No vinoGram VPS is used. Experimental avatar shapes and other unpublished
desktop changes are excluded.

## Get an APK

Open **Actions → vinoGram Android → a successful run → Artifacts →
vinoGram-Android-0.1.0-arm64**. Unzip and install `vinoGram-0.1.0-arm64.apk`.
The initial target is ARM64 Android phones, Android 6 or newer.
Application id: `org.vinogram.messenger.web`, allowing installation alongside
the official client. Builds use a persistent private signing key, so subsequent
APKs can update the app without deleting account data.

## Build

Use Java 17, Android SDK 36, build tools 36.0.0, NDK 27.2.12479018 and CMake 3.22.1.
The GitHub Actions workflow installs the toolchain and initializes pinned
submodules. Add repository secrets:

- `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` for your own Telegram application.
- `ANDROID_KEYSTORE_BASE64`: a Base64 PKCS12 signing keystore with alias `vinogram`.
- `ANDROID_STORE_PASSWORD`: the keystore and key password.

For a local build, export those API credentials plus `VINOGRAM_KEYSTORE` (absolute
path), `VINOGRAM_STORE_PASSWORD`, `VINOGRAM_KEY_PASSWORD` and
`VINOGRAM_KEY_ALIAS=vinogram`, then run:

```sh
git submodule update --init --depth 1 --jobs 4
python3 Tools/prepare_xray.py
python3 Tools/subscription/test.py
./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone --no-daemon --max-workers=2
```

Output: `TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk`.
Keep a separate backup of your signing key and password: replacing the key prevents
updates over installed copies. Credentials and signing files are never checked in.

Upstream dummy Google/Firebase credentials and the public signing key have been
removed. Google push is disabled without your own Firebase configuration;
background delivery depends on the app retaining its connection and Android's
battery restrictions. Optional Firebase support requires your own
`TMessagesProj_AppStandalone/google-services.json` and
`TMessagesProj/google-services.json` and `VINOGRAM_FIREBASE_CONFIGURED=true`.
Official-app passkeys, Google billing and
the official Telegram APK updater are disabled for this independent client.

## Provenance and licenses

Branding can be regenerated from the exact released desktop commit with
`python3 Tools/prepare_branding.py --desktop /path/to/vinoGram` (requires Pillow).
Packaged `assets/vinogram/desktop-release.json` records both source commits.
The desktop source remains unchanged by the Android build.

Telegram Android source headers permit GPLv2 or later. The port, including the
subscription implementation adapted from vinoGram's GPLv3 desktop release, is
distributed under GPLv3; see `LICENSE`. The upstream license is retained as
`LICENSE.upstream`. Xray is a separate MPL-2.0 executable; its full license is
packaged in `assets/vinogram/Xray-core-LICENSE.txt`. Other upstream third-party
notices remain in their original source directories.

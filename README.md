# opencode-android-client

[![Android](https://github.com/chyax98/opencode-android-client/actions/workflows/android.yml/badge.svg)](https://github.com/chyax98/opencode-android-client/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/chyax98/opencode-android-client)](https://github.com/chyax98/opencode-android-client/releases)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

**English** · [中文](README.zh-CN.md)

An Android client for [opencode](https://opencode.ai). It pairs with `opencode pair` and shows the official
web UI full-screen in a WebView — no rewritten UI, no JS bridge, no account, no telemetry.
Multiple servers, warm switching, a native pairing screen that explains failures in plain language.

<img src="docs/assets/icon.png" width="96" alt="icon">

- **Several servers** — each keeps its own cookies and site data; switch from the floating button or the
  launcher shortcut
- **Switching doesn't reload** — the previous page is parked and resumed (scroll position, unsent drafts,
  live event stream intact); the other server's TCP/TLS is preconnected while you work
- **Pairing that says what went wrong** — loopback address, expired code, not an opencode server, service
  still starting, TLS problems: each gets its own message instead of `net::ERR_*`
- **Native setup screen** — Material 3, mapped to opencode's own design tokens, light/dark follows the system
- **Small surface** — no JavaScript interface, no background service, credentials only in app-private storage
  (`allowBackup=false`)

Android 8.0+ (API 26). Release APK is ~1.7 MB.

## Install

Download the latest `oc-client-*.apk` from [Releases](https://github.com/chyax98/opencode-android-client/releases/latest)
and install it. Or build it yourself (see below).

## Pair your computer

The pairing code lives on your computer, not in the app: `opencode pair` prints **a QR code and one link per
address**. The app only consumes them.

**1. On the computer that runs opencode** (the background service must be running — `opencode serve` in the
foreground is not enough):

```bash
opencode pair            # LAN address(es)
opencode pair --remote   # also expose a tunnel URL (asks for consent the first time)
```

**2. Read the output.** It looks roughly like this:

```
█▀▀▀▀▀█ ▄▀ █▀▀▀▀▀█     ← QR code, drawn with characters
█ ███ █ ▀▄ █ ███ █
...
Links work once and expire in 5 minutes:

  http://127.0.0.1:49374/auth/connect/AbC123...
  http://192.168.1.2:49374/auth/connect/AbC123...
  https://<your-tunnel>/auth/connect/AbC123...
```

The QR encodes `{code, urls}` with loopback addresses already stripped, so **scanning is the shortest path**.
The printed list also contains a `127.0.0.1` link — that points at the phone itself when pasted there; the app
rejects it with an explanation.

**3. In the app**, on the setup screen:

- tap **Scan QR code** and point the camera at the terminal, or
- tap **Paste** (or long-press → paste) with a non-loopback link copied from the terminal, then **Connect**

Either way the code is redeemed once and exchanged for a 30-day session stored in the app's private cookie jar.
The code itself is valid for **5 minutes and single use** — if it expired, run `opencode pair` again.

> Light terminal? `opencode pair` draws the character QR for a dark background, so it looks inverted in a light
> theme. The app's decoder accepts both polarities; you don't need to change your terminal colors.

## Everyday use

- Opening the app goes straight to the last page you were on, per server.
- Back on the root page twice → server list. Or tap the floating button, or long-press the launcher icon →
  **Servers**.
- Pairing failures leave the link in the field: if the service was still starting (503), tapping **Connect**
  again is a real retry — the code was not spent.
- If the remembered page no longer exists, you land on the home page and get told so instead of silently losing
  state.

## Switch between servers

The native server list shows `name · kind` and the full address per row. Tap to switch, long-press to delete,
`⋮` to rename / copy address / delete. Each server keeps its own cookies and WebView storage, keyed by origin.

The floating button opens a small panel listing every server plus **Server management ›**.
It only exists while the web UI is on screen; it can be dragged, snaps to the nearest edge, and remembers its
position.

## Build

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # JDK 17+ works
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest                   # 231 tests, all on the JVM
./gradlew :app:assembleRelease                     # R8 + resource shrinking
```

Signing, CI, and the two dependency gotchas (zxing → androidx.fragment, kotlin-bom) are in
[docs/build.md](docs/build.md).

## Docs

| Doc | What's inside |
| --- | --- |
| [docs/design.md](docs/design.md) | Architecture, multi-server switching, pairing and credential boundary, known trade-offs |
| [docs/build.md](docs/build.md) | Building, signing, CI, dependency gotchas |

## Limitations

- **Path-prefixed URLs** (reverse proxy under `/prefix`) pair but the upstream web UI hardcodes `location.origin`;
  the app warns you after pairing.
- **One credential, one address**: the token is only used on the address that issued it. A new tunnel hostname
  needs a new pairing.
- **Session expiry**: 30 days, or immediately when the server password changes; the app re-checks on cold start
  and asks you to pair again instead of showing the web UI login screen.
- **The native setup screen is Chinese-only** for now (the opencode web UI itself follows its own language).


## License

[MIT](LICENSE). The icon is opencode's official mark (`packages/identity/mark.svg`), © the opencode authors.
This project is an unofficial client and is not affiliated with the opencode project.

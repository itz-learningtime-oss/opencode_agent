# OpenCode Term — Android terminal client (armeabi-v7a)

A standalone Android terminal client for 32-bit ARM devices (Samsung Galaxy M02 class).
No root, no Termux, no ADB in normal use.

## What it does

1. **Local shell terminal** — a real child process (`/system/bin/sh`) attached to a PTY
   via native JNI (`posix_openpt`/`fork`/`execv`). Genuine bytes in, genuine bytes out.
2. **OpenCode remote client** — connects to a user-configured `opencode serve` instance
   using OpenCode's documented HTTP API (`/global/health`, `/session`, `/session/:id/message`,
   `/event` SSE) with HTTP Basic auth. Model inference happens entirely on the server.

> **Why not embed OpenCode locally?** See [COMPATIBILITY.md](COMPATIBILITY.md). In short:
> OpenCode ships 64-bit Bun-compiled binaries only; no Node.js/Bun runtime exists for
> armeabi-v7a Android, and Android 10+ W^X policy blocks executing extracted binaries at
> targetSdk 34. The app reports this honestly and provides the remote-server path instead.

## Requirements

- Android 7.0+ (API 24) device with an ARMv7 CPU (armeabi-v7a)
- An OpenCode server reachable over the network: run `opencode serve --hostname 0.0.0.0 --port 4096`
  (optionally with `OPENCODE_SERVER_PASSWORD` for Basic auth) on a machine that can run OpenCode.

## Installation

Build (see BUILDING.md) and install the APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

or copy the APK to the device and open it.

## First launch

1. The app starts a local shell session so you always have a working terminal.
2. Tap **⚙** to open Settings, enter your server URL (`https://host:4096`),
   username (default `opencode`) and password/token, then **Test connection**.
3. **Save** — the app health-checks the server and switches to remote mode.
   Status bar shows `REMOTE host:port` or `LOCAL SHELL` truthfully.

In remote mode, type a prompt and press Enter; the reply is rendered in the terminal.
Use the toolbar (CTRL/ALT one-shot modifiers, TAB, ESC, arrows, Enter, Backspace,
KB, CLR, restart) for terminal control.

## Security notes

- Tokens are stored encrypted (Android Keystore, AES-GCM) and never logged.
- HTTP (cleartext) is disabled unless explicitly enabled in Settings — for private
  networks only. Credentials over HTTP are readable by anyone on the network.
- The app requests only `INTERNET` and `ACCESS_NETWORK_STATE`.

## Troubleshooting

| Symptom | Meaning / fix |
|---|---|
| `AUTH: Server rejected credentials` | Wrong username/password, or server started without `OPENCODE_SERVER_PASSWORD`. |
| `TIMEOUT` | Wrong host/port, firewall, or device offline. |
| `Host not found` | DNS/name typo. Use the IP on LAN setups. |
| Terminal frozen | Tap **↻** in the toolbar to restart the session. |
| HTTP refused | Enable "Allow insecure HTTP" in Settings (private network only). |

## Limitations

- The 32-bit-only APK does not install on arm64-only devices.
- No local OpenCode execution on ARMv7 (hard upstream/platform blockers — COMPATIBILITY.md).
- The remote client covers prompt→reply and session creation; advanced TUI features
  (model picker dialogs, permission prompts UI) are surfaced via errors, not faked.

# LinkPhone

Microsoft Phone Link, rebuilt from scratch — Android phone + Windows PC, **local network only, no
cloud account, no relay server**.

> **Status: v0.2.** QR pairing, SMS, notifications, two-way file transfer and clipboard sync are
> implemented end to end. Calls, screen mirroring and Android app streaming are **not** — see
> [Roadmap](#roadmap). Everything is built by GitHub Actions; there are no local build steps.

---

## What is in this repo

```
desktop/      Electron + React + TypeScript PC app (WebSocket server, mDNS advertiser, tray)
android/      Kotlin + Jetpack Compose phone app (WebSocket client, notification listener)
docs/         PROTOCOL.md — the wire format, crypto and threat model
.github/      CI: builds the APK and typechecks/bundles the desktop app on every push
```

## Features

| Feature | State | Notes |
|---|---|---|
| Pairing (QR or code, no account) | ✅ | CameraX + ZXing scanner; 8-char fallback code |
| mDNS discovery | ✅ | `_linkphone._tcp`, no IP typing |
| Notification mirroring | ✅ | app, title, text, tap-to-open, dismiss sync |
| Inline reply from PC | ✅ | via `RemoteInput` when the notification supports it |
| SMS conversations | ✅ | read existing threads, receive live messages, reply from PC |
| MMS / new conversation | ⛔ | existing SMS threads only in v0.2 |
| File transfer both ways | ✅ | 64 KiB chunks, 512 KiB window, Documents/Downloads |
| Clipboard sync | ✅ (text) | PC ⇄ phone, phone side only while the app is open |
| Auto-reconnect | ✅ | 2.5 s backoff, survives Wi-Fi hiccups |
| Phone calls | ⛔ | planned — needs Bluetooth HFP, see below |
| Screen mirror + control | ⛔ | planned — `MediaProjection` + WebRTC |
| App streaming, camera | ⛔ | planned |

## Architecture

```
┌──────────────────────────┐         mDNS (_linkphone._tcp)        ┌───────────────────────────┐
│  Android (Kotlin)        │◄─────────────────────────────────────►│  Windows (Electron)        │
│                          │                                      │                            │
│  NsdManager discovery    │         ws://192.168.x.x:8765        │  bonjour-service advert    │
│  OkHttp WebSocket ───────┼──────────────────────────────────────┼─► ws server                │
│  Notifications + SMS     │   AES-256-GCM frames (seq + AAD)     │  system notifications      │
│  CameraX QR + MediaStore │                                      │  SMS / files / clipboard  │
│  Clipboard poller        │                                      │  tray + React UI           │
└──────────────────────────┘                                      └───────────────────────────┘
```

One encrypted WebSocket carries everything. See [docs/PROTOCOL.md](docs/PROTOCOL.md) for frames,
handshake and the threat model.

## Install (no builds on your machine)

1. Open the [latest GitHub Release](https://github.com/FakeAbid11/linkphone/releases/latest).
2. Install `linkphone-android.apk` on the phone (allow "install unknown apps").
   If upgrading from v0.1, uninstall it first and pair again: v0.1 used a temporary CI signing key.
   v0.2 establishes the permanent key, so later releases update in place normally.
3. Run `LinkPhone.Setup.0.2.0.exe` on Windows. The installer is unsigned, so SmartScreen requires
   **More info → Run anyway**.
4. On the PC, expand **Scan QR code instead** in the sidebar.
5. On the phone: **Scan QR** and point the camera at the PC. The original network Scan + typed code
   flow remains available as a fallback.
6. On the phone, open notification settings and tap **Allow SMS access**.

Both devices must be on the same Wi-Fi, and the network must allow mDNS and client-to-client
traffic. Guest/AP-isolated Wi-Fi will not work.

### If discovery finds nothing

QR pairing embeds every IPv4 address reported by the PC and tries them in private-network order, so
it also works when mDNS is blocked. Client-to-client traffic and TCP port 8765 must still be allowed.

## Running from source (optional, needs a dev machine)

```bash
cd desktop
npm install          # npm 12 asks before running electron's postinstall script
npm run dev          # Electron + Vite with HMR
```

Android needs Android Studio or the SDK plus a JDK 17; `gradle :app:installDebug` from `android/`.

## Security

* No account, no cloud, no telemetry. Traffic never leaves the LAN.
* Handshake proves knowledge of the pairing code; every subsequent frame is AES-256-GCM with a
  monotonic sequence number and direction-bound AAD.
* SMS contents use the same encrypted channel and are never uploaded. SMS access is optional.
* The PC stores the per-device key in `config.json` under `%APPDATA%\LinkPhone`; the phone stores it
  in `EncryptedSharedPreferences`.
* **Not yet hardened:** no TLS/pinning (a malicious LAN host could impersonate the PC), 6-digit codes
  accepted, no rate limiting on pairing attempts. Don't use this on a network you don't trust.

## Roadmap

1. **MMS + new conversations** — v0.2 intentionally replies inside existing SMS threads only. It
   does not become the default SMS app, so messages sent from the PC are not inserted into Android's
   provider history by LinkPhone itself.
2. **Calls** — call control via `TelecomManager`/`InCallService`; audio over Bluetooth HFP, handled by
   the OS, exactly like Phone Link. In-call audio cannot be captured by a normal Android app.
3. **Screen mirroring** — `MediaProjection` → hardware H.264 encoder → WebRTC; touch injection via an
   `AccessibilityService`.
4. **TLS + pinning**, manual IP entry, contact-name lookup, multi-device.

### Why iOS is out of scope

iOS exposes no SMS API, no notification access except ANCS over BLE, no screen capture of other
apps and no call control. A "Phone Link for iPhone" can only mirror notifications and make calls
over Bluetooth. Android-first is the only way to reach feature parity.

## License

MIT

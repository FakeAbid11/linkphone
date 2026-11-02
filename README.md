# LinkPhone

Microsoft Phone Link, rebuilt from scratch — Android phone + Windows PC, **local network only, no
cloud account, no relay server**.

> **Status: v0.1, milestone 1.** Notifications, two-way file transfer, clipboard sync and pairing are
> implemented end to end. SMS, calls, screen mirroring and Android app streaming are **not** — see
> [Roadmap](#roadmap). Everything is built by GitHub Actions; there are no local build steps.

---

## What is in this repo

```
desktop/      Electron + React + TypeScript PC app (WebSocket server, mDNS advertiser, tray)
android/      Kotlin + Jetpack Compose phone app (WebSocket client, notification listener)
docs/         PROTOCOL.md — the wire format, crypto and threat model
.github/      CI: builds the APK and typechecks/bundles the desktop app on every push
```

## Milestone 1 features

| Feature | State | Notes |
|---|---|---|
| Pairing (code, no account) | ✅ | 8-char code, PBKDF2 + AES-256-GCM |
| mDNS discovery | ✅ | `_linkphone._tcp`, no IP typing |
| Notification mirroring | ✅ | app, title, text, tap-to-open, dismiss sync |
| Inline reply from PC | ✅ | via `RemoteInput` when the notification supports it |
| File transfer both ways | ✅ | 64 KiB chunks, 512 KiB window, Documents/Downloads |
| Clipboard sync | ✅ (text) | PC ⇄ phone, phone side only while the app is open |
| Auto-reconnect | ✅ | 2.5 s backoff, survives Wi-Fi hiccups |
| SMS / MMS | ⛔ | planned — needs the default-SMS-app role |
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
│  NotificationListener    │   AES-256-GCM frames (seq + AAD)     │  system notifications      │
│  MediaStore transfers    │                                      │  clipboard watcher         │
│  Clipboard poller        │                                      │  tray + React UI           │
└──────────────────────────┘                                      └───────────────────────────┘
```

One encrypted WebSocket carries everything. See [docs/PROTOCOL.md](docs/PROTOCOL.md) for frames,
handshake and the threat model.

## Install (no builds on your machine)

1. Open the **Actions** tab → latest green `CI` run.
2. Download `linkphone-android-debug` → `app-debug.apk` → install on the phone (allow "install from
   unknown sources").
3. Download `linkphone-desktop-bundle`, or build the installer from the `Release` workflow
   (`workflow_dispatch`), unzip, run `LinkPhone.exe`.
4. On the PC: note the **pairing code** in the sidebar.
5. On the phone: open LinkPhone → **Scan** → tap your PC → type the code.
6. On the phone: **Open settings** → allow notification access for LinkPhone.

Both devices must be on the same Wi-Fi, and the network must allow mDNS and client-to-client
traffic. Guest/AP-isolated Wi-Fi will not work.

### If discovery finds nothing

Use the address shown under **Address** in the desktop sidebar. Corporate Wi-Fi frequently blocks
multicast; a manual "connect by IP" field is on the roadmap.

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
* The PC stores the per-device key in `config.json` under `%APPDATA%\LinkPhone`; the phone stores it
  in `EncryptedSharedPreferences`.
* **Not yet hardened:** no TLS/pinning (a malicious LAN host could impersonate the PC), 6-digit codes
  accepted, no rate limiting on pairing attempts. Don't use this on a network you don't trust.

## Roadmap

1. **SMS** — become the default SMS app (`READ_SMS`/`SEND_SMS` + `Telephony` provider), thread UI,
   and the Play Store policy work that goes with it.
2. **Calls** — call control via `TelecomManager`/`InCallService`; audio over Bluetooth HFP, handled by
   the OS, exactly like Phone Link. In-call audio cannot be captured by a normal Android app.
3. **Screen mirroring** — `MediaProjection` → hardware H.264 encoder → WebRTC; touch injection via an
   `AccessibilityService`.
4. **TLS + pinning**, manual IP entry, QR pairing, multi-device.

### Why iOS is out of scope

iOS exposes no SMS API, no notification access except ANCS over BLE, no screen capture of other
apps and no call control. A "Phone Link for iPhone" can only mirror notifications and make calls
over Bluetooth. Android-first is the only way to reach feature parity.

## License

MIT

# LinkPhone

Microsoft Phone Link, rebuilt from scratch — Android phone + Windows PC, **local network only, no
cloud account, no relay server**.

> **Status: v0.4.15.** Phone call controls, built-in updates, photo albums, battery status, QR pairing,
> SMS, notifications, two-way file transfer and clipboard sync are implemented end to end. v0.4.15 adds
> an optional Start with Windows setting; v0.4.14 uses the full logo in the Windows tray; v0.4.13 adds
> the app logo across both platforms; v0.4.12 removes the desktop menu bar; v0.4.11 gives both apps a
> strictly monochrome Material 3 theme; v0.4.10 adds contact names in SMS and calls; v0.4.9 adds a
> link-local IPv6 fallback for routers that isolate IPv4 Wi-Fi clients; v0.4.8 lets a phone re-pair with
> a fresh QR even when the PC still remembers an older pairing. Screen mirroring and Android app
> streaming are **not** — see [Roadmap](#roadmap). Everything is built by GitHub Actions; there are no
> local build steps.

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
| Contact names | ✅ | SMS threads and calls show names from the phone's contacts |
| MMS / new conversation | ⛔ | existing SMS threads only |
| Photo albums | ✅ | thumbnails, paged albums, large preview, original download |
| Battery status | ✅ | live percentage and charging state beside the connected phone |
| Built-in updates | ✅ | Windows download/restart flow; Android verified APK + system confirmation |
| Phone calls | ✅ control | dial, receive, answer, decline, hang up and phone-speaker toggle |
| Call audio on PC | ⛔ | audio deliberately stays on the phone; no capture or streaming |
| File transfer both ways | ✅ | 64 KiB chunks, 512 KiB window, Documents/Downloads |
| Clipboard sync | ✅ (text) | PC ⇄ phone, phone side only while the app is open |
| Start with Windows | ✅ | opt-in hidden tray start; appears in Windows Startup Apps |
| Auto-reconnect | ✅ | 2.5 s backoff, survives Wi-Fi hiccups |
| Screen mirror + control | ⛔ | planned — `MediaProjection` + WebRTC |
| App streaming, camera | ⛔ | planned |

## Architecture

```
┌──────────────────────────┐         mDNS (_linkphone._tcp)        ┌───────────────────────────┐
│  Android (Kotlin)        │◄─────────────────────────────────────►│  Windows (Electron)        │
│                          │                                      │                            │
│  NsdManager discovery    │         ws://192.168.x.x:8765        │  bonjour-service advert    │
│  OkHttp WebSocket ───────┼──────────────────────────────────────┼─► ws server                │
│  Calls + notif + SMS     │   AES-256-GCM frames (seq + AAD)     │  call controls / status    │
│  QR + photos + battery   │                                      │  gallery / files           │
│  Clipboard poller        │                                      │  tray + React UI           │
└──────────────────────────┘                                      └───────────────────────────┘
```

One encrypted WebSocket carries everything. See [docs/PROTOCOL.md](docs/PROTOCOL.md) for frames,
handshake and the threat model.

## Install (no builds on your machine)

1. Open the [latest GitHub Release](https://github.com/FakeAbid11/linkphone/releases/latest).
2. Install `linkphone-android.apk` on the phone (allow "install unknown apps").
   If upgrading from v0.1, uninstall it first and pair again: v0.1 used a temporary CI signing key.
   v0.2 established the permanent key, so v0.2 and later update in place normally.
3. Run `LinkPhone.Setup.0.4.15.exe` on Windows. The installer is unsigned, so SmartScreen requires
   **More info → Run anyway**.
4. On the PC, expand **Scan QR code instead** in the sidebar.
5. On the phone: **Scan QR** and point the camera at the PC. The original network Scan + typed code
   flow remains available as a fallback.
6. On the phone, open notification settings, then tap **Allow SMS access** and
   **Allow photo access**. Android 14+ can expose all photos or only a selected set. Optionally tap
   **Allow contact access** to see names instead of numbers in Messages and Calls.
7. For call controls, tap **Set as default phone app**, accept Android's role prompt, then allow
   phone permissions.

Both devices must be on the same Wi-Fi, and the network must allow mDNS and client-to-client
traffic. Guest/AP-isolated Wi-Fi will not work.

### Built-in updates

v0.4 must be installed manually once because older versions do not contain updater code. After that:

* **Windows:** Settings checks automatically every six hours. Choose **Download**, then
  **Restart & install**.
* **Android:** the App update card checks when LinkPhone opens. Choose **Download**, then **Install**.
  Android always shows its system installer confirmation and may first ask to allow installs from
  LinkPhone; a normal sideloaded app cannot bypass that screen.
* Android downloads are checked against GitHub's SHA-256 digest and the certificate that signed the
  installed LinkPhone app before the installer is opened.

### Phone calls

Android requires LinkPhone to be the **default phone/dialer app** for reliable incoming and active
call control. v0.4.5 includes a minimal phone-side dial/incoming/in-call screen and call notification,
so calls remain usable if Windows is offline. You can restore your previous default dialer at any
time in Android Settings → Apps → Default apps → Phone app.

Windows can dial, show incoming calls, answer, decline, hang up, and toggle the **phone's** speaker.
Microphone and call audio never cross the network. Do not depend on LinkPhone for emergency calling;
use the phone's emergency interface directly.

### Theme

Both apps use a strictly monochrome, dark-only Material 3 theme: pure black `#000000` background,
`#121212` cards, `#1C1C1C` elevated components, white primary text, soft gray `#B3B3B3` secondary
text, and white/gray Material Symbols Rounded icons. There is no light theme and no colorful accent.
The app logo is a white phone + laptop + chain-link mark on black, used for the Windows installer,
app, tray, Android adaptive icon, themed icon, and the in-app header.

### Start with Windows

Open **Settings → Start with Windows**. This is off by default. Enabling it registers a per-user login
item, so LinkPhone appears in **Windows Settings → Apps → Startup** and starts hidden in the tray when
you sign in. Disable it from either LinkPhone or Windows; LinkPhone will not re-enable it.

### If discovery finds nothing

QR pairing embeds every IPv4 address reported by the PC and tries them in private-network order, so
it also works when mDNS is blocked. Client-to-client traffic and TCP port 8765 must still be allowed.

### If pairing no longer works after a reinstall or forget

Starting with v0.4.8, scanning a fresh QR sends an explicit pairing intent. The PC then runs the
pairing handshake even for a phone it already knows, and replaces the old key once the new code
proves valid — so forgetting on the phone no longer requires forgetting on the PC first. If a
connection still fails, click the **×** on the phone card in the PC sidebar and tap **Forget** on
the phone, then pair again with the current code.

If the router blocks device-to-device IPv4 traffic (both devices can reach the router but not each
other), v0.4.9 also embeds the PC's link-local IPv6 address in the QR. The phone tries it after the
IPv4 addresses and pairs over Wi-Fi without any router change. USB tethering and the phone hotspot
remain fallbacks for networks that block both families.

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
* SMS and photo previews use the same encrypted channel and are never uploaded. Both permissions
  are optional; gallery thumbnails/previews are held only in PC memory. Originals are saved only
  when **Download original** is clicked.
* Contact names are resolved on the phone with the optional `READ_CONTACTS` permission and sent
  only over the encrypted LAN channel. No contact data is stored on the PC or uploaded.
* Updater checks contact only this repository's public GitHub Releases API. They send no phone data,
  account information or telemetry.
* Phone numbers, call state and control commands use the encrypted LAN channel. No call audio is
  captured, transmitted or stored.
* The PC stores the per-device key in `config.json` under `%APPDATA%\LinkPhone`; the phone stores it
  in `EncryptedSharedPreferences`.
* **Not yet hardened:** no TLS/pinning (a malicious LAN host could impersonate the PC), 6-digit codes
  accepted, no rate limiting on pairing attempts. Don't use this on a network you don't trust.

## Roadmap

1. **MMS + new conversations** — LinkPhone intentionally replies inside existing SMS threads only. It
   does not become the default SMS app, so messages sent from the PC are not inserted into Android's
   provider history by LinkPhone itself.
2. **Screen mirroring** — `MediaProjection` → hardware H.264 encoder → WebRTC; touch injection via an
   `AccessibilityService`.
3. **TLS + pinning**, manual IP entry, contact-name lookup, multi-device.

### Why iOS is out of scope

iOS exposes no SMS API, no notification access except ANCS over BLE, no screen capture of other
apps and no call control. A "Phone Link for iPhone" can only mirror notifications and make calls
over Bluetooth. Android-first is the only way to reach feature parity.

## License

MIT

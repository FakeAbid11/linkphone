# First-run test plan

v0.1 was verified on a real phone. The QR and SMS additions in v0.2 still need the same end-to-end
check after CI proves they compile. Work through this in order; each failure identifies a layer.

## 0. Before you start

* PC and phone on the **same Wi-Fi**. Not a guest network (AP isolation blocks both mDNS and the
  WebSocket), not a VPN that routes local traffic.
* Windows Firewall: allow `LinkPhone.exe` on **private** networks when prompted. If you dismissed the
  prompt, add an inbound rule for TCP **8765**.
* Phone: install `app-debug.apk`, allow "install unknown apps".

## 1. Desktop boots

Run `LinkPhone.exe` (or `npm run dev`).

* ✅ Window opens, sidebar shows `Pairing code XXXX XXXX`, `Port 8765`, and an IPv4 `Address`.
* ❌ Window never appears → check for a second instance in the tray.
* ❌ Address shows `—` → no active IPv4 adapter; the phone can still connect by IP if you add one
  manually (roadmap).

## 2. QR pairing

PC sidebar → **Scan QR code instead**. Phone → **Scan QR** and point it at the PC.

* ✅ Camera opens, detects the code, and status goes Connecting → Pairing → Connected.
* ❌ Camera does not open → grant Camera in Android Settings → Apps → LinkPhone → Permissions.
* ❌ Connects forever → Windows Firewall or none of the PC's advertised addresses is reachable.

## 3. Discovery fallback

Phone → **Scan**.

* ✅ Your PC appears as `LinkPhone-xxxxxxxx`.
* ❌ Nothing after 10 s: mDNS is blocked. Confirm with `adb shell ping <pc-ip>` that the phone can
  reach the PC at all, then check that the desktop's port is listening:
  `Get-NetTCPConnection -LocalPort 8765 -State Listen`.

## 4. Typed-code pairing fallback

Tap the PC, type the code from the sidebar (case-insensitive, no spaces).

* ✅ Status goes Connecting → Pairing → **Connected to <PC>**; the PC sidebar shows the phone as
  connected.
* ❌ Stuck on Connecting → wrong IP/port or firewall.
* ❌ "Wrong pairing code" → the code was regenerated between reading and typing (the desktop rotates
  it on demand), or one side rebuilt without the other. Regenerate once and retry.
* ❌ Works once, fails on reconnect → the stored key is bad. **Forget** on the phone and re-pair.

## 5. Notifications

Phone → **Open settings** → allow notification access for LinkPhone. Trigger a notification (send
yourself a message from another app).

* ✅ Desktop shows a Windows toast **and** an entry in the Notifications tab with app name, title and
  text.
* ✅ Tapping the toast opens the window and dismisses the entry.
* ✅ Dismissing on the phone removes it from the desktop list.
* ✅ **Reply** (on a messaging notification) sends the text from the phone's own app.
* ❌ Nothing arrives: notification access not granted, or only "ongoing" notifications were posted
  (those are filtered out on purpose — media players, downloads).

## 6. SMS

Phone → **Allow SMS access**. On the PC open **Messages**.

* ✅ Existing SMS threads load newest-first; opening one shows up to the latest 300 messages.
* ✅ Replying from the PC creates an optimistic outgoing bubble and changes from `sending` to sent.
* ✅ Send an SMS to the phone from another number: it appears live and triggers a Windows toast.
* ❌ Empty list → all three SMS permissions (read, send, receive) must be enabled on the phone.
* ❌ Send fails → check that the phone has an active SIM and a default SMS subscription.

v0.2 supports SMS replies in existing threads only. It does not support MMS or a new-recipient
composer. Because LinkPhone is not the default SMS app, its outgoing message is not written into the
system SMS database by LinkPhone; the PC keeps the optimistic bubble for the current session.

## 7. Files

**PC → phone:** Files tab → Send file → pick something.

* ✅ Progress bar advances; file appears in the phone's `Download/LinkPhone` (Files app → Downloads).
* ✅ Phone's Files card shows `↓ name` and `done`.

**Phone → PC:** phone → Send file → pick from the picker.

* ✅ File lands in `C:\Users\<you>\Downloads\LinkPhone\`.

A 100 MB file should take a few seconds on 5 GHz and must not stall: the sender keeps 512 KiB in
flight, the receiver acks every 512 KiB.

## 8. Clipboard

* Copy text on the PC → after ~1 s it should be on the phone's clipboard (paste somewhere to check).
* Copy text on the phone **while the LinkPhone screen is open** → appears on the PC clipboard.
* Phone → PC only works in the foreground; that is an Android 10+ restriction, not a bug.

## 9. Resilience

* Lock the phone for 5 minutes → the foreground service should keep the socket alive.
* Toggle Wi-Fi off/on → within ~3 s the phone should reconnect and the status returns to Connected.
* Change the PC's Wi-Fi network → the phone reconnects to the stored IP; if the PC's address changed
  you must re-pair.

## Debugging

```powershell
adb logcat -s LinkPhone:* AndroidRuntime:E
```

```powershell
# desktop: run from a terminal to see main-process logs
cd desktop; npm run dev
```

Common causes, in order of likelihood: firewall, AP isolation, notification access not granted,
pairing code rotated, phone's Wi-Fi is on a VPN.

Please report what fails with the logcat lines around the failure — the code is structured so each
subsystem logs under the `LinkPhone` tag.

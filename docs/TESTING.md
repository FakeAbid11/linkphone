# First-run test plan

Nothing here has been run on a real device yet — CI proves it compiles, not that it works. Work
through this in order; each step tells you what success looks like and what to check when it fails.

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

## 2. Discovery

Phone → **Scan**.

* ✅ Your PC appears as `LinkPhone-xxxxxxxx`.
* ❌ Nothing after 10 s: mDNS is blocked. Confirm with `adb shell ping <pc-ip>` that the phone can
  reach the PC at all, then check that the desktop's port is listening:
  `Get-NetTCPConnection -LocalPort 8765 -State Listen`.

## 3. Pairing

Tap the PC, type the code from the sidebar (case-insensitive, no spaces).

* ✅ Status goes Connecting → Pairing → **Connected to <PC>**; the PC sidebar shows the phone as
  connected.
* ❌ Stuck on Connecting → wrong IP/port or firewall.
* ❌ "Wrong pairing code" → the code was regenerated between reading and typing (the desktop rotates
  it on demand), or one side rebuilt without the other. Regenerate once and retry.
* ❌ Works once, fails on reconnect → the stored key is bad. **Forget** on the phone and re-pair.

## 4. Notifications

Phone → **Open settings** → allow notification access for LinkPhone. Trigger a notification (send
yourself a message from another app).

* ✅ Desktop shows a Windows toast **and** an entry in the Notifications tab with app name, title and
  text.
* ✅ Tapping the toast opens the window and dismisses the entry.
* ✅ Dismissing on the phone removes it from the desktop list.
* ✅ **Reply** (on a messaging notification) sends the text from the phone's own app.
* ❌ Nothing arrives: notification access not granted, or only "ongoing" notifications were posted
  (those are filtered out on purpose — media players, downloads).

## 5. Files

**PC → phone:** Files tab → Send file → pick something.

* ✅ Progress bar advances; file appears in the phone's `Download/LinkPhone` (Files app → Downloads).
* ✅ Phone's Files card shows `↓ name` and `done`.

**Phone → PC:** phone → Send file → pick from the picker.

* ✅ File lands in `C:\Users\<you>\Downloads\LinkPhone\`.

A 100 MB file should take a few seconds on 5 GHz and must not stall: the sender keeps 512 KiB in
flight, the receiver acks every 512 KiB.

## 6. Clipboard

* Copy text on the PC → after ~1 s it should be on the phone's clipboard (paste somewhere to check).
* Copy text on the phone **while the LinkPhone screen is open** → appears on the PC clipboard.
* Phone → PC only works in the foreground; that is an Android 10+ restriction, not a bug.

## 7. Resilience

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

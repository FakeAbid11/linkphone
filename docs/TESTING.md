# First-run test plan

The core v0.1 flow was verified on a real phone. Work through this after each release; CI proves the
code compiles and packages, while these checks cover phone/OEM behavior.

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
* ✅ If the router isolates IPv4 clients, v0.4.9 still connects over the PC's link-local IPv6
  address, which is tried after the IPv4 addresses.
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
* ❌ Works once, fails on reconnect → the stored key is bad. **Forget** on the phone, then scan a
  fresh QR. v0.4.8 re-pairs even while the PC still has the old pairing; if it still fails, click
  **×** on the PC device card and pair again.

## 5. Notifications

Phone → **Open settings** → allow notification access for LinkPhone. Trigger a notification (send
yourself a message from another app).

* ✅ Desktop shows a Windows toast **and** an entry in the Notifications tab with app name, title and
  text.
* ✅ Tapping the toast opens the window and dismisses the entry.
* ✅ Dismissing on the phone removes it from the desktop list.
* ✅ **Reply** (on a messaging notification) sends the text from the phone's own app.
* ✅ Notifications with actions show quick-action buttons (Open, Mark read, Archive, …); tapping one
  applies it on the phone.
* ✅ Tapping the notification body opens the source app on the phone. Without a tap intent the body is
  not clickable.
* ✅ **Reply** does not trigger Open; a notification with four or more actions shows Open plus up to
  three buttons.
* ✅ Dismissing the phone notification removes its action buttons from the PC.
* ❌ Quick actions or open do nothing: some OEMs block background activity starts. On HyperOS allow
  LinkPhone to autostart and display pop-up windows while running in the background.
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

## 6b. Contact names

Phone → **Allow contact access**. Open **Messages** and **Calls**.

* ✅ SMS threads, an open conversation, and incoming/active calls show the contact's name instead of
  the number.
* ❌ Numbers only → `READ_CONTACTS` was denied; messaging and calling still work with numbers.

## 6c. Icon and branding

- ✅ Windows installer, installed exe, taskbar/Alt-Tab, and tray show the phone + laptop + link mark.
- ✅ The sidebar brand mark is aligned with the "LinkPhone" title and looks crisp on the black sidebar.
- ✅ Android launcher icon is not clipped under circle or squircle masks; the Android 13 themed icon
  uses the same mark.
- ✅ The in-app header logo aligns with the title; the connection, ringing/in-call, and missed-call
  notifications show the gray phone + link mark at 24 dp.

## 7. Photos and albums

Phone → **Allow photo access**. On Android 14+, choose all photos or a selected set. On the PC open
**Photos**.

* ✅ Album cards show names, counts and cover thumbnails.
* ✅ Opening an album loads 48 photos at a time; **Load more** appends the next page.
* ✅ Clicking a photo opens a larger preview without saving it to disk.
* ✅ **Download original** starts a normal incoming transfer and saves the original file under
  `Downloads/LinkPhone`.
* ❌ “Photo permission not granted” → grant Photos and videos permission on the phone, then Refresh.
* ❌ A chosen photo is missing → Android 14 selected-photo access exposes only the items selected in
  the system permission dialog.

## 8. Battery

With the phone connected, inspect its card in the desktop sidebar.

* ✅ It shows a percentage and **Charging** while USB, wireless or AC charging is active.
* ✅ Plugging/unplugging power updates the card without reconnecting.
* ❌ No percentage → update both apps to v0.3 and reconnect once.

## 9. Calls

On the phone tap **Set as default phone app**, accept Android's role prompt and grant Phone access.
On Windows open **Calls**.

* ✅ Enter a non-emergency test number and press **Call**; the phone places the call and Windows shows
  dialing/active state.
* ✅ Call the phone from another number; both phone and Windows show the incoming call.
* ✅ **Answer**, **Decline**, and **Hang up** work from Windows.
* ✅ **Phone speaker** switches audio from the phone earpiece to its loudspeaker; switching it off
  returns to the earpiece.
* ✅ No microphone or call audio is heard on or sent to the PC.
* ✅ Disconnect/close Windows and make an incoming call; the phone's own call notification and screen
  still allow answering or declining.
* ❌ “Set LinkPhone as the default phone app” → complete the role card in the Android app first.

Do not use LinkPhone call control to test emergency numbers. Restore the previous dialer under
Android Settings → Apps → Default apps → Phone app if you no longer want LinkPhone handling calls.

## 9b. Contacts and recent calls

On the phone grant **Contact access** and **Call history** (Call logs), then open **Contacts** and
**Calls** on Windows.

* ✅ Contacts lists names, numbers and labels; search matches both names and digits.
* ✅ The call button places a call through the phone (it is disabled until the default-phone card is
  complete).
* ✅ **Message** opens an existing SMS conversation; a number with no thread shows
  “No SMS thread yet — start one on the phone.”
* ✅ Recent calls shows direction, relative time and talk duration; the call button redials.
* ✅ **Refresh** repopulates both tabs; reopening either tab also refreshes.
* ✅ Revoke Call logs in Android settings and refresh: the PC explains that call history is off
  instead of showing an empty list.
* ❌ Contacts refused → allow Contact access on the phone and refresh.
* ❌ After upgrading only the PC, the lists stay empty with an update hint; install the matching APK.
* ✅ A phone book with 500+ contacts scrolls and searches without visible stalls; only the first 200
  matches render.

## 10. Files

**PC → phone:** Files tab → Send file → pick something.

* ✅ Progress bar advances; file appears in the phone's `Download/LinkPhone` (Files app → Downloads).
* ✅ Phone's Files card shows `↓ name` and `done`.

**Phone → PC:** phone → Send file → pick from the picker.

* ✅ File lands in `C:\Users\<you>\Downloads\LinkPhone\`.

A 100 MB file should take a few seconds on 5 GHz and must not stall: the sender keeps 512 KiB in
flight, the receiver acks every 512 KiB.

## 11. Clipboard

* Copy text on the PC → after ~1 s it should be on the phone's clipboard (paste somewhere to check).
* Copy text on the phone **while the LinkPhone screen is open** → appears on the PC clipboard.
* Phone → PC only works in the foreground; that is an Android 10+ restriction, not a bug.

## 12. Built-in updates

v0.4 is the first updater-enabled build. Use it to test the v0.4.5 update:

* ✅ Windows v0.4 detects v0.4.5, downloads with progress, and **Restart & install** relaunches it.
* ✅ Android v0.4 detects v0.4.5, downloads the APK, verifies SHA-256 + signing certificate, then opens the
  Android installer confirmation.
* ❌ Android opens “Install unknown apps” first → allow LinkPhone and tap **Install** again. This is
  required once by Android and cannot be skipped by the app.

## 13. Resilience

* Lock the phone for 5 minutes → the foreground service should keep the socket alive.
* Toggle Wi-Fi off/on → within ~3 s the phone should reconnect and the status returns to Connected.
* Change the PC's Wi-Fi network → the phone reconnects to the stored IP; if the PC's address changed
  you must re-pair.

## 14. Start with Windows

* ✅ **Settings → Start with Windows** turns on; `HKCU\...\Run` has `LinkPhone` =
  `"...\LinkPhone.exe" --hidden`.
* ✅ Windows Settings → Apps → Startup and Task Manager → Startup list LinkPhone as On.
* ✅ Sign out/in starts LinkPhone with only the tray icon, no window.
* ✅ Launching it manually while hidden opens the window.
* ✅ Toggle off removes the Run and StartupApproved values; disabling it in Windows Settings instead is
  respected and not re-enabled by LinkPhone.
* ✅ Uninstall removes both registry values and the Startup Apps entry.

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

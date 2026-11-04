# First-run test plan

The core v0.1 flow was verified on a real phone. Work through this after each release; CI proves the
code compiles and packages, while these checks cover phone/OEM behavior.

## 0. Before you start

* PC and phone on the **same Wi-Fi**. Not a guest network (AP isolation blocks both mDNS and the
  WebSocket), not a VPN that routes local traffic.
* Windows Firewall: allow `LinkPhone.exe` on **private** networks when prompted. If you dismissed the
  prompt, add an inbound rule for TCP **8765**.
* Phone: install `app-debug.apk`, allow "install unknown apps".

## 0b. Android onboarding (v0.7.5+)

Clear LinkPhone app data (or install fresh), then launch it with the PC running.

* ✅ Step 1 explains LinkPhone and how the link works; **Next**, **Back** and the dots track the
  three steps.
* ✅ Step 2 lists Notifications, Messages, Contacts, Photos, Calls, Call history and Background with
  a reason each. Granting SMS/contacts/photos/call history changes the row to **Allowed**; the
  Notification row opens system settings and updates after returning; denying leaves the button
  usable. The battery row opens the exemption dialog or the system optimization list.
* ✅ Granting Contacts on step 2 and then opening Calls → Contacts on the PC shows the phone book
  immediately, with no five-minute wait or extra **Refresh**.
* ✅ The Android 13+ notification permission is requested when step 2 first opens.
* ✅ Step 3 pairs with the PC: **Scan QR** closes onboarding on success, and the network list plus
  typed code also work. **Pair later** opens the main screen unpaired; **Skip** works from any step.
* ✅ Relaunching, force-stopping/reopening or rebooting does not show onboarding again, and a later
  **Forget** on the main screen does not replay it.
* ✅ Upgrading an install that already has a pairing never shows onboarding.
* ❌ Onboarding repeats on every launch → `onboardingDone` is not persisting; check app data or the
  SharedPreferences fallback.
* ❌ Pairing from step 3 does not close onboarding → confirm the connection reaches **Connected** and
  that the desktop pairing code is current.

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
* ✅ Tapping the toast opens the window and dismisses the entry **and** the phone notification.
* ✅ Dismissing a card on the PC removes it from the phone shade; dismissing on the phone removes it
  from the desktop list.
* ✅ **Clear** on an app header and **Clear all** dismiss the matching notifications from the phone.
* ✅ With the phone disconnected, dismissing on the PC hides the card without an error.
* ✅ Dismiss on the PC, then let the app repost the same notification key → it stays hidden on the
  PC; a genuine phone-side dismissal still removes it.
* ✅ Disconnect/reconnect while on Calls → contacts and call history reload without showing “off”.
* ✅ Grant Contact access while Calls → Contacts is open → the list appears without pressing Refresh.
* ✅ An ongoing/system-locked notification that Android refuses to cancel stays in the phone shade;
  its PC card is hidden.
* ✅ **Reply** (on a messaging notification) sends the text from the phone's own app.
* ✅ Notifications with actions show quick-action buttons (Open, Mark read, Archive, …); tapping one
  applies it on the phone.
* ✅ Tapping the notification body opens the source app on the phone. Without a tap intent the body is
  not clickable.
* ✅ **Reply** does not trigger Open; a notification with four or more actions shows Open plus up to
  three buttons.
* ✅ Dismissing the phone notification removes its action buttons from the PC.
* ✅ A download/update notification shows a moving progress bar and percentage; the card updates in
  place without moving or showing another toast.
* ✅ An indeterminate “preparing” notification shows an animated bar without a percentage.
* ✅ Dismissing a live progress card on the PC keeps it hidden while the phone keeps updating it;
  removing it on the phone allows a later repost.
* ✅ Search matches app names, titles and text; **Newest** and **By app** show the same filtered set.
* ✅ By-app headers show correct counts; **Clear** removes that app's cards and later new
  notifications still appear.
* ✅ **Mute** removes the app's cards, suppresses future cards and toasts, and survives a PC restart;
  **Unmute** from the Muted chips lets new notifications through.
* ✅ Live progress updates in both views; an in-progress reply survives switching views.
* ✅ Ongoing media/navigation notifications without progress remain filtered out.
* ❌ Quick actions or open do nothing: some OEMs block background activity starts. On HyperOS allow
  LinkPhone to autostart and display pop-up windows while running in the background.
* ❌ Nothing arrives: notification access not granted, or only “ongoing” notifications without
  progress were posted (media players are filtered out on purpose).

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

Do **not** set LinkPhone as the default phone app. Grant **Allow call access** on the phone (this
includes `ANSWER_PHONE_CALLS`) and keep notification access on. On Windows open **Calls**.

Best-effort no-role control:

* ✅ The PC dialer **Call**, recent-call redial and contact call buttons are enabled.
* ✅ Dialing opens the phone's system call UI and places the call; once the system dialer posts its
  ongoing call notification, the PC shows the active call card.
* ✅ **Hang up** attempts `TelecomManager.endCall()`; the card clears when the system notification
  disappears. If the OEM blocks it, a visible `call.error` appears and the phone still controls the
  call.
* ✅ An incoming call produces a ringing card; **Answer** uses `acceptRingingCall` and **Decline**
  ends the ringing call. OEM blocks show an error instead of a false success.
* ✅ **Phone speaker** attempts to route communication audio to the speaker and reports the requested
  state. If routing is blocked, the call stays controllable on the phone and an error is shown.
* ✅ Deny `ANSWER_PHONE_CALLS` → the live card shows disabled Answer/Decline/Hang up/Speaker controls
  with “Allow call access on your phone”; dialing still works.
* ✅ Turn notification access off → the live card still appears from the phone’s call state
  (caller may show “Phone call”); Answer/Decline/Hang up/Speaker use the fallback.
* ✅ Revoke `READ_PHONE_STATE` and turn notification access off → no synthetic live card; the
  “Call sent to your phone” confirmation remains until dismissed.
* ✅ Hide or disable the dialer’s call notification while keeping notification access → the
  telephony fallback still creates the card.
* ✅ End the call → the phone call state returns to idle and the PC card clears.
* ✅ `adb logcat -s LinkPhone` shows `call notification …` and `telephony call state=…` lines for
  diagnostics.
* ✅ `CALL_PHONE` denied → the PC call buttons disable and the phone card asks for call access.
* ✅ No “Set as default phone app” text appears on the PC or phone.
* ✅ A missed-call notification does not create a ringing/active PC card; a real incoming call does.
* ✅ Place a call, dismiss “Call sent to your phone”, switch tabs and return → it stays dismissed;
  it also does not reappear after the call ends.
* ✅ Without call access the “Allow call access” warning appears only once.
* ✅ Regression: if LinkPhone already holds the default-phone role, the real InCallService card and
  controls still work and synthetic notification state is ignored.

Do not use LinkPhone call control to test emergency numbers. The system dialer remains the owner of
the call; if a best-effort control fails, finish the call on the phone.

## 9b. Contacts and recent calls

On the phone grant **Contact access** and **Call history** (Call logs), then open **Calls** on
Windows. Recent calls and contacts share the column beside the dialer; there is no separate
Contacts tab.

* ✅ At the default window the dialer/active call is on the left; the right column starts with the
  **Recent / Contacts** switch and shows Recent calls. Nothing appears below the dialer.
* ✅ **Contacts** swaps the right column to the contact search and list; **Recent** swaps back.
* ✅ Below 1000 px the layout stacks as dialer/active call → switch and selected panel.
* ✅ Contacts lists names, numbers and labels; search matches both names and digits. Granting or
  revoking Contact access in Android settings while LinkPhone is open reloads or clears the list.
* ✅ The call button places a call through the phone (it is disabled until call access is granted).
* ✅ **Message** opens an existing SMS conversation; a number with no thread shows
  “No SMS thread yet — start one on the phone.”
* ✅ Recent calls shows direction, relative time and talk duration; the call button redials.
* ✅ Each panel's **Refresh** reloads only that panel; reopening Calls also refreshes both.
* ✅ An incoming or active call replaces the dialer on the left while the switch and side panel stay
  usable.
* ✅ Revoke Call logs in Android settings and refresh: the PC explains that call history is off
  instead of showing an empty list.
* ❌ Contacts refused → allow Contact access on the phone and refresh.
* ❌ After upgrading only the PC, the lists stay empty with an update hint; install the matching APK.
* ✅ A phone book with 500+ contacts scrolls and searches without visible stalls; only the first 200
  matches render.

## 9c. Dual SIM

Use a phone with two active SIMs (or an eSIM plus a physical SIM). Grant **Phone access** if the
phone offers the “Choose a SIM” card.

* ✅ Messages and Calls show a SIM picker only when more than one SIM/account is available.
* ✅ Sending a text from each SIM uses that SIM; verify the sender on a second phone.
* ✅ Incoming messages show the receiving SIM, and replying defaults to that SIM.
* ✅ Placing a call from each SIM/account works; active calls and recent calls show the SIM.
* ✅ Contact call buttons and recent-call redial use the SIM selected in Calls.
* ✅ Deny Phone access: the pickers disappear and texts/calls still use the phone default.
* ✅ Disable/remove a SIM while connected: the next send/call reports a clear error and the list
  refreshes.
* ✅ A single-SIM phone shows no picker and behaves as before.

## 10. Files

**PC → phone:** Files tab → Send file → pick something.

* ✅ Progress bar advances; file appears in the phone's `Download/LinkPhone` (Files app → Downloads).
* ✅ Phone's Files card shows `↓ name` and `done`.

**Phone → PC:** phone → Send file → pick from the picker.

* ✅ File lands in `C:\Users\<you>\Downloads\LinkPhone\`.

A 100 MB file should take a few seconds on 5 GHz and must not stall: the sender keeps 512 KiB in
flight, the receiver acks every 512 KiB.

## 10b. Send to from Explorer

Install v0.7.8, then right-click a file in Explorer → **Send to → LinkPhone** (Windows 11 may require
**Show more options**).

* ✅ The SendTo menu lists LinkPhone with the app icon.
* ✅ One file, including spaces/Unicode in its name, arrives in the phone's `Download/LinkPhone`;
  the PC opens the Files tab.
* ✅ Selecting several files sends them all.
* ✅ With LinkPhone hidden in the tray, SendTo focuses the window on Files and sends; no second
  process appears.
* ✅ With LinkPhone closed, SendTo cold-starts it, waits for the phone to reconnect, then sends.
* ✅ With the phone disconnected, one “queued” toast appears; connecting sends the files once.
* ✅ Selecting only a folder skips it without an error or crash.
* ✅ Updating from v0.6.1 keeps the shortcut; uninstalling removes it.

## 10c. Drag and drop

Drag files from Explorer onto any part of the LinkPhone window.

* ✅ A full-window “Drop files to send to your phone” overlay appears and disappears when the drag
  leaves the window, without flicker.
* ✅ Dropping one file opens the Files tab and sends it; dropping several sends them all.
* ✅ Dropping only a folder or a virtual file shows a “can’t be sent” notice and sends nothing.
* ✅ Text or non-file drags show no overlay and do not navigate the window.
* ✅ Dropping more than 100 files sends at most 100 and reports the accepted count.
* ✅ With the phone disconnected, the existing queued toast appears and the files send on connect.
* ✅ Send-to, the Send file dialog, and second-instance handling still work.

## 11. Clipboard

* Copy text on the PC → after ~1 s it should be on the phone's clipboard (paste somewhere to check).
* Copy text on the phone **while the LinkPhone screen is open** → appears on the PC clipboard.
* Phone → PC only works in the foreground; that is an Android 10+ restriction, not a bug.

## 12. Built-in updates

v0.4 is the first updater-enabled build. Use it to test the v0.4.5 update:

* ✅ Windows v0.4 detects v0.4.5, downloads with progress, and **Restart & install** relaunches it.
* ✅ Windows **Restart & install** runs the NSIS installer silently (no wizard), then relaunches the
  new version; the sidebar shows the new version.
* ✅ Download an update, close LinkPhone normally → no installer process starts; reopen and use
  **Restart & install** when ready.
* ✅ A first-time install still shows the assisted installer and lets you choose a directory.
* ✅ Android v0.4 detects v0.4.5, downloads the APK, verifies SHA-256 + signing certificate, then opens the
  Android installer confirmation.
* ❌ Android opens “Install unknown apps” first → allow LinkPhone and tap **Install** again. This is
  required once by Android and cannot be skipped by the app.

Automatic scans (v0.6.5+):

* ✅ Windows shows one update notification after an automatic check finds a newer release; clicking
  it opens Settings, and a second check of the same version does not notify again.
* ✅ A manual check from a focused Settings window updates the panel without an extra toast.
* ✅ Android posts one update notification from the background worker, tapping it opens LinkPhone,
  and repeated worker runs for the same version do not re-post it.
* ✅ Denying Android notification permission does not crash; the in-app card still checks on open.
* ✅ The update card’s **Check**, **Download** and **Install** paths are unchanged; neither platform
  auto-downloads.
* ❌ No background notification within six hours → some OEM battery managers delay WorkManager; open
  LinkPhone to check immediately.

## 13. Background and boot (v0.7.2+)

* ✅ Open LinkPhone, pair, then minimize and lock the screen → the persistent LinkPhone
  notification stays and notifications, texts and calls keep arriving on the PC.
* ✅ The **Background connection** card appears while the app is battery-optimized; tap **Allow
  background**, accept the system dialog → the card disappears and
  `adb shell dumpsys deviceidle whitelist | findstr dev.linkphone.app` lists the package.
* ✅ Decline the exemption → the card stays, a notice explains the manual step, and the app keeps
  working.
* ✅ On a ROM that rejects `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` → the fallback opens the
  battery-optimization list and the notice explains what to choose.
* ✅ Reboot with a paired phone → the service starts on its own, the notification appears, and the
  PC reconnects without opening LinkPhone. On Xiaomi/HyperOS this requires Autostart to be enabled.
* ✅ Reboot with no pairing → no service and no notification.
* ✅ Force-stop LinkPhone, then reboot → no start (Android behavior); opening the app starts it.
* ✅ `adb shell dumpsys activity services dev.linkphone.app` shows `LinkService` with the
  `connectedDevice` type; leaving it running for more than six hours does not trigger the old
  `dataSync` timeout.
* ✅ Screen off overnight with the exemption → the PC still shows the phone connected. Without it,
  Doze delays are expected rather than a bug.
* ✅ Clipboard phone→PC still requires LinkPhone in the foreground.

## 14. Auto reconnect (v0.7.3+)

* ✅ Pair once, force-stop LinkPhone, then open it → the service starts and the connection returns
  without tapping **Reconnect**.
* ✅ Restart the PC without changing its address → the phone reconnects on the next retry.
* ✅ Change the PC's address (disable/enable its Wi-Fi or reserve another DHCP lease) → within
  ~20 s the phone rediscovers it through mDNS and reconnects; the pairing is unchanged and no QR is
  needed.
* ✅ With two LinkPhone PCs on the LAN, only the one whose mDNS TXT `id` matches the saved
  `serverId` is used.
* ✅ With the PC off, retries back off from 2.5 s to 15 s instead of hammering the old address;
  turning the PC on reconnects within one cycle.
* ✅ **Forget this phone** on the PC, then let the phone retry → it stops with the existing
  "PC no longer knows this phone" message; a fresh QR is still required.
* ✅ Block mDNS/AP client isolation → auto discovery finds nothing, but **Scan** still lists the PC
  and manual pairing works.
* ✅ Opening the app with no pairing still starts nothing and shows no notification.

## 15. Resilience

* Lock the phone for 5 minutes → the foreground service should keep the socket alive.
* Toggle Wi-Fi off/on → within ~3 s the phone should reconnect and the status returns to Connected.
* Change the PC's Wi-Fi network → the phone rediscovers the paired PC by `serverId`; a different
  subnet (a different LAN) still needs a manual scan.

## 16. Start with Windows

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

# LinkPhone protocol v1

Transport is a plain WebSocket (`ws://host:8765/`) on the local network. There is **no TLS** in v1:
everything after the handshake is encrypted at the application layer with AES-256-GCM, which gives
the same payload confidentiality without certificate provisioning. TLS + certificate pinning is on
the roadmap.

## 1. Discovery

The desktop advertises an mDNS service:

| Field | Value |
|---|---|
| Service type | `_linkphone._tcp` |
| Port | 8765 (8766…8775 if taken) |
| TXT `id` | server UUID |
| TXT `name` | PC hostname |
| TXT `ver` | `1` |

The Android app browses with `NsdManager`, resolves the SRV record and connects to the IP:port.
Since v0.7.3 a paired phone also uses TXT `id` to recognize the same PC after its address changes:
only a resolved service whose `id` matches the saved `serverId` is used for automatic reconnect.
The key handshake below still authenticates the socket.

The server binds dual-stack (`::`), so IPv4-mapped and IPv6 clients share the same port. The QR's
`a6` field carries link-local IPv6 addresses for routers that isolate IPv4 Wi-Fi clients but still
forward IPv6 between them.

## 2. Pairing key

Both sides derive a 32-byte pre-shared key from the 8-character pairing code shown on the PC:

```
salt = 16 random bytes (sent by the desktop in hello.result)
psk  = PBKDF2-HMAC-SHA256(normalize(code), salt, 600_000, 32)
```

`normalize` uppercases and strips non-alphanumerics. The alphabet is `A-Z` minus `I/O` plus
`2-9` (32 symbols), so 8 characters = 40 bits. A 6-digit numeric code is accepted by the desktop as
well, for convenience.

### QR payload

The desktop can encode the same pairing data as a QR code (error correction level M):

```
linkphone://pair?c=<code>&p=<port>&a=<comma-separated IPv4s>
                  &a6=<comma-separated link-local IPv6s>
                  &n=<PC name>&i=<server UUID>&v=1
```

All values are URL-encoded. The Android app prefers RFC 1918 addresses, then tries every remaining
IPv4 address, then the `a6` link-local IPv6 addresses. Link-local IPv6 is scoped to the phone's
Wi-Fi interface (OkHttp resolves a synthetic hostname to a scoped `Inet6Address`). This is a
fallback for routers that isolate IPv4 clients but still forward IPv6 between them. The QR is only
a transport for the pairing code and addresses; the cryptographic handshake below is unchanged.
Camera scanning uses CameraX + ZXing and has no Google Play Services dependency.

## 3. Frames

Before authentication, control messages are plaintext JSON **text frames**:

```json
{ "t": "hello", "proto": 1, "deviceId": "…", "deviceName": "Pixel 8", "platform": "android", "pair": true }
```

`pair` is optional. A client sets it when the user explicitly started pairing (QR scan or typed
code), which lets the desktop re-pair a device it already knows.

After authentication every frame is a **binary frame**:

```
nonce(12) || AES-256-GCM(plaintext) || tag(16)
AAD = "lp1:" + direction + ":" + seq(8, big endian)
direction ∈ {"c2s", "s2c"}
```

`seq` starts at 1 and increments for every encrypted frame in that direction. A receiver rejects a
frame whose `seq` is not exactly `last + 1`.

Plaintext payloads start with a kind byte:

| Kind | Payload |
|---|---|
| `0x10` | UTF-8 JSON control message |
| `0x20` | file chunk: `idLen(1) ‖ id ‖ offset(8) ‖ data` |

## 4. Handshake

```
client → hello                     { deviceId, deviceName, platform, pair? }
server → hello.result              { needPair, needAuth, salt?, serverId, serverName }
```

**First pairing**

```
client → pair.request   { proof = HMAC(psk, "pair:" + deviceId) }   (plaintext)
server → pair.ok                                                    (encrypted, seq 1)
```

On a proof mismatch the server answers with a plaintext `{ "t": "pair.reject" }` and closes with
code 4001 — a text frame at that point is unambiguous, because an authenticated peer only ever
sends binary.

**Re-pairing a known device.** If `hello` carries `pair: true`, the server always replies with a
fresh salt and `needPair: true`, even when it already stores a key for that `deviceId`. The proof
must still match the current pairing code; on success the new key replaces the old one. This is how
a phone that lost or forgot its key recovers without the user having to forget the device on the PC.
An old client that omits `pair` keeps the original behaviour.

**Reconnecting** (device already paired)

```
server → auth.challenge  { nonce = 32 random bytes, base64 }
client → auth.response   { proof = HMAC(psk, "auth:" + nonce) }      (plaintext)
server → auth.ok                                                     (encrypted, seq 1)
```

If the device is no longer paired on the server, or the proof does not match, the server sends a
plaintext `{ "t": "auth.reject", "reason": "not-paired" | "bad-proof" }` before closing with 4003
or 4001. The phone clears its stale local pairing and asks the user to scan a fresh QR.

The key used here is the one stored at pairing time, not a fresh PBKDF2 derivation.

A paired client that cannot reach its saved address rediscovers the PC through mDNS and retries the
stored-key handshake against the newly resolved address. This changes only where the client points
the socket; the frames above are unchanged.

## 5. Control messages

| `t` | Direction | Fields |
|---|---|---|
| `notif.post` | phone → PC | `notif{key,app,pkg,title,text,when,canReply,actions[],progress?,progressMax?,indeterminate?,ongoing?}` |
| `notif.dismiss` | phone → PC | `key` |
| `notif.action` | PC → phone | `key`, `actionId`, `replyText?` (≤2000 chars), `label?` (echo of the button label) |
| `notif.action.result` | phone → PC | `key`, `ok`, `error?` (`gone`, `unavailable`, `no-action`, `no-reply`, `action-changed`, `blank`, `too-long`, `blocked`) |
| `notif.cancel` | PC → phone | `keys[]` — dismiss these notifications in the phone shade (best effort) |
| `clip.set` | both | `text`, `ts`, `manual?` (phone→PC Pull response; accepted even when automatic sync is off) |
| `clip.enabled` | PC → phone | `enabled` — the desktop automatic-sync toggle |
| `clip.request` | PC → phone | — ask the phone to read and return its clipboard |
| `clip.error` | phone → PC | `reason` (`offline`, `unavailable`, `empty`, `too-large`) |
| `file.offer` | both | `id`, `name`, `size`, `mime`, `target?` (`downloads`/`pictures`/`videos`), `resumeFrom?`, `mtimeMs?`, `photoId?`, `mediaKind?` |
| `file.accept` | both | `id`, `offset?` (bytes already received; absent means 0) |
| `file.decline` | both | `id`, `reason?` |
| `file.ack` | receiver → sender | `id`, `offset` |
| `file.done` | sender → receiver | `id` |
| `file.cancel` | both | `id`, `reason?` |
| `storage.list.request` | PC → phone | `reqId`, `cursor?` |
| `storage.list` | phone → PC | `reqId`, `entries[{id,name,size,mime,modified}]`, `scanLimited`, `granted` |
| `storage.pull` | PC → phone | `id`, `name?`, `mime?` — the phone replies with a normal `file.offer` |
| `storage.error` | phone → PC | `op`, `id?`, `message` |
| `sms.threads.request` | PC → phone | — |
| `sms.threads` | phone → PC | `threads[{threadId,address,name?,snippet,date,count}]` |
| `sms.thread.request` | PC → phone | `threadId` |
| `sms.thread` | phone → PC | `threadId`, `address`, `name?`, `messages[]` |
| `sms.send` | PC → phone | `tempId`, `address`, `body` |
| `sms.sent` | phone → PC | `tempId`, `ok`, `error?` |
| `sms.new` | phone → PC | `message{id,threadId,address,name?,body,date,outgoing}` |
| `sms.error` | phone → PC | `op`, `error` |
| `phone.ring` | PC → phone | `durationMs?` (user setting: 5/10/15/30 s, capped to 30 s) |
| `device.rename` | PC → phone | `name` (1–32 chars) |
| `device.rename.state` | phone → PC | `name` — confirms a PC rename or reports a phone-side rename |
| `phone.ring.stop` | PC → phone | — |
| `phone.ring.state` | phone → PC | `ringing` |
| `media.state` | phone → PC | `title`, `artist`, `playing`, `app`, `positionMs?`, `durationMs?`, `volume?`, `volumeMax?`, `artId?` |
| `media.action` | PC → phone | `action` = `play` \| `pause` \| `next` \| `previous` \| `seek` \| `volume`, plus `positionMs?` or `level?` |
| `media.art` | phone → PC | `id`, `mime`, `data` (base64 JPEG ≤ 64 KB binary, ~256 px; empty id/data clears) |
| `device.status` | phone → PC | `batteryLevel`, `charging` |
| `sims.request` | PC → phone | — |
| `sims.sync` | phone → PC | `sms[{id,label,carrier?,slot?,isDefault?}]`, `call[{id,label,isDefault?}]`, `granted` |
| `gallery.albums.request` | PC → phone | `kind?` (`image`/`video`) |
| `gallery.albums` | phone → PC | `albums[{id,name,count,coverPhotoId,kind}]`, `kinds[]` |
| `gallery.photos.request` | PC → phone | `albumId`, `offset`, `limit`, `kind?` |
| `gallery.photos` | phone → PC | `albumId`, `offset`, `photos[{...,kind,durationMs?}]`, `hasMore` |
| `gallery.thumbnails.request` | PC → phone | `photoIds[]` (maximum 24) |
| `gallery.thumbnails` | phone → PC | `items[{photoId,mime,data}]` |
| `gallery.preview.request` | PC → phone | `photoId` |
| `gallery.preview` | phone → PC | `photoId`, `mime`, `data` |
| `gallery.download` | PC → phone | `photoId`, `kind?` — the phone replies with a `file.offer` carrying `photoId` |
| `gallery.error` | phone → PC | `op`, `error` |
| `call.state` | phone → PC | `calls[]` (`id`, `number`, `displayName`, `phase`, `incoming`, `startedAt`, `simLabel?`, `slot?`), `dialerReady`, `outgoingReady`, `controlReady`, `speaker`, `muted`, `route?` |
| `call.place` | PC → phone | `number` |
| `call.answer` | PC → phone | `callId` |
| `call.reject` | PC → phone | `callId` |
| `call.disconnect` | PC → phone | `callId` |
| `call.speaker` | PC → phone | `enabled` — device-wide best effort |
| `call.mute` | PC → phone | `enabled` — device-wide best effort; needs the call-control service or `MODIFY_AUDIO_SETTINGS` |
| `call.error` | phone → PC | `error` |
| `contacts.request` | PC → phone | — |
| `contacts.sync` | phone → PC | `contacts[{id,name,numbers[{number,label}]}]`, `granted` |
| `calllog.request` | PC → phone | `limit?` (1–200) |
| `calllog.sync` | phone → PC | `entries[{id,number,name?,type,date,duration}]`, `granted` |
| `ping` / `pong` | both | `ts` |

### Files, resume and phone storage

- Gallery requests carry `kind` (`image` or `video`); `gallery.albums` advertises `kinds` so a new PC
  can hide Videos for an older phone. Video items include `durationMs`.
- `gallery.download` passes the kind; the resulting `file.offer` includes `photoId` and `mediaKind`
  so the PC can correlate the download with the video and enable inline playback.
- A receiver keeps a partial file and replies to a resumed `file.offer` with `file.accept.offset`.
  A sender includes `resumeFrom` only when it can resume, so an older receiver can start at zero
  without breaking the offset check.
- `target: "pictures"` asks the phone to file an image in `Pictures/LinkPhone` under MediaStore
  Images. Older phones ignore it and use Downloads.
- `storage.list` lists only MediaStore Downloads. On Android 13+ that means files owned by
  LinkPhone; `scanLimited` tells the PC to show that limitation. `storage.pull` asks the phone to
  send the file through the normal `file.offer` pipeline.

### Inline media

Downloaded videos are served to the renderer through the read-only `linkphone-media://media/<id>`
scheme. The main process accepts transfer ids only, requires a completed incoming transfer under the
downloads folder, and answers with byte-range responses for seeking.

### Notifications

`notif.post.actions` is a list of `{id, label, reply}`. The phone emits at most four actions:
`content` (the notification's tap intent, when one exists) first, then up to two ordinary buttons,
then the free-form reply action last so a reply can never be dropped by the cap.

Action ids come in two forms:

* **v2** (the PC advertised `notif.action.v2` in `hello.result.caps`): `content`, `a<index>` for an
  ordinary button, and `reply` for the free-form RemoteInput action. `reply` is resolved by meaning
  on the phone — it always fires the action that carries a free-form RemoteInput, even if the app
  reordered its actions since the notification was posted.
* **legacy** (older PCs): the original zero-based platform index, as before.

`reply: true` marks the action used by `notif.action.replyText`; text is trimmed, capped at 2000
characters, and rejected when blank. The PC echoes the button's `label` on ordinary actions; if the
resolved action no longer matches, the phone answers `action-changed` instead of firing the wrong
button. `notif.action.result` is only sent to PCs that advertise `notif.action.result` in
`hello.result.caps`; older desktops keep the fire-and-forget behaviour.

Progress notifications may include `progress`, `progressMax`, `indeterminate` and `ongoing`.
`notif.post` with the same `key` updates the existing PC entry in place. The phone only sends
progress-bearing notifications after the PC advertises the `notif.progress` capability in
`hello.result.caps`; older PCs therefore receive no progress frames and no update notifications.

### Clipboard

Clipboard sync is plain text only and capped at 256 KB. Phone→PC automatic reads require
LinkPhone to be in the foreground or the optional clipboard AccessibilityService to be enabled;
PC→phone writes work while the phone app is backgrounded. The service reads clipboard text only,
never window content. Android 12+ may show a clipboard-access notice once per background read.

### SMS

The phone reads the `Telephony.Sms` provider after runtime `READ_SMS` permission and sends through
`SmsManager` after `SEND_SMS`. `RECEIVE_SMS` drives live `sms.new` events. LinkPhone does not claim
the default-SMS-app role and does not write provider rows. MMS remains unsupported. A successful
`sms.sent` means Android accepted the send request; it is not a carrier delivery receipt.

To start a conversation for an address with no provider thread, the PC uses
`threadId = "addr:<number>"`. The phone resolves that id with
`messagesForAddress(number)`, so existing provider messages are returned when present. Outgoing-only
conversations are cached locally on the PC (`sms-local.json`) and merged with provider threads on
refresh; the phone's provider rows always win when both exist.

Thread and message records may include `name`, resolved from the phone's contacts with the optional
`READ_CONTACTS` permission. The PC displays it instead of the number; the address is still present
for replies.

### Photos and albums

After runtime photo permission, the phone queries Android `MediaStore.Images` read-only. Albums are
MediaStore buckets plus a synthetic `__all__` album. Photo lists are paged in groups of 48. The PC
requests thumbnails in batches of at most 24 and a 1600 px JPEG preview only when the user opens an
image. JPEG bytes are base64 inside the already encrypted control frame and are kept in PC memory.

`gallery.download` does not put the original in a JSON message: it starts the existing chunked file
transfer from the phone, preserving the original bytes and filename in `Downloads/LinkPhone`.
LinkPhone never modifies or deletes the phone's MediaStore rows.

### Battery state

The Android foreground service watches the sticky `ACTION_BATTERY_CHANGED` broadcast. It sends an
initial `device.status` after authentication and another only when percentage or charging state
changes. No Android permission is required.

### SIM selection

The PC sends `sims.request` when Messages or Calls opens. The phone answers with `sims.sync`:
`sms` lists `SubscriptionManager` SIMs, `call` lists SIM-capable Telecom accounts, and `granted`
reflects the optional `READ_PHONE_STATE` permission. IDs are opaque and valid only while the SIM or
account exists. `sms.send` may include `subscriptionId`; `call.place` may include `accountId`.
Omitting either field uses the phone's default. Messages, threads, active calls and call-log rows may
include `simLabel` so the PC can show which SIM was used, even after a SIM is removed.

### Call control

Call control works **without** the default-dialer role. With the role, LinkPhone's `InCallService`
publishes call IDs, number/display label, direction, state, `startedAt` and the audio route. Without
it, the phone falls back to the dialer's ongoing notification and `TelephonyRegistry`. Those phases
are best effort, so:

* the phone never reports `unknown` for a live call;
* an outgoing call stays `dialing` until the dialer's notification starts its chronometer
  (`EXTRA_SHOW_CHRONOMETER`, which dialers enable on connect) or the text shows a running timer;
* fallback entries carry `slot: "ringing" | "active"`; a waiting call is published alongside the
  live one instead of replacing it. Answer/Decline target the ringing slot, and Hang up is refused
  while one exists because `TelecomManager.endCall()` rejects the ringing leg first;
* the PC keeps the dialed number from `call.place` and fills it in when the phone's report has none;
* the **PC** stamps the live timer when it first sees `active`, so `startedAt` (phone clock) is only
  informational and clock drift between the two devices cannot corrupt the duration.

`call.speaker` and `call.mute` are device-wide. The reported `speaker`, `muted` and `route`
(`earpiece`/`speaker`/`bluetooth`/`headset`/`unknown`) are **observed** values, not the requested
ones. Without the role, mute falls back to `AudioManager.setMicrophoneMute` (deprecated on API 34,
ignored by some OEMs); the phone restores the microphone when the call ends and after a restart if
LinkPhone was the one that muted it. Bluetooth routing is displayed, never selected.

Windows sends commands by opaque call ID. Without the role, control uses `TelecomManager`
(`acceptRingingCall`, `endCall`) and speaker routing uses the call-audio route when available,
otherwise `AudioManager`. Outgoing PSTN calls use `TelecomManager.placeCall`.

Only signaling/control data uses this protocol. LinkPhone does not access, encode or transport the
microphone or call audio; audio remains on the phone earpiece/speaker (or another route selected by
Android). The phone includes its own minimal call UI and high-priority notification for PC-offline
operation.

### Contacts and call history

Both lists are pull-only: the PC sends `contacts.request` / `calllog.request` when the Contacts or
Calls tab opens, and the phone answers with a snapshot. Nothing is pushed on reconnect.

`contacts.sync` requires optional `READ_CONTACTS`; the phone returns at most 800 contacts, each with
up to 5 numbers and a localized type label. `calllog.sync` requires optional `READ_CALL_LOG` and
returns at most the requested number of entries (default and maximum 200), newest first. `type` is
one of `incoming`, `outgoing`, `missed`, `rejected`, `blocked`, `voicemail`, `unknown`; `date` is
epoch milliseconds and `duration` is seconds.

When a permission is missing, the phone still answers with the same frame, an empty list and
`granted: false`, so the PC can show an actionable message instead of "no contacts/calls". Both
readers are read-only; LinkPhone never modifies the provider. The older peer ignores these frames,
so the protocol version is unchanged.

### File transfer

1. Sender sends `file.offer` and waits for `file.accept` (15 s timeout).
2. Sender streams `0x20` chunks of 64 KiB. The receiver requires `offset == bytesReceived`.
3. The receiver acknowledges every 512 KiB; the sender keeps at most 8 chunks (512 KiB) in flight.
4. Sender finishes with `file.done`; the receiver closes the file and marks the transfer `done`.

Incoming files land in `~/Downloads/LinkPhone` on Windows and `Download/LinkPhone` (MediaStore) on
Android.

## 6. Threat model

* Passive LAN sniffing without the pairing code reveals only metadata (sizes, timing, and the
  plaintext `hello` device name).
* An attacker who captures a handshake can brute-force the code offline. With a 40-bit code and
  600k PBKDF2 rounds that is not practical; a 6-digit code is much weaker and should be treated as
  "guest" pairing.
* Pairing attempts are rate-limited per source IP (six per minute), unauthenticated connections are
  capped, and PBKDF2 runs off the main thread. This limits online CPU exhaustion but does not
  replace TLS + pinning.
* An attacker on the LAN can advertise a fake `_linkphone._tcp` service. Verify the PC name and
  code out-of-band. Certificate pinning (roadmap) closes this.
* Anything the phone shares is readable by the desktop process, which is the point.

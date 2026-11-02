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
linkphone://pair?c=<code>&p=<port>&a=<comma-separated IPv4s>&n=<PC name>&i=<server UUID>&v=1
```

All values are URL-encoded. The Android app prefers RFC 1918 addresses, then tries every remaining
address in order. The QR is only a transport for the pairing code and address; the cryptographic
handshake below is unchanged. Camera scanning uses CameraX + ZXing and has no Google Play Services
dependency.

## 3. Frames

Before authentication, control messages are plaintext JSON **text frames**:

```json
{ "t": "hello", "proto": 1, "deviceId": "…", "deviceName": "Pixel 8", "platform": "android" }
```

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
client → hello                     { deviceId, deviceName, platform }
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

**Reconnecting** (device already paired)

```
server → auth.challenge  { nonce = 32 random bytes, base64 }
client → auth.response   { proof = HMAC(psk, "auth:" + nonce) }      (plaintext)
server → auth.ok                                                     (encrypted, seq 1)
```

The key used here is the one stored at pairing time, not a fresh PBKDF2 derivation.

## 5. Control messages

| `t` | Direction | Fields |
|---|---|---|
| `notif.post` | phone → PC | `notif{key,app,pkg,title,text,when,canReply,actions[]}` |
| `notif.dismiss` | phone → PC | `key` |
| `notif.action` | PC → phone | `key`, `actionId`, `replyText?` |
| `clip.set` | both | `text`, `ts` |
| `file.offer` | both | `id`, `name`, `size`, `mime` |
| `file.accept` | both | `id` |
| `file.decline` | both | `id`, `reason?` |
| `file.ack` | receiver → sender | `id`, `offset` |
| `file.done` | sender → receiver | `id` |
| `file.cancel` | both | `id`, `reason?` |
| `sms.threads.request` | PC → phone | — |
| `sms.threads` | phone → PC | `threads[{threadId,address,snippet,date,count}]` |
| `sms.thread.request` | PC → phone | `threadId` |
| `sms.thread` | phone → PC | `threadId`, `address`, `messages[]` |
| `sms.send` | PC → phone | `tempId`, `address`, `body` |
| `sms.sent` | phone → PC | `tempId`, `ok`, `error?` |
| `sms.new` | phone → PC | `message{id,threadId,address,body,date,outgoing}` |
| `sms.error` | phone → PC | `op`, `error` |
| `ping` / `pong` | both | `ts` |

### SMS

The phone reads the `Telephony.Sms` provider after runtime `READ_SMS` permission and sends through
`SmsManager` after `SEND_SMS`. `RECEIVE_SMS` drives live `sms.new` events. LinkPhone does not claim
the default-SMS-app role and does not write provider rows. Consequently v0.2 supports existing SMS
threads and replies, not MMS or composing a new conversation. A successful `sms.sent` means Android
accepted the send request; it is not a carrier delivery receipt.

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
* An attacker on the LAN can advertise a fake `_linkphone._tcp` service. Verify the PC name and
  code out-of-band. Certificate pinning (roadmap) closes this.
* Anything the phone shares is readable by the desktop process, which is the point.

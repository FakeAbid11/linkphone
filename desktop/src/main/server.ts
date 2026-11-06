import { EventEmitter } from 'node:events'
import crypto from 'node:crypto'
import type { IncomingMessage } from 'node:http'
import { WebSocketServer, WebSocket, type RawData } from 'ws'
import type { AppStore } from './state'
import { FileReceiver, FileSource } from './files'
import {
  CAP_NOTIF_ACTION_RESULT,
  CAP_NOTIF_ACTION_V2,
  CAP_NOTIF_PROGRESS,
  DEFAULT_PORT,
  KIND_CONTROL,
  KIND_FILE,
  MAX_CALL_LOG,
  MAX_CONTACTS,
  MAX_CONTACT_NUMBERS,
  MAX_FILE_CHUNK,
  PROTO_VERSION,
  bool,
  isRawMsg,
  num,
  str,
  type CallAccountInfo,
  type CallLogEntry,
  type CallLogType,
  type ControlMsg,
  type FileOfferMsg,
  type GalleryAlbum,
  type GalleryPhoto,
  type MediaActionMsg,
  type NotificationAction,
  type NotificationItem,
  type PhoneCallInfo,
  type PhoneContact,
  type Platform,
  type RawMsg,
  type RouteKind,
  type SimInfo,
  type SmsMessage,
  type SmsThread,
  type StorageEntry,
  type TransferItem
} from '../shared/protocol'
import { mimeForPath } from '../shared/mime'
import { sanitizeDeviceName } from '../shared/device-name'
import {
  decodeFileChunk,
  deriveKeyAsync,
  encodeControl,
  fileChunkPayload,
  hmac,
  normalizeCode,
  openFrame,
  safeEqual,
  sealFrame
} from './crypto'

type Phase = 'fresh' | 'awaitPair' | 'awaitAuth' | 'ready'

const WINDOW_BYTES = 8 * MAX_FILE_CHUNK
const ACK_TIMEOUT_MS = 30_000
const ACTION_ACK_TIMEOUT_MS = 5_000
const GALLERY_PAGE_SIZE = 48
const MAX_SIMS = 8
// Gallery previews are base64 JPEGs in control frames, so allow up to 16 MiB;
// contact and call-log lists are capped far below this.
const MAX_PLAIN_CONTROL = 16 * 1024 * 1024
const MAX_FRAME_BYTES = 20 * 1024 * 1024
const MAX_UNAUTHENTICATED = 32
const MAX_UNAUTHENTICATED_PER_IP = 4
const PAIR_WINDOW_MS = 60_000
const MAX_PAIR_ATTEMPTS = 6

class Outbound {
  acked = 0
  cursor = 0
  resumeOffset = 0
  size = 0
  accepted = false
  failed = false
  canceled = false
  deviceId = ''
  path = ''
  target: 'downloads' | 'pictures' | 'videos' = 'downloads'
  mtimeMs = 0
  private acceptResolve: ((ok: boolean) => void) | null = null
  private dataResolve: (() => void) | null = null

  waitAccept(timeoutMs: number): Promise<boolean> {
    if (this.accepted) return Promise.resolve(true)
    if (this.failed) return Promise.resolve(false)
    return new Promise<boolean>((resolve) => {
      this.acceptResolve = resolve
      setTimeout(() => {
        if (this.acceptResolve) {
          this.acceptResolve(false)
          this.acceptResolve = null
        }
      }, timeoutMs)
    })
  }

  accept(offset = 0): void {
    this.accepted = true
    this.resumeOffset = Math.max(0, offset)
    this.acceptResolve?.(true)
    this.acceptResolve = null
  }

  async waitDrained(size: number, timeoutMs: number): Promise<boolean> {
    const deadline = Date.now() + timeoutMs
    while (this.acked < size) {
      if (this.failed) return false
      if (Date.now() >= deadline) return false
      await new Promise((resolve) => setTimeout(resolve, 200))
    }
    return true
  }

  decline(): void {
    this.failed = true
    this.acceptResolve?.(false)
    this.acceptResolve = null
    this.kickData()
  }

  cancel(): void {
    this.canceled = true
    this.decline()
  }

  waitData(timeoutMs: number): Promise<void> {
    return new Promise<void>((resolve) => {
      this.dataResolve = resolve
      setTimeout(() => {
        const fn = this.dataResolve
        this.dataResolve = null
        if (fn) {
          this.failed = true
          fn()
        }
      }, timeoutMs)
    })
  }

  kickData(): void {
    const fn = this.dataResolve
    this.dataResolve = null
    fn?.()
  }
}

class Peer {
  phase: Phase = 'fresh'
  key: Buffer | null = null
  sendSeq = 0
  recvSeq = 0
  deviceId = ''
  deviceName = ''
  platform: Platform = 'android'
  salt: Buffer | null = null
  nonceB64 = ''
  alive = true
  address = ''
  pairBusy = false
  caps = new Set<string>()

  constructor(
    readonly ws: WebSocket,
    readonly connId: string
  ) {}
}

export interface LinkServerEvents {
  clipboardIn: [text: string]
  clipboardError: [reason: string]
  notification: [item: NotificationItem]
  notificationDismiss: [key: string]
}

export class LinkServer extends EventEmitter {
  private wss: WebSocketServer | null = null
  private peers = new Set<Peer>()
  private outbound = new Map<string, Outbound>()
  private actionPending = new Map<
    string,
    {
      deviceId: string
      resolve: (result: { ok: boolean; error?: string }) => void
      timer: NodeJS.Timeout
    }
  >()
  private ringingCallIds = new Set<string>()
  private pairAttempts = new Map<string, number[]>()
  private readonly receiver: FileReceiver
  private heartbeat: NodeJS.Timeout | null = null
  port = 0

  constructor(private readonly store: AppStore) {
    super()
    this.receiver = new FileReceiver(
      store.downloadsDir,
      (patch) => store.patchTransfer(patch.id, patch),
      (id, offset, deviceId) => {
        const peer = [...this.peers].find(
          (candidate) => candidate.deviceId === deviceId && candidate.phase === 'ready'
        )
        if (peer) this.send(peer, { t: 'file.accept', id, offset })
      },
      (id, reason, deviceId) => {
        const peer = [...this.peers].find(
          (candidate) => candidate.deviceId === deviceId && candidate.phase === 'ready'
        )
        if (peer) this.send(peer, { t: 'file.decline', id, reason })
      }
    )
  }

  /* ---------------------------------------------------------------- */
  /* lifecycle                                                         */
  /* ---------------------------------------------------------------- */

  async start(preferredPort = DEFAULT_PORT): Promise<number> {
    const server = await this.listen(preferredPort)
    this.wss = server.wss
    this.port = server.port
    this.store.setPort(this.port)

    this.wss.on('connection', (ws, req) => this.onConnection(ws, req))
    this.wss.on('error', (err) => console.error('[server] error', err))

    this.heartbeat = setInterval(() => {
      for (const peer of this.peers) {
        if (!peer.alive) {
          peer.ws.terminate()
          continue
        }
        peer.alive = false
        try {
          peer.ws.ping()
        } catch {
          /* ignore */
        }
      }
    }, 25_000)

    return this.port
  }

  private listen(port: number): Promise<{ wss: WebSocketServer; port: number }> {
    return new Promise((resolve, reject) => {
      // Dual-stack: IPv4-mapped clients keep working, and link-local IPv6
      // reaches the phone when the router isolates IPv4 clients.
      const wss = new WebSocketServer({ port, host: '::', maxPayload: MAX_FRAME_BYTES })
      const fail = (err: NodeJS.ErrnoException): void => {
        wss.removeAllListeners()
        wss.close()
        if (err.code === 'EADDRINUSE' && port < DEFAULT_PORT + 10) {
          this.listen(port + 1).then(resolve, reject)
          return
        }
        reject(err)
      }
      wss.once('listening', () => resolve({ wss, port }))
      wss.once('error', fail)
    })
  }

  stop(): void {
    if (this.heartbeat) clearInterval(this.heartbeat)
    this.heartbeat = null
    for (const peer of this.peers) {
      try {
        peer.ws.close(1001, 'shutting down')
      } catch {
        /* ignore */
      }
    }
    this.peers.clear()
    this.wss?.close()
    this.wss = null
  }

  hasConnectedPeer(): boolean {
    return [...this.peers].some((p) => p.phase === 'ready')
  }

  /* ---------------------------------------------------------------- */
  /* connection                                                        */
  /* ---------------------------------------------------------------- */

  private onConnection(ws: WebSocket, req: IncomingMessage): void {
    const address = req.socket.remoteAddress ?? ''
    const unauthenticated = [...this.peers].filter((peer) => peer.phase !== 'ready')
    if (
      unauthenticated.length >= MAX_UNAUTHENTICATED ||
      unauthenticated.filter((peer) => peer.address === address).length >= MAX_UNAUTHENTICATED_PER_IP
    ) {
      ws.close(1013, 'busy')
      return
    }
    const peer = new Peer(ws, crypto.randomUUID())
    peer.address = address
    this.peers.add(peer)

    ws.on('pong', () => {
      peer.alive = true
    })
    ws.on('message', (data, isBinary) => {
      peer.alive = true
      try {
        if (isBinary) this.onBinary(peer, toBuffer(data))
        else this.onText(peer, data.toString())
      } catch (err) {
        console.error('[server] frame error', err)
        ws.close(1011, 'protocol error')
      }
    })
    ws.on('close', () => {
      this.peers.delete(peer)
      if (peer.phase === 'ready') {
        this.ringingCallIds.clear()
        this.store.setCallState({ calls: [], dialerReady: false, outgoingReady: false, controlReady: false, speaker: false, muted: false })
        this.store.setCallPlaced(null)
        this.store.setMedia(null)
        this.emit('media-art', null)
        this.store.resetContacts()
        this.store.resetSims()
      }
      if (peer.deviceId) {
        this.store.markDisconnected(peer.deviceId)
        for (const [key, pending] of [...this.actionPending]) {
          if (pending.deviceId !== peer.deviceId) continue
          this.actionPending.delete(key)
          clearTimeout(pending.timer)
          pending.resolve({ ok: false, error: 'no-peer' })
        }
        this.receiver.pauseAllForDevice(peer.deviceId)
        this.store.interruptTransfersForDevice(peer.deviceId)
        for (const [id, out] of [...this.outbound]) {
          if (out.deviceId !== peer.deviceId) continue
          this.store.patchTransfer(id, {
            status: 'interrupted',
            error: 'connection lost',
            resumeOffset: out.acked
          })
          this.outbound.delete(id)
          out.decline()
        }
      }
    })
    ws.on('error', () => ws.terminate())
  }

  private onText(peer: Peer, raw: string): void {
    if (raw.length > 64 * 1024) {
      peer.ws.close(1009, 'too large')
      return
    }
    let parsed: unknown
    try {
      parsed = JSON.parse(raw)
    } catch {
      return
    }
    if (!isRawMsg(parsed)) return
    const msg = parsed
    switch (str(msg.t)) {
      case 'hello':
        this.onHello(peer, msg)
        return
      case 'pair.request':
        void this.onPairRequest(peer, msg).catch((err) => {
          console.error('[server] pair request failed', err)
          peer.ws.close(1011, 'pairing failed')
        })
        return
      case 'auth.response':
        this.onAuthResponse(peer, msg)
        return
      default:
        // Anything else must arrive encrypted.
        peer.ws.close(1008, 'expected handshake')
    }
  }

  private onHello(peer: Peer, msg: RawMsg): void {
    if (num(msg.proto) !== PROTO_VERSION) {
      peer.ws.close(1002, 'protocol mismatch')
      return
    }
    peer.deviceId = str(msg.deviceId).slice(0, 64)
    peer.deviceName = str(msg.deviceName, 'Android phone').slice(0, 64)
    peer.platform = (str(msg.platform, 'android') as Platform) ?? 'android'
    peer.caps = new Set(
      Array.isArray(msg.caps) ? msg.caps.filter((cap): cap is string => typeof cap === 'string') : []
    )
    if (!peer.deviceId) {
      peer.ws.close(1008, 'missing device id')
      return
    }

    // A code-initiated hello may re-pair a device the PC already knows. The
    // proof still has to match the current pairing code, and a valid proof
    // replaces the old key, so a phone that lost its key can recover without
    // anyone having to forget the device on the PC first.
    const wantsPair = bool(msg.pair)
    const paired = wantsPair ? undefined : this.store.findPaired(peer.deviceId)
    if (paired) {
      peer.phase = 'awaitAuth'
      peer.nonceB64 = crypto.randomBytes(32).toString('base64')
      this.send(peer, {
        t: 'hello.result',
        proto: PROTO_VERSION,
        serverId: this.store.serverId,
        serverName: this.store.serverName,
        needPair: false,
        needAuth: true,
        caps: [CAP_NOTIF_PROGRESS, CAP_NOTIF_ACTION_V2, CAP_NOTIF_ACTION_RESULT]
      })
      this.send(peer, { t: 'auth.challenge', nonce: peer.nonceB64 })
      return
    }

    peer.phase = 'awaitPair'
    peer.salt = crypto.randomBytes(16)
    this.send(peer, {
      t: 'hello.result',
      proto: PROTO_VERSION,
      serverId: this.store.serverId,
      serverName: this.store.serverName,
      needPair: true,
      needAuth: false,
      caps: [CAP_NOTIF_PROGRESS, CAP_NOTIF_ACTION_V2, CAP_NOTIF_ACTION_RESULT],
      salt: peer.salt.toString('base64')
    })
  }

  private async onPairRequest(peer: Peer, msg: RawMsg): Promise<void> {
    if (peer.phase !== 'awaitPair' || !peer.salt || peer.pairBusy) {
      peer.ws.close(1008, 'unexpected pair request')
      return
    }
    if (!normalizeCode(this.store.pairingCode)) {
      this.send(peer, { t: 'pair.reject', reason: 'no-code' })
      peer.ws.close(4002, 'no pairing code')
      return
    }
    if (!this.allowPairAttempt(peer.address)) {
      this.send(peer, { t: 'pair.reject', reason: 'rate' })
      peer.ws.close(4008, 'too many pairing attempts')
      return
    }

    const salt = peer.salt
    const code = this.store.pairingCode
    peer.pairBusy = true
    let key: Buffer
    try {
      key = await deriveKeyAsync(code, salt)
    } catch (err) {
      console.error('[server] pairing derivation failed', err)
      peer.ws.close(1011, 'pairing failed')
      return
    } finally {
      peer.pairBusy = false
    }
    if (peer.phase !== 'awaitPair' || peer.ws.readyState !== WebSocket.OPEN) return

    const proof = Buffer.from(str(msg.proof), 'base64')
    const expected = hmac(key, `pair:${peer.deviceId}`)
    if (!safeEqual(proof, expected)) {
      // Plaintext rejection: the client has already switched to encrypted mode,
      // so a text frame is the unambiguous "wrong code" signal.
      this.send(peer, { t: 'pair.reject', reason: 'code' })
      peer.ws.close(4001, 'bad pairing code')
      return
    }

    peer.key = key
    peer.sendSeq = 0
    peer.recvSeq = 0
    peer.phase = 'ready'
    if (str(msg.deviceName)) peer.deviceName = str(msg.deviceName).slice(0, 64)

    const platform = str(msg.platform, 'android') as Platform
    if (platform) peer.platform = platform

    this.store.addPaired({
      deviceId: peer.deviceId,
      name: peer.deviceName,
      platform: peer.platform,
      key: key.toString('base64'),
      pairedAt: Date.now()
    })
    this.markReady(peer)
    this.send(peer, { t: 'pair.ok', serverId: this.store.serverId, serverName: this.store.serverName })
  }

  private allowPairAttempt(address: string): boolean {
    const now = Date.now()
    const cutoff = now - PAIR_WINDOW_MS
    const recent = (this.pairAttempts.get(address) ?? []).filter((at) => at > cutoff)
    if (recent.length >= MAX_PAIR_ATTEMPTS) {
      this.pairAttempts.set(address, recent)
      return false
    }
    recent.push(now)
    this.pairAttempts.set(address, recent)
    if (this.pairAttempts.size > 256) {
      for (const [key, times] of this.pairAttempts) {
        const fresh = times.filter((at) => at > cutoff)
        if (fresh.length === 0) this.pairAttempts.delete(key)
        else this.pairAttempts.set(key, fresh)
      }
    }
    return true
  }

  private onAuthResponse(peer: Peer, msg: RawMsg): void {
    if (peer.phase !== 'awaitAuth') {
      peer.ws.close(1008, 'unexpected auth response')
      return
    }
    const paired = this.store.findPaired(peer.deviceId)
    if (!paired) {
      // Plaintext, like pair.reject: the peer is not authenticated yet, so a
      // text frame is unambiguous and lets the phone clear its stale pairing.
      this.send(peer, { t: 'auth.reject', reason: 'not-paired' })
      peer.ws.close(4003, 'not paired')
      return
    }
    const key = Buffer.from(paired.key, 'base64')
    const expected = hmac(key, `auth:${peer.nonceB64}`)
    const proof = Buffer.from(str(msg.proof), 'base64')
    if (!safeEqual(proof, expected)) {
      this.send(peer, { t: 'auth.reject', reason: 'bad-proof' })
      peer.ws.close(4001, 'bad auth proof')
      return
    }

    peer.key = key
    peer.sendSeq = 0
    peer.recvSeq = 0
    peer.phase = 'ready'
    if (str(msg.deviceName)) peer.deviceName = str(msg.deviceName).slice(0, 64)
    this.markReady(peer)
    this.send(peer, { t: 'auth.ok', serverId: this.store.serverId, serverName: this.store.serverName })
  }

  private markReady(peer: Peer): void {
    this.store.setPairedName(peer.deviceId, peer.deviceName)
    this.store.markConnected({
      deviceId: peer.deviceId,
      name: peer.deviceName,
      platform: peer.platform,
      paired: true,
      connected: true,
      address: peer.address
    })
    this.send(peer, { t: 'clip.enabled', enabled: this.store.clipboardEnabled })
    this.emit('peer-ready', peer.deviceId)
    setImmediate(() => {
      this.requestContacts()
      this.requestCallHistory()
    })
  }

  /* ---------------------------------------------------------------- */
  /* frames                                                            */
  /* ---------------------------------------------------------------- */

  private onBinary(peer: Peer, data: Buffer): void {
    if (!peer.key) {
      peer.ws.close(1008, 'not authenticated')
      return
    }
    peer.recvSeq += 1
    let plain: Buffer
    try {
      plain = openFrame(peer.key, 'c2s', peer.recvSeq, data)
    } catch {
      peer.ws.close(1008, 'bad frame')
      return
    }
    if (plain.length > MAX_PLAIN_CONTROL) {
      peer.ws.close(1009, 'too large')
      return
    }
    const kind = plain.readUInt8(0)
    if (kind === KIND_CONTROL) {
      let parsed: unknown
      try {
        parsed = JSON.parse(plain.subarray(1).toString('utf8'))
      } catch {
        return
      }
      if (isRawMsg(parsed)) this.onControl(peer, parsed)
      return
    }
    if (kind === KIND_FILE) {
      const chunk = decodeFileChunk(plain)
      this.receiver.write(chunk.transferId, chunk.offset, chunk.data)
    }
  }

  private onControl(peer: Peer, msg: RawMsg): void {
    switch (str(msg.t)) {
      case 'ping':
        this.send(peer, { t: 'pong', ts: num(msg.ts) })
        return

      case 'clip.set': {
        const manual = bool(msg.manual)
        if (!this.store.clipboardEnabled && !manual) return
        const text = str(msg.text)
        if (text.length > 256 * 1024) return
        this.emit('clipboardIn', text)
        return
      }

      case 'clip.error':
        this.emit('clipboardError', str(msg.reason, 'unavailable'))
        return

      case 'notif.post': {
        const raw = msg.notif
        if (!isRawMsg(raw)) return
        const actions: NotificationAction[] = Array.isArray(raw.actions)
          ? (raw.actions as unknown[])
              .filter((a): a is RawMsg => isRawMsg(a))
              .map((a) => ({
                id: str(a.id).slice(0, 16),
                label: str(a.label, 'Open').slice(0, 40),
                reply: bool(a.reply)
              }))
              .slice(0, 4)
          : []
        const item: NotificationItem = {
          key: str(raw.key).slice(0, 256) || `n-${Date.now()}`,
          app: str(raw.app, 'Phone').slice(0, 64),
          pkg: str(raw.pkg).slice(0, 128),
          title: str(raw.title).slice(0, 300),
          text: str(raw.text).slice(0, 2000),
          when: num(raw.when, Date.now()),
          canReply: bool(raw.canReply) || actions.some((action) => action.reply),
          actions,
          deviceId: peer.deviceId,
          progress: optionalInt(raw.progress),
          progressMax: optionalInt(raw.progressMax),
          indeterminate: bool(raw.indeterminate),
          ongoing: bool(raw.ongoing)
        }
        if (this.store.isNotificationMuted(item.pkg, item.app)) {
          console.warn('[notif] muted', item.pkg)
          return
        }
        const isNew = this.store.addNotification(item)
        // Backfilled notifications are old; show them in the list without replaying toasts.
        if (isNew && Date.now() - item.when < 15_000) this.emit('notification', item)
        return
      }

      case 'notif.dismiss':
        this.store.phoneNotificationDismissed(str(msg.key))
        this.emit('notificationDismiss', str(msg.key))
        return

      case 'notif.action.result': {
        const key = str(msg.key)
        const pending = this.actionPending.get(key)
        if (!pending) return
        this.actionPending.delete(key)
        clearTimeout(pending.timer)
        const result = { ok: bool(msg.ok), error: str(msg.error) || undefined }
        pending.resolve(result)
        this.emit('notificationActionResult', key, result)
        return
      }

      case 'file.offer': {
        const offer: FileOfferMsg = {
          t: 'file.offer',
          id: str(msg.id),
          name: str(msg.name, 'file'),
          size: num(msg.size),
          mime: str(msg.mime, 'application/octet-stream'),
          target:
            msg.target === 'pictures'
              ? 'pictures'
              : msg.target === 'videos'
                ? 'videos'
                : 'downloads'
        }
        if (typeof msg.resumeFrom === 'number') offer.resumeFrom = num(msg.resumeFrom)
        if (typeof msg.mtimeMs === 'number') offer.mtimeMs = num(msg.mtimeMs)
        if (typeof msg.photoId === 'string' && msg.photoId) offer.photoId = str(msg.photoId).slice(0, 64)
        if (msg.mediaKind === 'video' || msg.mediaKind === 'image') {
          offer.mediaKind = msg.mediaKind
        }
        this.store.ensureIncomingTransfer({
          id: offer.id,
          name: offer.name,
          size: offer.size,
          received: 0,
          direction: 'in',
          status: 'active',
          deviceId: peer.deviceId,
          startedAt: Date.now(),
          target: offer.target,
          photoId: offer.photoId,
          mediaKind: offer.mediaKind
        })
        this.receiver.offer(offer, peer.deviceId)
        return
      }

      case 'file.done': {
        const id = str(msg.id)
        this.receiver.finish(id)
        const item = this.store.getTransfer(id)
        if (item?.photoId) {
          const key = `${item.mediaKind ?? 'image'}:${item.photoId}`
          this.store.setGalleryDownload(key, {
            transferId: id,
            status: item.status === 'done' ? 'done' : 'failed',
            received: item.received,
            size: item.size,
            path: item.path,
            error: item.error
          })
        }
        return
      }

      case 'storage.list': {
        const list = Array.isArray(msg.entries) ? (msg.entries as unknown[]) : []
        this.store.setStorageFiles(
          list.filter(isRawMsg).map(parseStorageEntry).slice(0, 100),
          bool(msg.scanLimited),
          bool(msg.granted),
          str(msg.nextCursor) || undefined
        )
        return
      }

      case 'storage.error':
        this.store.setStorageError(str(msg.message, 'Could not read phone files'))
        return

      case 'file.cancel': {
        const id = str(msg.id)
        const out = this.outbound.get(id)
        if (out) {
          out.cancel()
          this.finishOutbound(id, 'canceled', str(msg.reason, 'canceled by phone'))
        } else {
          this.receiver.abort(id, str(msg.reason, 'canceled by phone'), 'canceled')
        }
        return
      }

      case 'file.ack': {
        const out = this.outbound.get(str(msg.id))
        if (out) {
          out.acked = Math.max(out.acked, num(msg.offset))
          this.store.patchTransfer(str(msg.id), { received: out.acked })
          out.kickData()
        }
        return
      }

      case 'file.accept': {
        this.outbound.get(str(msg.id))?.accept(num(msg.offset))
        return
      }

      case 'file.decline': {
        const id = str(msg.id)
        this.outbound.get(id)?.decline()
        this.finishOutbound(id, 'declined', str(msg.reason, 'declined by phone'))
        return
      }

      case 'sms.threads': {
        const list = Array.isArray(msg.threads) ? (msg.threads as unknown[]) : []
        this.store.setSmsThreads(list.filter(isRawMsg).map(parseSmsThread).slice(0, 80))
        return
      }

      case 'sms.thread': {
        const list = Array.isArray(msg.messages) ? (msg.messages as unknown[]) : []
        this.store.openSmsThread({
          threadId: str(msg.threadId),
          address: str(msg.address),
          name: str(msg.name).slice(0, 200),
          messages: list.filter(isRawMsg).map(parseSmsMessage).sort((a, b) => a.date - b.date)
        })
        return
      }

      case 'sms.new': {
        const raw = msg.message
        if (!isRawMsg(raw)) return
        const message = parseSmsMessage(raw)
        this.store.appendSmsMessage(message)
        return
      }

      case 'sms.sent':
        this.store.markSmsResult(str(msg.tempId), bool(msg.ok))
        return

      case 'sms.error':
        console.warn('[server] phone reported sms error:', str(msg.error))
        this.store.setSmsLoading(false)
        return

      case 'device.status':
        this.store.updateDeviceStatus(
          peer.deviceId,
          Math.max(0, Math.min(100, num(msg.batteryLevel))),
          bool(msg.charging)
        )
        return

      case 'gallery.albums': {
        const list = Array.isArray(msg.albums) ? (msg.albums as unknown[]) : []
        const kinds = Array.isArray(msg.kinds)
          ? (msg.kinds as unknown[])
              .filter((kind): kind is 'image' | 'video' => kind === 'image' || kind === 'video')
          : []
        this.store.setGalleryKinds(kinds.length > 0 ? kinds : ['image'])
        this.store.setGalleryAccess(bool(msg.granted, true), bool(msg.partial))
        this.store.setGalleryAlbums(list.filter(isRawMsg).map(parseGalleryAlbum).slice(0, 200))
        return
      }

      case 'gallery.photos': {
        const albumId = str(msg.albumId).slice(0, 256)
        const offset = Math.max(0, Math.floor(num(msg.offset)))
        const list = Array.isArray(msg.photos) ? (msg.photos as unknown[]) : []
        this.store.appendGalleryPhotos(
          albumId,
          msg.kind === 'video' ? 'video' : 'image',
          offset,
          list.filter(isRawMsg).map(parseGalleryPhoto).slice(0, GALLERY_PAGE_SIZE),
          bool(msg.hasMore)
        )
        return
      }

      case 'gallery.thumbnails': {
        if (msg.kind !== undefined && !this.store.isGalleryKind(str(msg.kind))) return
        const list = Array.isArray(msg.items) ? (msg.items as unknown[]) : []
        const items = list
          .filter(isRawMsg)
          .slice(0, 24)
          .map((item) => imageData(item, 750_000))
          .filter((item): item is { photoId: string; dataUrl: string } => item !== null)
        this.store.addGalleryThumbs(items)
        return
      }

      case 'gallery.preview': {
        const image = imageData(msg, 8_000_000)
        if (image) this.store.setGalleryPreview(image.photoId, image.dataUrl)
        else this.store.setGalleryError('Phone returned an invalid preview')
        return
      }

      case 'gallery.error':
        console.warn('[server] phone reported gallery error:', str(msg.error))
        this.store.setGalleryError(str(msg.error, 'Gallery request failed').slice(0, 500))
        return

      case 'call.state': {
        const list = Array.isArray(msg.calls) ? (msg.calls as unknown[]) : []
        const calls = list.filter(isRawMsg).map(parsePhoneCall).slice(0, 8)
        const ringing = new Set(calls.filter((call) => call.phase === 'ringing').map((call) => call.id))
        for (const call of calls) {
          if (call.phase === 'ringing' && !this.ringingCallIds.has(call.id)) {
            this.emit('incomingCall', call)
          }
        }
        this.ringingCallIds = ringing
        const dialerReady = bool(msg.dialerReady)
        this.store.setCallState({
          calls,
          dialerReady,
          outgoingReady: msg.outgoingReady === undefined ? dialerReady : bool(msg.outgoingReady),
          controlReady: msg.controlReady === undefined ? dialerReady : bool(msg.controlReady),
          speaker: bool(msg.speaker),
          muted: bool(msg.muted),
          route: parseRoute(str(msg.route, 'unknown'))
        })
        return
      }

      case 'call.placed': {
        const number = typeof msg.number === 'string' ? msg.number.trim().slice(0, 64) : ''
        if (number) this.store.setCallPlaced(number)
        return
      }

      case 'media.state': {
        this.store.setMedia({
          title: str(msg.title).slice(0, 200),
          artist: str(msg.artist).slice(0, 200),
          playing: bool(msg.playing),
          app: str(msg.app).slice(0, 64),
          positionMs: Math.max(0, Math.min(86_400_000, Math.floor(num(msg.positionMs)))),
          durationMs: Math.max(0, Math.min(86_400_000, Math.floor(num(msg.durationMs)))),
          volume: Math.max(0, Math.floor(num(msg.volume))),
          volumeMax: Math.max(0, Math.floor(num(msg.volumeMax))),
          artId: str(msg.artId).slice(0, 64)
        })
        return
      }

      case 'media.art': {
        const id = str(msg.id).slice(0, 64)
        const mime = str(msg.mime).toLowerCase()
        const data = str(msg.data)
        if (!id || !data) {
          this.emit('media-art', null)
          return
        }
        if (
          !/^image\/(jpeg|png)$/.test(mime) ||
          data.length > 96 * 1024 ||
          !/^[A-Za-z0-9+/=]+$/.test(data)
        ) {
          return
        }
        this.emit('media-art', { id, mime, data })
        return
      }

      case 'phone.ring.state': {
        if (peer.deviceId) this.store.setRinging(peer.deviceId, bool(msg.ringing))
        return
      }

      case 'device.rename.state': {
        const name = sanitizeDeviceName(str(msg.name))
        if (name && peer.deviceId) this.store.setPairedName(peer.deviceId, name)
        return
      }

      case 'call.error':
        this.store.setCallError(str(msg.error, 'Call operation failed').slice(0, 500))
        return

      case 'contacts.sync': {
        const list = Array.isArray(msg.contacts) ? (msg.contacts as unknown[]) : []
        this.store.setPhoneContacts(
          list.filter(isRawMsg).map(parsePhoneContact).slice(0, MAX_CONTACTS),
          bool(msg.granted)
        )
        return
      }

      case 'calllog.sync': {
        const list = Array.isArray(msg.entries) ? (msg.entries as unknown[]) : []
        this.store.setCallHistory(
          list.filter(isRawMsg).map(parseCallLogEntry).slice(0, MAX_CALL_LOG),
          bool(msg.granted)
        )
        return
      }

      case 'sims.sync': {
        const sms = Array.isArray(msg.sms) ? (msg.sms as unknown[]) : []
        const call = Array.isArray(msg.call) ? (msg.call as unknown[]) : []
        this.store.setSims(
          sms.filter(isRawMsg).map(parseSimInfo).slice(0, MAX_SIMS),
          call.filter(isRawMsg).map(parseCallAccount).slice(0, MAX_SIMS),
          bool(msg.granted)
        )
        return
      }

      case 'pong':
        return

      default:
        return
    }
  }

  private send(peer: Peer, msg: ControlMsg | RawMsg): void {
    if (peer.ws.readyState !== WebSocket.OPEN) return
    if (peer.key) {
      peer.sendSeq += 1
      const frame = sealFrame(peer.key, 's2c', peer.sendSeq, encodeControl(KIND_CONTROL, msg))
      peer.ws.send(frame, { binary: true })
      return
    }
    peer.ws.send(JSON.stringify(msg))
  }

  private sendBinary(peer: Peer, payload: Buffer): boolean {
    if (!peer.key || peer.ws.readyState !== WebSocket.OPEN) return false
    peer.sendSeq += 1
    peer.ws.send(sealFrame(peer.key, 's2c', peer.sendSeq, payload), { binary: true })
    return true
  }

  broadcast(msg: ControlMsg | RawMsg): void {
    for (const peer of this.peers) {
      if (peer.phase === 'ready') this.send(peer, msg)
    }
  }

  /* ---------------------------------------------------------------- */
  /* desktop -> phone                                                  */
  /* ---------------------------------------------------------------- */

  async sendFiles(
    paths: string[],
    target: 'downloads' | 'pictures' | 'videos' = 'downloads'
  ): Promise<void> {
    const peers = [...this.peers].filter((p) => p.phase === 'ready')
    for (const peer of peers) {
      for (const filePath of paths) {
        await this.sendFileTo(peer, filePath, target)
      }
    }
  }

  cancelTransfer(id: string): void {
    const item = this.store.getTransfer(id)
    if (!item || (item.status !== 'pending' && item.status !== 'active')) return
    const out = this.outbound.get(id)
    if (out) {
      out.cancel()
      this.finishOutbound(id, 'canceled', 'canceled by you')
    } else {
      this.receiver.abort(id, 'canceled by you', 'canceled')
    }
    this.broadcast({ t: 'file.cancel', id, reason: 'canceled by you' })
  }

  retryTransfer(id: string): void {
    const item = this.store.getTransfer(id)
    if (!item || item.direction !== 'out' || !item.sourcePath) return
    const peer = [...this.peers].find(
      (candidate) => candidate.deviceId === item.deviceId && candidate.phase === 'ready'
    )
    if (!peer) return
    void this.sendFileTo(
      peer,
      item.sourcePath,
      item.target ?? 'downloads',
      item.id,
      item.resumeOffset ?? item.received
    )
  }

  private async sendFileTo(
    peer: Peer,
    filePath: string,
    target: 'downloads' | 'pictures' | 'videos' = 'downloads',
    existingId?: string,
    resumeFrom?: number
  ): Promise<void> {
    let source: FileSource
    try {
      source = new FileSource(filePath, filePath.split(/[\\/]/).pop() ?? 'file')
    } catch {
      return
    }

    const id = existingId ?? crypto.randomUUID()
    const mime = mimeForPath(filePath)
    const out = new Outbound()
    out.deviceId = peer.deviceId
    out.path = filePath
    out.target = target
    out.size = source.size
    out.mtimeMs = source.mtimeMs
    this.outbound.set(id, out)

    if (existingId) {
      this.store.patchTransfer(id, {
        status: 'pending',
        error: undefined,
        received: resumeFrom ?? 0,
        target,
        sourcePath: filePath,
        mtimeMs: source.mtimeMs
      })
    } else {
      this.store.upsertTransfer({
        id,
        name: source.name,
        size: source.size,
        received: 0,
        direction: 'out',
        status: 'pending',
        deviceId: peer.deviceId,
        startedAt: Date.now(),
        target,
        sourcePath: filePath,
        mtimeMs: source.mtimeMs
      })
    }

    let sentDone = false
    try {
      const offer: FileOfferMsg = {
        t: 'file.offer',
        id,
        name: source.name,
        size: source.size,
        mime,
        target,
        mtimeMs: source.mtimeMs
      }
      if (resumeFrom !== undefined) offer.resumeFrom = resumeFrom
      this.send(peer, offer)

      const accepted = await out.waitAccept(15_000)
      if (!accepted) {
        this.store.patchTransfer(id, {
          status: out.canceled ? 'canceled' : out.failed ? 'failed' : 'interrupted',
          error: out.canceled
            ? 'canceled by you'
            : out.failed
              ? 'declined by phone'
              : 'no response from phone'
        })
        return
      }

      const offset = Math.max(0, Math.min(out.resumeOffset, source.size))
      source.seek(offset)
      out.cursor = offset
      out.acked = offset
      this.store.patchTransfer(id, { status: 'active', received: offset })

      while (out.cursor < source.size) {
        if (out.failed) throw new Error('transfer aborted by phone')
        if (out.cursor - out.acked >= WINDOW_BYTES) await out.waitData(ACK_TIMEOUT_MS)
        if (out.failed) throw new Error('ack timeout')
        const at = out.cursor
        const chunk = source.readChunk(MAX_FILE_CHUNK)
        if (chunk.length === 0) break
        if (!this.sendBinary(peer, fileChunkPayload(KIND_FILE, id, at, chunk))) {
          throw new Error('connection lost')
        }
        out.cursor += chunk.length
      }

      const drained = await out.waitDrained(source.size, 15_000)
      if (!drained) throw new Error('phone did not confirm the last chunk')
      this.send(peer, { t: 'file.done', id })
      sentDone = true
      this.store.patchTransfer(id, {
        status: 'done',
        received: source.size,
        resumeOffset: 0
      })
      if (target === 'pictures') this.requestGalleryAlbums('image')
      else if (target === 'videos') this.requestGalleryAlbums('video')
    } catch (err) {
      const message = (err as Error).message
      const interrupted = message === 'connection lost'
      this.store.patchTransfer(id, {
        status: out.canceled ? 'canceled' : interrupted ? 'interrupted' : 'failed',
        error: message,
        resumeOffset: out.acked
      })
      if (!out.canceled && !interrupted && !sentDone) {
        this.send(peer, { t: 'file.cancel', id, reason: message })
      }
    } finally {
      source.close()
      this.outbound.delete(id)
    }
  }

  private finishOutbound(id: string, status: TransferItem['status'], error?: string): void {
    this.store.patchTransfer(id, { status, error })
    this.outbound.delete(id)
  }

  /** Called by the desktop clipboard watcher. */
  sendClipboard(text: string): boolean {
    if (!this.hasConnectedPeer()) return false
    this.broadcast({ t: 'clip.set', text, ts: Date.now() })
    return true
  }

  /** Manual Pull from the phone; the response may bypass the automatic-sync toggle. */
  requestClipboard(): boolean {
    if (!this.hasConnectedPeer()) return false
    this.broadcast({ t: 'clip.request' })
    return true
  }

  sendClipboardEnabled(): void {
    this.broadcast({ t: 'clip.enabled', enabled: this.store.clipboardEnabled })
  }

  /**
   * Forward a notification action (e.g. inline reply) back to the phone. Resolves once the phone
   * reports the outcome; legacy phones (no notif.action.result cap) resolve optimistically.
   */
  async sendNotificationAction(
    key: string,
    actionId: string,
    replyText?: string,
    label?: string
  ): Promise<{ ok: boolean; error?: string }> {
    const peers = [...this.peers].filter((p) => p.phase === 'ready')
    if (peers.length === 0) return { ok: false, error: 'no-peer' }

    const waiting = peers.filter((p) => p.caps.has(CAP_NOTIF_ACTION_RESULT))
    const previous = this.actionPending.get(key)
    if (previous) {
      this.actionPending.delete(key)
      clearTimeout(previous.timer)
      previous.resolve({ ok: false, error: 'superseded' })
    }

    for (const peer of peers) this.send(peer, { t: 'notif.action', key, actionId, replyText, label })
    if (waiting.length === 0) return { ok: true }

    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.actionPending.delete(key)
        resolve({ ok: false, error: 'timeout' })
      }, ACTION_ACK_TIMEOUT_MS)
      this.actionPending.set(key, { deviceId: waiting[0].deviceId, resolve, timer })
    })
  }

  /** Ask the phone to dismiss mirrored notifications from its shade. */
  sendNotificationCancel(keys: string[]): void {
    const unique = [...new Set(keys.filter((key) => key.length > 0))]
    if (unique.length === 0) return
    this.broadcast({ t: 'notif.cancel', keys: unique })
  }

  ringPhone(deviceId: string): void {
    const peer = [...this.peers].find(
      (candidate) => candidate.deviceId === deviceId && candidate.phase === 'ready'
    )
    if (!peer) return
    this.send(peer, { t: 'phone.ring', durationMs: this.store.ringDurationMs })
  }

  renameDevice(deviceId: string, name: string): void {
    const clean = sanitizeDeviceName(name)
    if (!clean) return
    const peer = [...this.peers].find(
      (candidate) => candidate.deviceId === deviceId && candidate.phase === 'ready'
    )
    if (!peer) return
    this.send(peer, { t: 'device.rename', name: clean })
  }

  stopRing(deviceId: string): void {
    const peer = [...this.peers].find(
      (candidate) => candidate.deviceId === deviceId && candidate.phase === 'ready'
    )
    if (!peer) {
      this.store.setRinging(deviceId, false)
      return
    }
    this.send(peer, { t: 'phone.ring.stop' })
  }

  sendMediaAction(
    action: 'play' | 'pause' | 'next' | 'previous' | 'seek' | 'volume',
    value?: number
  ): void {
    const msg: MediaActionMsg = { t: 'media.action', action }
    if (action === 'seek' && typeof value === 'number') msg.positionMs = Math.max(0, value)
    if (action === 'volume' && typeof value === 'number') msg.level = Math.max(0, value)
    this.broadcast(msg)
  }

  /* ---------------------------------------------------------------- */
  /* SMS                                                               */
  /* ---------------------------------------------------------------- */

  requestSmsThreads(): void {
    if (!this.hasConnectedPeer()) return
    this.store.setSmsLoading(true)
    this.broadcast({ t: 'sms.threads.request' })
  }

  requestSmsThread(threadId: string, address: string): void {
    if (!this.hasConnectedPeer()) return
    this.store.openSmsThread({
      threadId,
      address,
      name: this.store.smsThreadName(threadId),
      messages: []
    })
    this.broadcast({ t: 'sms.thread.request', threadId })
  }

  sendSms(
    threadId: string,
    address: string,
    body: string,
    subscriptionId?: number
  ): 'queued' | 'no-peer' {
    const peer = [...this.peers].find((p) => p.phase === 'ready')
    if (!peer) return 'no-peer'
    const tempId = `tmp-${crypto.randomUUID()}`
    const simLabel = subscriptionId === undefined
      ? undefined
      : this.store.simLabelFor(subscriptionId)
    this.store.appendSmsMessage({
      id: tempId,
      threadId,
      address,
      body,
      date: Date.now(),
      outgoing: true,
      pending: true,
      subscriptionId,
      simLabel
    })
    this.send(peer, { t: 'sms.send', tempId, address, body, subscriptionId })
    return 'queued'
  }

  retrySms(messageId: string): 'queued' | 'no-peer' | 'invalid' {
    const message = this.store.findSmsMessage(messageId)
    if (!message || !message.outgoing || !message.failed || message.retried) return 'invalid'
    if (!this.hasConnectedPeer()) return 'no-peer'
    const result = this.sendSms(
      message.threadId,
      message.address,
      message.body,
      message.subscriptionId
    )
    if (result === 'queued') this.store.markSmsRetried(message.id)
    return result
  }

  startSmsConversation(
    address: string,
    body: string,
    subscriptionId?: number
  ): 'queued' | 'invalid' | 'no-peer' {
    const normalized = address.trim().slice(0, 64)
    const text = body.trim()
    if (!/^[+]?[0-9][0-9\s()-]{1,}$/.test(normalized) || text.length === 0) return 'invalid'
    if (!this.hasConnectedPeer()) return 'no-peer'
    const threadId = `addr:${normalized}`
    this.store.openSmsThread({
      threadId,
      address: normalized,
      name: '',
      messages: this.store.localSmsMessages(normalized)
    })
    this.broadcast({ t: 'sms.thread.request', threadId })
    return this.sendSms(threadId, normalized, text, subscriptionId)
  }

  /* ---------------------------------------------------------------- */
  /* Phone storage                                                     */
  /* ---------------------------------------------------------------- */

  requestStorageList(cursor?: string): boolean {
    const peer = [...this.peers].find((candidate) => candidate.phase === 'ready')
    if (!peer) return false
    this.store.setStorageLoading(true)
    this.send(peer, {
      t: 'storage.list.request',
      reqId: crypto.randomUUID(),
      cursor: cursor?.slice(0, 64)
    })
    return true
  }

  pullStorageFile(id: string, name = '', mime = ''): void {
    const peer = [...this.peers].find((candidate) => candidate.phase === 'ready')
    if (!peer) return
    this.send(peer, {
      t: 'storage.pull',
      id: id.slice(0, 64),
      name: name ? name.slice(0, 200) : undefined,
      mime: mime ? mime.slice(0, 100) : undefined
    })
  }

  uploadMedia(paths: string[]): void {
    const images = paths.filter((filePath) => mimeForPath(filePath).startsWith('image/'))
    const videos = paths.filter((filePath) => mimeForPath(filePath).startsWith('video/'))
    const other = paths.filter((filePath) => {
      const mime = mimeForPath(filePath)
      return !mime.startsWith('image/') && !mime.startsWith('video/')
    })
    if (images.length > 0) void this.sendFiles(images, 'pictures')
    if (videos.length > 0) void this.sendFiles(videos, 'videos')
    if (other.length > 0) void this.sendFiles(other, 'downloads')
  }

  /* ---------------------------------------------------------------- */
  /* Gallery                                                           */
  /* ---------------------------------------------------------------- */

  requestGalleryAlbums(kind: 'image' | 'video' = 'image'): void {
    if (!this.hasConnectedPeer()) return
    this.store.requestGalleryAlbums(kind)
    this.broadcast({ t: 'gallery.albums.request', kind })
  }

  openGalleryAlbum(kind: 'image' | 'video', albumId: string, name: string): void {
    if (!this.hasConnectedPeer()) return
    const safeId = albumId.slice(0, 256)
    this.store.openGalleryAlbum(safeId, name.slice(0, 200))
    this.broadcast({
      t: 'gallery.photos.request',
      albumId: safeId,
      offset: 0,
      limit: GALLERY_PAGE_SIZE,
      kind
    })
  }

  closeGalleryAlbum(): void {
    this.store.closeGalleryAlbum()
  }

  loadMoreGallery(kind: 'image' | 'video', albumId: string, offset: number): void {
    if (!this.hasConnectedPeer()) return
    this.store.setGalleryLoading(true)
    this.broadcast({
      t: 'gallery.photos.request',
      albumId: albumId.slice(0, 256),
      offset: Math.max(0, Math.floor(offset)),
      limit: GALLERY_PAGE_SIZE,
      kind
    })
  }

  requestGalleryThumbnails(kind: 'image' | 'video', photoIds: string[]): void {
    if (!this.hasConnectedPeer()) return
    const ids = [...new Set(photoIds.map((id) => id.slice(0, 64)).filter(Boolean))].slice(0, 24)
    if (ids.length === 0) return
    this.broadcast({ t: 'gallery.thumbnails.request', photoIds: ids, kind })
  }

  requestGalleryPreview(kind: 'image' | 'video', photoId: string, name: string): void {
    if (!this.hasConnectedPeer()) return
    const safeId = photoId.slice(0, 64)
    this.store.openGalleryPreview(safeId, name.slice(0, 240))
    this.broadcast({ t: 'gallery.preview.request', photoId: safeId, kind })
  }

  closeGalleryPreview(): void {
    this.store.closeGalleryPreview()
  }

  downloadGalleryPhoto(kind: 'image' | 'video', photoId: string): void {
    if (!this.hasConnectedPeer()) return
    this.broadcast({ t: 'gallery.download', photoId: photoId.slice(0, 64), kind })
  }

  /* ---------------------------------------------------------------- */
  /* Calls                                                             */
  /* ---------------------------------------------------------------- */

  placeCall(number: string, accountId?: string): void {
    if (!this.hasConnectedPeer()) return
    this.store.setCallError('')
    const dialed = number.slice(0, 64)
    // Remember the dialed number locally: the phone's fallback call state may arrive without one.
    this.store.setCallPlaced(dialed)
    this.broadcast({
      t: 'call.place',
      number: dialed,
      accountId: accountId?.slice(0, 256)
    })
  }

  answerCall(callId: string): void {
    this.store.setCallError('')
    this.broadcast({ t: 'call.answer', callId: callId.slice(0, 64) })
  }

  rejectCall(callId: string): void {
    this.store.setCallError('')
    this.broadcast({ t: 'call.reject', callId: callId.slice(0, 64) })
  }

  disconnectCall(callId: string): void {
    this.store.setCallError('')
    this.broadcast({ t: 'call.disconnect', callId: callId.slice(0, 64) })
  }

  setCallSpeaker(enabled: boolean): void {
    this.store.setCallError('')
    this.broadcast({ t: 'call.speaker', enabled })
  }

  setCallMute(enabled: boolean): void {
    this.store.setCallError('')
    this.broadcast({ t: 'call.mute', enabled })
  }

  /* ---------------------------------------------------------------- */
  /* Contacts and call history                                         */
  /* ---------------------------------------------------------------- */

  requestContacts(): void {
    if (!this.hasConnectedPeer()) return
    this.store.setContactsLoading(true)
    this.broadcast({ t: 'contacts.request' })
  }

  requestSims(): void {
    if (!this.hasConnectedPeer()) return
    this.store.setSimsLoading(true)
    this.broadcast({ t: 'sims.request' })
  }

  requestCallHistory(limit = MAX_CALL_LOG): void {
    if (!this.hasConnectedPeer()) return
    this.store.setCallLogLoading(true)
    this.broadcast({
      t: 'calllog.request',
      limit: Math.max(1, Math.min(MAX_CALL_LOG, Math.floor(limit)))
    })
  }
}

function parseSmsMessage(raw: RawMsg): SmsMessage {
  return {
    id: str(raw.id).slice(0, 256) || crypto.randomUUID(),
    threadId: str(raw.threadId).slice(0, 256),
    address: str(raw.address).slice(0, 256),
    name: str(raw.name).slice(0, 200),
    body: str(raw.body).slice(0, 20_000),
    date: num(raw.date, Date.now()),
    outgoing: bool(raw.outgoing),
    pending: bool(raw.pending),
    failed: bool(raw.failed),
    subscriptionId: optionalInt(raw.subscriptionId),
    simLabel: str(raw.simLabel).slice(0, 24) || undefined
  }
}

function parseSmsThread(raw: RawMsg): SmsThread {
  return {
    threadId: str(raw.threadId).slice(0, 256),
    address: str(raw.address).slice(0, 256),
    name: str(raw.name).slice(0, 200),
    snippet: str(raw.snippet).slice(0, 500),
    date: num(raw.date),
    count: num(raw.count, 1),
    subscriptionId: optionalInt(raw.subscriptionId),
    simLabel: str(raw.simLabel).slice(0, 24) || undefined
  }
}

function parseGalleryAlbum(raw: RawMsg): GalleryAlbum {
  return {
    id: str(raw.id).slice(0, 256),
    name: str(raw.name, 'Album').slice(0, 200),
    count: Math.max(0, Math.floor(num(raw.count))),
    coverPhotoId: str(raw.coverPhotoId).slice(0, 64),
    kind: raw.kind === 'video' ? 'video' : 'image'
  }
}

function parseStorageEntry(raw: RawMsg): StorageEntry {
  return {
    id: str(raw.id).slice(0, 64),
    name: str(raw.name, 'file').slice(0, 240),
    size: Math.max(0, num(raw.size)),
    mime: str(raw.mime, 'application/octet-stream').slice(0, 100),
    modified: Math.max(0, num(raw.modified))
  }
}

function parseGalleryPhoto(raw: RawMsg): GalleryPhoto {
  const kind = raw.kind === 'video' ? 'video' : 'image'
  return {
    id: str(raw.id).slice(0, 64),
    albumId: str(raw.albumId).slice(0, 256),
    name: str(raw.name, kind === 'video' ? 'Video' : 'Photo').slice(0, 240),
    mime: str(raw.mime, kind === 'video' ? 'video/mp4' : 'image/jpeg').slice(0, 100),
    size: Math.max(0, num(raw.size)),
    dateTaken: Math.max(0, num(raw.dateTaken)),
    width: Math.max(0, Math.floor(num(raw.width))),
    height: Math.max(0, Math.floor(num(raw.height))),
    kind,
    durationMs: kind === 'video' ? Math.max(0, num(raw.durationMs)) : undefined
  }
}

function imageData(
  raw: RawMsg,
  maxBase64Length: number
): { photoId: string; dataUrl: string } | null {
  const photoId = str(raw.photoId).slice(0, 64)
  const mime = str(raw.mime, 'image/jpeg')
  const data = str(raw.data)
  if (!photoId || !/^image\/(?:jpeg|png|webp)$/i.test(mime)) return null
  if (!data || data.length > maxBase64Length || !/^[A-Za-z0-9+/=]+$/.test(data)) return null
  return { photoId, dataUrl: `data:${mime.toLowerCase()};base64,${data}` }
}

function parsePhoneContact(raw: RawMsg): PhoneContact {
  const list = Array.isArray(raw.numbers) ? (raw.numbers as unknown[]) : []
  const numbers = list
    .filter(isRawMsg)
    .map((item) => ({
      number: str(item.number).slice(0, 40),
      label: str(item.label, 'Phone').slice(0, 24)
    }))
    .filter((item) => item.number)
    .slice(0, MAX_CONTACT_NUMBERS)
  return {
    id: str(raw.id).slice(0, 64) || crypto.randomUUID(),
    name: str(raw.name).slice(0, 120),
    numbers
  }
}

function parseCallLogEntry(raw: RawMsg): CallLogEntry {
  const allowed: CallLogType[] = [
    'incoming',
    'outgoing',
    'missed',
    'rejected',
    'blocked',
    'voicemail',
    'unknown'
  ]
  const type = str(raw.type)
  const name = str(raw.name).slice(0, 120)
  return {
    id: str(raw.id).slice(0, 64) || crypto.randomUUID(),
    number: str(raw.number).slice(0, 40),
    name: name || undefined,
    type: allowed.includes(type as CallLogType) ? (type as CallLogType) : 'unknown',
    date: Math.max(0, num(raw.date)),
    duration: Math.max(0, Math.floor(num(raw.duration))),
    simLabel: str(raw.simLabel).slice(0, 24) || undefined
  }
}

function parseRoute(value: string): RouteKind {
  return value === 'earpiece' || value === 'speaker' || value === 'bluetooth' || value === 'headset'
    ? value
    : 'unknown'
}

function parsePhoneCall(raw: RawMsg): PhoneCallInfo {
  const phase = str(raw.phase)
  const allowed: PhoneCallInfo['phase'][] = [
    'new',
    'connecting',
    'dialing',
    'ringing',
    'active',
    'held',
    'disconnecting',
    'disconnected',
    'unknown'
  ]
  return {
    id: str(raw.id).slice(0, 64),
    number: str(raw.number).slice(0, 128),
    displayName: str(raw.displayName).slice(0, 200),
    slot: raw.slot === 'ringing' || raw.slot === 'active' ? raw.slot : undefined,
    canControl: raw.canControl === true ? true : undefined,
    viaNotification: raw.viaNotification === true ? true : undefined,
    phase: allowed.includes(phase as PhoneCallInfo['phase'])
      ? (phase as PhoneCallInfo['phase'])
      : 'unknown',
    incoming: bool(raw.incoming),
    startedAt: Math.max(0, num(raw.startedAt)),
    simLabel: str(raw.simLabel).slice(0, 24) || undefined
  }
}

function optionalInt(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0
    ? Math.floor(value)
    : undefined
}

function parseSimInfo(raw: RawMsg): SimInfo {
  return {
    id: str(raw.id).slice(0, 256),
    label: str(raw.label, 'SIM').slice(0, 24),
    carrier: str(raw.carrier).slice(0, 24) || undefined,
    slot: optionalInt(raw.slot),
    isDefault: bool(raw.isDefault)
  }
}

function parseCallAccount(raw: RawMsg): CallAccountInfo {
  return {
    id: str(raw.id).slice(0, 256),
    label: str(raw.label, 'SIM').slice(0, 24),
    isDefault: bool(raw.isDefault)
  }
}

function toBuffer(data: RawData): Buffer {
  if (Buffer.isBuffer(data)) return data
  if (Array.isArray(data)) return Buffer.concat(data)
  return Buffer.from(data as ArrayBuffer)
}

import { EventEmitter } from 'node:events'
import crypto from 'node:crypto'
import type { IncomingMessage } from 'node:http'
import { WebSocketServer, WebSocket, type RawData } from 'ws'
import type { AppStore } from './state'
import { FileReceiver, FileSource } from './files'
import {
  DEFAULT_PORT,
  KIND_CONTROL,
  KIND_FILE,
  MAX_FILE_CHUNK,
  PROTO_VERSION,
  bool,
  isRawMsg,
  num,
  str,
  type ControlMsg,
  type FileOfferMsg,
  type GalleryAlbum,
  type GalleryPhoto,
  type NotificationAction,
  type NotificationItem,
  type Platform,
  type PhoneCallInfo,
  type RawMsg,
  type SmsMessage,
  type SmsThread,
  type TransferItem
} from '../shared/protocol'
import {
  decodeFileChunk,
  deriveKey,
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
const GALLERY_PAGE_SIZE = 48

class Outbound {
  acked = 0
  cursor = 0
  accepted = false
  failed = false
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

  accept(): void {
    this.accepted = true
    this.acceptResolve?.(true)
    this.acceptResolve = null
  }

  decline(): void {
    this.failed = true
    this.acceptResolve?.(false)
    this.acceptResolve = null
    this.kickData()
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

  constructor(
    readonly ws: WebSocket,
    readonly connId: string
  ) {}
}

export interface LinkServerEvents {
  clipboardIn: [text: string]
  notification: [item: NotificationItem]
  notificationDismiss: [key: string]
}

export class LinkServer extends EventEmitter {
  private wss: WebSocketServer | null = null
  private peers = new Set<Peer>()
  private outbound = new Map<string, Outbound>()
  private ringingCallIds = new Set<string>()
  private readonly receiver: FileReceiver
  private heartbeat: NodeJS.Timeout | null = null
  port = 0

  constructor(private readonly store: AppStore) {
    super()
    this.receiver = new FileReceiver(
      store.downloadsDir,
      (patch) => store.patchTransfer(patch.id, patch),
      (id) => this.broadcast({ t: 'file.accept', id }),
      (id, reason) => this.broadcast({ t: 'file.decline', id, reason })
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
      const wss = new WebSocketServer({ port, host: '::' })
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
    const peer = new Peer(ws, crypto.randomUUID())
    peer.address = req.socket.remoteAddress ?? ''
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
        this.store.setCallState({ calls: [], dialerReady: false, speaker: false, muted: false })
      }
      if (peer.deviceId) {
        this.store.markDisconnected(peer.deviceId)
        this.receiver.cancelAllForDevice(peer.deviceId)
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
        this.onPairRequest(peer, msg)
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
        needAuth: true
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
      salt: peer.salt.toString('base64')
    })
  }

  private onPairRequest(peer: Peer, msg: RawMsg): void {
    if (peer.phase !== 'awaitPair' || !peer.salt) {
      peer.ws.close(1008, 'unexpected pair request')
      return
    }
    if (!normalizeCode(this.store.pairingCode)) {
      this.send(peer, { t: 'pair.reject', reason: 'no-code' })
      peer.ws.close(4002, 'no pairing code')
      return
    }

    const proof = Buffer.from(str(msg.proof), 'base64')
    const key = deriveKey(this.store.pairingCode, peer.salt)
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
    this.store.markConnected({
      deviceId: peer.deviceId,
      name: peer.deviceName,
      platform: peer.platform,
      paired: true,
      connected: true,
      address: peer.address
    })
    this.emit('peer-ready', peer.deviceId)
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
        if (!this.store.clipboardEnabled) return
        const text = str(msg.text)
        if (text.length > 512 * 1024) return
        this.emit('clipboardIn', text)
        return
      }

      case 'notif.post': {
        const raw = msg.notif
        if (!isRawMsg(raw)) return
        const actions: NotificationAction[] = Array.isArray(raw.actions)
          ? (raw.actions as unknown[])
              .filter((a): a is RawMsg => isRawMsg(a))
              .map((a) => ({
                id: str(a.id),
                label: str(a.label, 'Open'),
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
          canReply: bool(raw.canReply),
          actions,
          deviceId: peer.deviceId
        }
        this.store.addNotification(item)
        this.emit('notification', item)
        return
      }

      case 'notif.dismiss':
        this.store.dismissNotification(str(msg.key))
        this.emit('notificationDismiss', str(msg.key))
        return

      case 'file.offer': {
        const offer: FileOfferMsg = {
          t: 'file.offer',
          id: str(msg.id),
          name: str(msg.name, 'file'),
          size: num(msg.size),
          mime: str(msg.mime, 'application/octet-stream')
        }
        this.receiver.offer(offer, peer.deviceId)
        return
      }

      case 'file.done':
        this.receiver.finish(str(msg.id))
        return

      case 'file.cancel':
        this.receiver.abort(str(msg.id), str(msg.reason, 'canceled by phone'))
        return

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
        this.outbound.get(str(msg.id))?.accept()
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
        this.store.setGalleryAlbums(list.filter(isRawMsg).map(parseGalleryAlbum).slice(0, 200))
        return
      }

      case 'gallery.photos': {
        const albumId = str(msg.albumId).slice(0, 256)
        const offset = Math.max(0, Math.floor(num(msg.offset)))
        const list = Array.isArray(msg.photos) ? (msg.photos as unknown[]) : []
        this.store.appendGalleryPhotos(
          albumId,
          offset,
          list.filter(isRawMsg).map(parseGalleryPhoto).slice(0, GALLERY_PAGE_SIZE),
          bool(msg.hasMore)
        )
        return
      }

      case 'gallery.thumbnails': {
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
        this.store.setCallState({
          calls,
          dialerReady: bool(msg.dialerReady),
          speaker: bool(msg.speaker),
          muted: bool(msg.muted)
        })
        return
      }

      case 'call.error':
        this.store.setCallError(str(msg.error, 'Call operation failed').slice(0, 500))
        return

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

  private sendBinary(peer: Peer, payload: Buffer): void {
    if (!peer.key || peer.ws.readyState !== WebSocket.OPEN) return
    peer.sendSeq += 1
    peer.ws.send(sealFrame(peer.key, 's2c', peer.sendSeq, payload), { binary: true })
  }

  broadcast(msg: ControlMsg | RawMsg): void {
    for (const peer of this.peers) {
      if (peer.phase === 'ready') this.send(peer, msg)
    }
  }

  /* ---------------------------------------------------------------- */
  /* desktop -> phone                                                  */
  /* ---------------------------------------------------------------- */

  async sendFiles(paths: string[]): Promise<void> {
    const peers = [...this.peers].filter((p) => p.phase === 'ready')
    for (const peer of peers) {
      for (const filePath of paths) {
        await this.sendFileTo(peer, filePath)
      }
    }
  }

  private async sendFileTo(peer: Peer, filePath: string): Promise<void> {
    let source: FileSource
    try {
      source = new FileSource(filePath, filePath.split(/[\\/]/).pop() ?? 'file')
    } catch {
      return
    }

    const id = crypto.randomUUID()
    const out = new Outbound()
    this.outbound.set(id, out)
    this.store.upsertTransfer({
      id,
      name: source.name,
      size: source.size,
      received: 0,
      direction: 'out',
      status: 'pending',
      deviceId: peer.deviceId,
      startedAt: Date.now()
    })

    try {
      this.send(peer, {
        t: 'file.offer',
        id,
        name: source.name,
        size: source.size,
        mime: 'application/octet-stream'
      })

      const accepted = await out.waitAccept(15_000)
      if (!accepted) {
        this.store.patchTransfer(id, {
          status: 'failed',
          error: out.failed ? 'declined by phone' : 'no response from phone'
        })
        return
      }

      this.store.patchTransfer(id, { status: 'active' })

      while (out.cursor < source.size) {
        if (out.failed) throw new Error('transfer aborted by phone')
        if (out.cursor - out.acked >= WINDOW_BYTES) await out.waitData(ACK_TIMEOUT_MS)
        if (out.failed) throw new Error('ack timeout')
        const offset = out.cursor
        const chunk = source.readChunk(MAX_FILE_CHUNK)
        if (chunk.length === 0) break
        this.sendBinary(peer, fileChunkPayload(KIND_FILE, id, offset, chunk))
        out.cursor += chunk.length
      }

      this.send(peer, { t: 'file.done', id })
      this.store.patchTransfer(id, { status: 'done', received: source.size })
    } catch (err) {
      const message = (err as Error).message
      this.store.patchTransfer(id, { status: 'failed', error: message })
      this.send(peer, { t: 'file.cancel', id, reason: message })
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
  sendClipboard(text: string): void {
    this.broadcast({ t: 'clip.set', text, ts: Date.now() })
  }

  /** Forward a notification action (e.g. inline reply) back to the phone. */
  sendNotificationAction(key: string, actionId: string, replyText?: string): void {
    this.broadcast({ t: 'notif.action', key, actionId, replyText })
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
    this.store.openSmsThread({ threadId, address, messages: [] })
    this.broadcast({ t: 'sms.thread.request', threadId })
  }

  sendSms(threadId: string, address: string, body: string): void {
    const peer = [...this.peers].find((p) => p.phase === 'ready')
    if (!peer) return
    const tempId = `tmp-${crypto.randomUUID()}`
    this.store.appendSmsMessage({
      id: tempId,
      threadId,
      address,
      body,
      date: Date.now(),
      outgoing: true,
      pending: true
    })
    this.send(peer, { t: 'sms.send', tempId, address, body })
  }

  /* ---------------------------------------------------------------- */
  /* Gallery                                                           */
  /* ---------------------------------------------------------------- */

  requestGalleryAlbums(): void {
    if (!this.hasConnectedPeer()) return
    this.store.requestGalleryAlbums()
    this.broadcast({ t: 'gallery.albums.request' })
  }

  openGalleryAlbum(albumId: string, name: string): void {
    if (!this.hasConnectedPeer()) return
    const safeId = albumId.slice(0, 256)
    this.store.openGalleryAlbum(safeId, name.slice(0, 200))
    this.broadcast({
      t: 'gallery.photos.request',
      albumId: safeId,
      offset: 0,
      limit: GALLERY_PAGE_SIZE
    })
  }

  closeGalleryAlbum(): void {
    this.store.closeGalleryAlbum()
  }

  loadMoreGallery(albumId: string, offset: number): void {
    if (!this.hasConnectedPeer()) return
    this.store.setGalleryLoading(true)
    this.broadcast({
      t: 'gallery.photos.request',
      albumId: albumId.slice(0, 256),
      offset: Math.max(0, Math.floor(offset)),
      limit: GALLERY_PAGE_SIZE
    })
  }

  requestGalleryThumbnails(photoIds: string[]): void {
    if (!this.hasConnectedPeer()) return
    const ids = [...new Set(photoIds.map((id) => id.slice(0, 64)).filter(Boolean))].slice(0, 24)
    if (ids.length === 0) return
    this.broadcast({ t: 'gallery.thumbnails.request', photoIds: ids })
  }

  requestGalleryPreview(photoId: string, name: string): void {
    if (!this.hasConnectedPeer()) return
    const safeId = photoId.slice(0, 64)
    this.store.openGalleryPreview(safeId, name.slice(0, 240))
    this.broadcast({ t: 'gallery.preview.request', photoId: safeId })
  }

  closeGalleryPreview(): void {
    this.store.closeGalleryPreview()
  }

  downloadGalleryPhoto(photoId: string): void {
    if (!this.hasConnectedPeer()) return
    this.broadcast({ t: 'gallery.download', photoId: photoId.slice(0, 64) })
  }

  /* ---------------------------------------------------------------- */
  /* Calls                                                             */
  /* ---------------------------------------------------------------- */

  placeCall(number: string): void {
    if (!this.hasConnectedPeer()) return
    this.store.setCallError('')
    this.broadcast({ t: 'call.place', number: number.slice(0, 64) })
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
}

function parseSmsMessage(raw: RawMsg): SmsMessage {
  return {
    id: str(raw.id).slice(0, 256) || crypto.randomUUID(),
    threadId: str(raw.threadId).slice(0, 256),
    address: str(raw.address).slice(0, 256),
    body: str(raw.body).slice(0, 20_000),
    date: num(raw.date, Date.now()),
    outgoing: bool(raw.outgoing),
    pending: bool(raw.pending),
    failed: bool(raw.failed)
  }
}

function parseSmsThread(raw: RawMsg): SmsThread {
  return {
    threadId: str(raw.threadId).slice(0, 256),
    address: str(raw.address).slice(0, 256),
    snippet: str(raw.snippet).slice(0, 500),
    date: num(raw.date),
    count: num(raw.count, 1)
  }
}

function parseGalleryAlbum(raw: RawMsg): GalleryAlbum {
  return {
    id: str(raw.id).slice(0, 256),
    name: str(raw.name, 'Album').slice(0, 200),
    count: Math.max(0, Math.floor(num(raw.count))),
    coverPhotoId: str(raw.coverPhotoId).slice(0, 64)
  }
}

function parseGalleryPhoto(raw: RawMsg): GalleryPhoto {
  return {
    id: str(raw.id).slice(0, 64),
    albumId: str(raw.albumId).slice(0, 256),
    name: str(raw.name, 'Photo').slice(0, 240),
    mime: str(raw.mime, 'image/jpeg').slice(0, 100),
    size: Math.max(0, num(raw.size)),
    dateTaken: Math.max(0, num(raw.dateTaken)),
    width: Math.max(0, Math.floor(num(raw.width))),
    height: Math.max(0, Math.floor(num(raw.height)))
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
    number: str(raw.number, 'Unknown').slice(0, 128),
    displayName: str(raw.displayName).slice(0, 200),
    phase: allowed.includes(phase as PhoneCallInfo['phase'])
      ? (phase as PhoneCallInfo['phase'])
      : 'unknown',
    incoming: bool(raw.incoming),
    startedAt: Math.max(0, num(raw.startedAt, Date.now()))
  }
}

function toBuffer(data: RawData): Buffer {
  if (Buffer.isBuffer(data)) return data
  if (Array.isArray(data)) return Buffer.concat(data)
  return Buffer.from(data as ArrayBuffer)
}

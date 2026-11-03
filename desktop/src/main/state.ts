import { EventEmitter } from 'node:events'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import crypto from 'node:crypto'
import type {
  AppState,
  DesktopUpdateState,
  DeviceInfo,
  GalleryAlbum,
  GalleryPhoto,
  NotificationItem,
  PhoneCallInfo,
  Platform,
  SmsActiveThread,
  SmsMessage,
  SmsThread,
  TransferItem
} from '../shared/protocol'

export interface PairedDevice {
  deviceId: string
  name: string
  platform: Platform
  /** base64 AES-256 key */
  key: string
  pairedAt: number
}

interface Persisted {
  serverId: string
  serverName: string
  paired: PairedDevice[]
  clipboardEnabled: boolean
}

const MAX_NOTIFICATIONS = 200
const MAX_TRANSFERS = 100

export class AppStore extends EventEmitter {
  private readonly file: string
  private persisted: Persisted
  readonly downloadsDir: string
  private notifications: NotificationItem[] = []
  private transfers: TransferItem[] = []
  private smsThreads: SmsThread[] = []
  private smsActive: SmsActiveThread | null = null
  private smsLoading = false
  private galleryAlbums: GalleryAlbum[] = []
  private galleryPhotos: GalleryPhoto[] = []
  private galleryAlbumId: string | null = null
  private galleryAlbumName = ''
  private galleryNextOffset = 0
  private galleryHasMore = false
  private galleryLoading = false
  private galleryThumbs: Record<string, string> = {}
  private galleryPreview: AppState['galleryPreview'] = null
  private galleryError = ''
  private desktopUpdate: DesktopUpdateState
  private phoneCalls: PhoneCallInfo[] = []
  private callDialerReady = false
  private callSpeaker = false
  private callMuted = false
  private callError = ''
  private live = new Map<string, DeviceInfo>()
  private addresses: string[] = []
  private addresses6: string[] = []
  port = 0
  pairingCode = ''

  constructor(dir: string, currentVersion = '') {
    super()
    this.file = path.join(dir, 'config.json')
    this.downloadsDir = path.join(os.homedir(), 'Downloads', 'LinkPhone')
    fs.mkdirSync(this.downloadsDir, { recursive: true })
    this.persisted = this.load(dir)
    this.desktopUpdate = { status: 'idle', currentVersion }
  }

  private load(dir: string): Persisted {
    try {
      const raw = fs.readFileSync(this.file, 'utf8')
      const parsed = JSON.parse(raw) as Partial<Persisted>
      return {
        serverId: parsed.serverId ?? crypto.randomUUID(),
        serverName: parsed.serverName ?? os.hostname(),
        paired: Array.isArray(parsed.paired) ? parsed.paired : [],
        clipboardEnabled: parsed.clipboardEnabled ?? true
      }
    } catch {
      const fresh: Persisted = {
        serverId: crypto.randomUUID(),
        serverName: os.hostname() || 'This PC',
        paired: [],
        clipboardEnabled: true
      }
      try {
        fs.mkdirSync(dir, { recursive: true })
        fs.writeFileSync(this.file, JSON.stringify(fresh, null, 2), 'utf8')
      } catch {
        /* first run on a read-only profile - kept in memory */
      }
      return fresh
    }
  }

  private save(): void {
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true })
      fs.writeFileSync(this.file, JSON.stringify(this.persisted, null, 2), 'utf8')
    } catch {
      /* ignore write failures, state stays in memory */
    }
  }

  get serverId(): string {
    return this.persisted.serverId
  }

  get serverName(): string {
    return this.persisted.serverName
  }

  get clipboardEnabled(): boolean {
    return this.persisted.clipboardEnabled
  }

  setClipboardEnabled(enabled: boolean): void {
    this.persisted.clipboardEnabled = enabled
    this.save()
    this.changed()
  }

  findPaired(deviceId: string): PairedDevice | undefined {
    return this.persisted.paired.find((d) => d.deviceId === deviceId)
  }

  addPaired(device: PairedDevice): void {
    this.persisted.paired = this.persisted.paired.filter((d) => d.deviceId !== device.deviceId)
    this.persisted.paired.push(device)
    this.save()
    this.changed()
  }

  forget(deviceId: string): void {
    this.persisted.paired = this.persisted.paired.filter((d) => d.deviceId !== deviceId)
    this.live.delete(deviceId)
    this.save()
    this.changed()
  }

  setAddresses(addresses: string[]): void {
    this.addresses = addresses
    this.changed()
  }

  setAddresses6(addresses: string[]): void {
    this.addresses6 = addresses
    this.changed()
  }

  setPort(port: number): void {
    this.port = port
    this.changed()
  }

  setPairingCode(code: string): void {
    this.pairingCode = code
    this.changed()
  }

  markConnected(device: DeviceInfo): void {
    const current = this.live.get(device.deviceId)
    this.live.set(device.deviceId, {
      ...current,
      ...device,
      connected: true,
      lastSeen: Date.now()
    })
    this.changed()
  }

  updateDeviceStatus(deviceId: string, batteryLevel: number, charging: boolean): void {
    const current = this.live.get(deviceId)
    if (!current) return
    this.live.set(deviceId, {
      ...current,
      batteryLevel: Math.max(0, Math.min(100, Math.round(batteryLevel))),
      charging
    })
    this.changed()
  }

  markDisconnected(deviceId: string): void {
    const current = this.live.get(deviceId)
    if (current) this.live.set(deviceId, { ...current, connected: false, lastSeen: Date.now() })
    this.changed()
  }

  addNotification(item: NotificationItem): void {
    this.notifications = [item, ...this.notifications.filter((n) => n.key !== item.key)].slice(
      0,
      MAX_NOTIFICATIONS
    )
    this.changed()
  }

  dismissNotification(key: string): void {
    this.notifications = this.notifications.filter((n) => n.key !== key)
    this.changed()
  }

  clearNotifications(): void {
    this.notifications = []
    this.changed()
  }

  upsertTransfer(item: TransferItem): void {
    const idx = this.transfers.findIndex((t) => t.id === item.id)
    if (idx >= 0) this.transfers[idx] = item
    else this.transfers = [item, ...this.transfers].slice(0, MAX_TRANSFERS)
    this.changed()
  }

  patchTransfer(id: string, patch: Partial<TransferItem>): void {
    const idx = this.transfers.findIndex((t) => t.id === id)
    if (idx < 0) return
    this.transfers[idx] = { ...this.transfers[idx], ...patch }
    this.changed()
  }

  /* ---------------- SMS ---------------- */

  setSmsLoading(loading: boolean): void {
    this.smsLoading = loading
    this.changed()
  }

  setSmsThreads(threads: SmsThread[]): void {
    this.smsThreads = threads
    this.smsLoading = false
    this.changed()
  }

  openSmsThread(active: SmsActiveThread | null): void {
    this.smsActive = active
    if (!active) this.smsLoading = false
    this.changed()
  }

  appendSmsMessage(message: SmsMessage): void {
    if (this.smsActive && this.smsActive.threadId === message.threadId) {
      const exists = this.smsActive.messages.some((m) => m.id === message.id)
      if (!exists) {
        this.smsActive = {
          ...this.smsActive,
          messages: [...this.smsActive.messages, message].sort((a, b) => a.date - b.date)
        }
      }
    }

    const thread = this.smsThreads.find((t) => t.threadId === message.threadId)
    if (thread) {
      const next: SmsThread = {
        ...thread,
        snippet: message.body.slice(0, 160),
        date: Math.max(thread.date, message.date),
        count: thread.count,
        address: thread.address || message.address
      }
      this.smsThreads = [next, ...this.smsThreads.filter((t) => t.threadId !== thread.threadId)]
    } else if (!message.outgoing) {
      this.smsThreads = [
        {
          threadId: message.threadId,
          address: message.address,
          snippet: message.body.slice(0, 160),
          date: message.date,
          count: 1
        },
        ...this.smsThreads
      ]
    }
    this.changed()
  }

  markSmsResult(tempId: string, ok: boolean): void {
    if (!this.smsActive) return
    this.smsActive = {
      ...this.smsActive,
      messages: this.smsActive.messages.map((m) =>
        m.id === tempId ? { ...m, pending: false, failed: !ok } : m
      )
    }
    this.changed()
  }

  /* ---------------- Gallery ---------------- */

  requestGalleryAlbums(): void {
    this.galleryLoading = true
    this.galleryError = ''
    this.galleryPreview = null
    this.changed()
  }

  setGalleryAlbums(albums: GalleryAlbum[]): void {
    this.galleryAlbums = albums
    this.galleryPhotos = []
    this.galleryAlbumId = null
    this.galleryAlbumName = ''
    this.galleryNextOffset = 0
    this.galleryHasMore = false
    this.galleryLoading = false
    this.galleryError = ''
    this.galleryPreview = null
    this.changed()
  }

  openGalleryAlbum(albumId: string, name: string): void {
    this.galleryAlbumId = albumId
    this.galleryAlbumName = name
    this.galleryPhotos = []
    this.galleryNextOffset = 0
    this.galleryHasMore = false
    this.galleryLoading = true
    this.galleryError = ''
    this.galleryPreview = null
    this.changed()
  }

  closeGalleryAlbum(): void {
    this.galleryAlbumId = null
    this.galleryAlbumName = ''
    this.galleryPhotos = []
    this.galleryNextOffset = 0
    this.galleryHasMore = false
    this.galleryLoading = false
    this.galleryPreview = null
    this.changed()
  }

  setGalleryLoading(loading: boolean): void {
    this.galleryLoading = loading
    this.changed()
  }

  appendGalleryPhotos(
    albumId: string,
    offset: number,
    photos: GalleryPhoto[],
    hasMore: boolean
  ): void {
    if (this.galleryAlbumId !== albumId) return
    if (offset === 0) {
      this.galleryPhotos = photos
    } else {
      const known = new Set(this.galleryPhotos.map((photo) => photo.id))
      this.galleryPhotos = [...this.galleryPhotos, ...photos.filter((photo) => !known.has(photo.id))]
    }
    this.galleryNextOffset = offset + photos.length
    this.galleryHasMore = hasMore
    this.galleryLoading = false
    this.galleryError = ''
    this.changed()
  }

  addGalleryThumbs(items: Array<{ photoId: string; dataUrl: string }>): void {
    for (const item of items) this.galleryThumbs[item.photoId] = item.dataUrl
    const keys = Object.keys(this.galleryThumbs)
    if (keys.length > 500) {
      for (const key of keys.slice(0, keys.length - 500)) delete this.galleryThumbs[key]
    }
    this.changed()
  }

  openGalleryPreview(photoId: string, name: string): void {
    this.galleryPreview = { photoId, name, dataUrl: '', loading: true }
    this.galleryError = ''
    this.changed()
  }

  setGalleryPreview(photoId: string, dataUrl: string): void {
    if (!this.galleryPreview || this.galleryPreview.photoId !== photoId) return
    this.galleryPreview = { ...this.galleryPreview, dataUrl, loading: false, error: undefined }
    this.changed()
  }

  closeGalleryPreview(): void {
    this.galleryPreview = null
    this.changed()
  }

  setGalleryError(error: string): void {
    this.galleryLoading = false
    this.galleryError = error
    if (this.galleryPreview?.loading) {
      this.galleryPreview = { ...this.galleryPreview, loading: false, error }
    }
    this.changed()
  }

  setDesktopUpdate(patch: Partial<DesktopUpdateState>): void {
    this.desktopUpdate = { ...this.desktopUpdate, ...patch }
    this.changed()
  }

  setCallState(input: {
    calls: PhoneCallInfo[]
    dialerReady: boolean
    speaker: boolean
    muted: boolean
  }): void {
    this.phoneCalls = input.calls
    this.callDialerReady = input.dialerReady
    this.callSpeaker = input.speaker
    this.callMuted = input.muted
    this.callError = ''
    this.changed()
  }

  setCallError(error: string): void {
    this.callError = error
    this.changed()
  }

  snapshot(): AppState {
    const devices: DeviceInfo[] = this.persisted.paired.map((d) => {
      const live = this.live.get(d.deviceId)
      return {
        deviceId: d.deviceId,
        name: d.name,
        platform: d.platform,
        paired: true,
        connected: live?.connected ?? false,
        address: live?.address,
        lastSeen: live?.lastSeen,
        batteryLevel: live?.batteryLevel,
        charging: live?.charging
      }
    })
    for (const [id, live] of this.live) {
      if (!devices.some((d) => d.deviceId === id)) {
        devices.push({ ...live, paired: false })
      }
    }
    return {
      serverId: this.persisted.serverId,
      serverName: this.persisted.serverName,
      port: this.port,
      pairingCode: this.pairingCode,
      addresses: this.addresses,
      addresses6: this.addresses6,
      devices,
      notifications: this.notifications,
      transfers: this.transfers,
      smsThreads: this.smsThreads,
      smsActive: this.smsActive,
      smsLoading: this.smsLoading,
      galleryAlbums: this.galleryAlbums,
      galleryPhotos: this.galleryPhotos,
      galleryAlbumId: this.galleryAlbumId,
      galleryAlbumName: this.galleryAlbumName,
      galleryNextOffset: this.galleryNextOffset,
      galleryHasMore: this.galleryHasMore,
      galleryLoading: this.galleryLoading,
      galleryThumbs: this.galleryThumbs,
      galleryPreview: this.galleryPreview,
      galleryError: this.galleryError,
      desktopUpdate: this.desktopUpdate,
      phoneCalls: this.phoneCalls,
      callDialerReady: this.callDialerReady,
      callSpeaker: this.callSpeaker,
      callMuted: this.callMuted,
      callError: this.callError,
      clipboardEnabled: this.persisted.clipboardEnabled,
      downloadsDir: this.downloadsDir,
      clippingInFlight: false
    }
  }

  private changed(): void {
    this.emit('change', this.snapshot())
  }
}

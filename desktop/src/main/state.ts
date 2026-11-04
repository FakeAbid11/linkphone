import { EventEmitter } from 'node:events'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import crypto from 'node:crypto'
import type {
  AppState,
  CallAccountInfo,
  CallLogEntry,
  DesktopUpdateState,
  DeviceInfo,
  GalleryAlbum,
  GalleryPhoto,
  MutedApp,
  NotificationItem,
  PhoneCallInfo,
  PhoneContact,
  Platform,
  SmsActiveThread,
  SmsMessage,
  SmsThread,
  SimInfo,
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
  mutedNotifications: MutedApp[]
}

const MAX_NOTIFICATIONS = 200
const MAX_TRANSFERS = 100
const MAX_MUTED_APPS = 100

function muteKey(pkg: string, app: string): string {
  const trimmed = pkg.trim()
  return trimmed || `app:${app.trim().toLowerCase()}`
}

function sanitizeMuted(value: unknown): MutedApp[] {
  if (!Array.isArray(value)) return []
  const out: MutedApp[] = []
  const seen = new Set<string>()
  for (const entry of value) {
    if (!entry || typeof entry !== 'object') continue
    const raw = entry as Record<string, unknown>
    const pkg = typeof raw.pkg === 'string' ? raw.pkg.trim().slice(0, 128) : ''
    const app = typeof raw.app === 'string' ? raw.app.trim().slice(0, 64) : ''
    if (!pkg && !app) continue
    const key = muteKey(pkg, app)
    if (seen.has(key)) continue
    seen.add(key)
    out.push({ pkg, app: app || pkg })
    if (out.length >= MAX_MUTED_APPS) break
  }
  return out
}

export class AppStore extends EventEmitter {
  private readonly file: string
  private persisted: Persisted
  readonly downloadsDir: string
  private notifications: NotificationItem[] = []
  private hiddenNotificationKeys = new Set<string>()
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
  private callOutgoingReady = false
  private callControlReady = false
  private callPlaced: { number: string; at: number } | null = null
  private callSpeaker = false
  private callMuted = false
  private callError = ''
  private phoneContacts: PhoneContact[] = []
  private contactsGranted = false
  private contactsLoading = false
  private phoneSims: SimInfo[] = []
  private callAccounts: CallAccountInfo[] = []
  private simsGranted = false
  private simsLoading = false
  private callHistory: CallLogEntry[] = []
  private callLogGranted = false
  private callLogLoading = false
  private live = new Map<string, DeviceInfo>()
  private addresses: string[] = []
  private addresses6: string[] = []
  private launchAtLogin = false
  private launchAtLoginSupported = false
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
        clipboardEnabled: parsed.clipboardEnabled ?? true,
        mutedNotifications: sanitizeMuted(parsed.mutedNotifications)
      }
    } catch {
      const fresh: Persisted = {
        serverId: crypto.randomUUID(),
        serverName: os.hostname() || 'This PC',
        paired: [],
        clipboardEnabled: true,
        mutedNotifications: []
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

  setLaunchAtLogin(enabled: boolean, supported: boolean): void {
    if (this.launchAtLogin === enabled && this.launchAtLoginSupported === supported) return
    this.launchAtLogin = enabled
    this.launchAtLoginSupported = supported
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

  addNotification(item: NotificationItem): boolean {
    if (this.hiddenNotificationKeys.has(item.key)) return false
    const index = this.notifications.findIndex((n) => n.key === item.key)
    if (index >= 0) {
      this.notifications = this.notifications.map((n, i) =>
        i === index ? { ...item, when: n.when } : n
      )
      this.changed()
      return false
    }
    this.notifications = [item, ...this.notifications].slice(0, MAX_NOTIFICATIONS)
    this.changed()
    return true
  }

  private hideNotification(key: string): void {
    this.hiddenNotificationKeys.add(key)
    if (this.hiddenNotificationKeys.size > 500) {
      const oldest = this.hiddenNotificationKeys.values().next().value
      if (oldest !== undefined) this.hiddenNotificationKeys.delete(oldest)
    }
  }

  dismissNotification(key: string): void {
    this.hideNotification(key)
    this.notifications = this.notifications.filter((n) => n.key !== key)
    this.changed()
  }

  phoneNotificationDismissed(key: string): void {
    this.hiddenNotificationKeys.delete(key)
    this.notifications = this.notifications.filter((n) => n.key !== key)
    this.changed()
  }

  clearNotifications(): string[] {
    const keys = this.notifications.map((item) => item.key)
    for (const item of this.notifications) this.hideNotification(item.key)
    this.notifications = []
    this.changed()
    return keys
  }

  isNotificationMuted(pkg: string, app: string): boolean {
    const key = muteKey(pkg, app)
    return this.persisted.mutedNotifications.some(
      (entry) => muteKey(entry.pkg, entry.app) === key
    )
  }

  setNotificationMuted(pkg: string, app: string, muted: boolean): void {
    const key = muteKey(pkg, app)
    const list = this.persisted.mutedNotifications
    const exists = list.some((entry) => muteKey(entry.pkg, entry.app) === key)
    let dirty = false
    if (muted) {
      if (!exists) {
        const label = app.trim().slice(0, 64) || pkg.trim().slice(0, 128)
        this.persisted.mutedNotifications = [
          ...list,
          { pkg: pkg.trim().slice(0, 128), app: label }
        ].slice(0, 100)
        dirty = true
      }
      if (this.removeNotificationsForApp(pkg, app)) dirty = true
    } else {
      if (!exists) return
      this.persisted.mutedNotifications = list.filter(
        (entry) => muteKey(entry.pkg, entry.app) !== key
      )
      dirty = true
    }
    if (!dirty) return
    this.save()
    this.changed()
  }

  clearNotificationsForApp(pkg: string, app: string): string[] {
    const key = muteKey(pkg, app)
    const keys = this.notifications
      .filter((item) => muteKey(item.pkg, item.app) === key)
      .map((item) => item.key)
    if (this.removeNotificationsForApp(pkg, app)) this.changed()
    return keys
  }

  private removeNotificationsForApp(pkg: string, app: string): boolean {
    const key = muteKey(pkg, app)
    const keys = new Set<string>()
    for (const item of this.notifications) {
      if (muteKey(item.pkg, item.app) === key) keys.add(item.key)
    }
    if (keys.size === 0) return false
    for (const itemKey of keys) this.hideNotification(itemKey)
    this.notifications = this.notifications.filter((item) => !keys.has(item.key))
    return true
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

  smsThreadName(threadId: string): string | undefined {
    return this.smsThreads.find((thread) => thread.threadId === threadId)?.name
  }

  openSmsThread(active: SmsActiveThread | null): void {
    if (active && !active.name) {
      const name = this.smsThreadName(active.threadId)
      if (name) active = { ...active, name }
    }
    this.smsActive = active
    if (!active) this.smsLoading = false
    this.changed()
  }

  appendSmsMessage(message: SmsMessage): void {
    if (this.smsActive && this.smsActive.threadId === message.threadId) {
      const exists = this.smsActive.messages.some((m) => m.id === message.id)
      this.smsActive = {
        ...this.smsActive,
        name: this.smsActive.name || message.name,
        messages: exists
          ? this.smsActive.messages
          : [...this.smsActive.messages, message].sort((a, b) => a.date - b.date)
      }
    }

    const thread = this.smsThreads.find((t) => t.threadId === message.threadId)
    if (thread) {
      const next: SmsThread = {
        ...thread,
        snippet: message.body.slice(0, 160),
        date: Math.max(thread.date, message.date),
        count: thread.count,
        address: thread.address || message.address,
        name: thread.name || message.name
      }
      this.smsThreads = [next, ...this.smsThreads.filter((t) => t.threadId !== thread.threadId)]
    } else if (!message.outgoing) {
      this.smsThreads = [
        {
          threadId: message.threadId,
          address: message.address,
          name: message.name,
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
    outgoingReady: boolean
    controlReady: boolean
    speaker: boolean
    muted: boolean
  }): void {
    this.phoneCalls = input.calls
    this.callDialerReady = input.dialerReady
    this.callOutgoingReady = input.outgoingReady
    this.callControlReady = input.controlReady
    if (input.calls.length > 0) this.callPlaced = null
    this.callSpeaker = input.speaker
    this.callMuted = input.muted
    this.callError = ''
    this.changed()
  }

  setCallError(error: string): void {
    this.callError = error
    this.changed()
  }

  setCallPlaced(number: string | null): void {
    this.callPlaced = number ? { number, at: Date.now() } : null
    this.changed()
  }

  /* ---------------- Contacts and call history ---------------- */

  setContactsLoading(loading: boolean): void {
    if (this.contactsLoading === loading) return
    this.contactsLoading = loading
    this.changed()
  }

  setPhoneContacts(contacts: PhoneContact[], granted: boolean): void {
    this.phoneContacts = contacts
    this.contactsGranted = granted
    this.contactsLoading = false
    this.changed()
  }

  setCallLogLoading(loading: boolean): void {
    if (this.callLogLoading === loading) return
    this.callLogLoading = loading
    this.changed()
  }

  setCallHistory(entries: CallLogEntry[], granted: boolean): void {
    this.callHistory = entries
    this.callLogGranted = granted
    this.callLogLoading = false
    this.changed()
  }

  setSimsLoading(loading: boolean): void {
    if (this.simsLoading === loading) return
    this.simsLoading = loading
    this.changed()
  }

  setSims(sms: SimInfo[], call: CallAccountInfo[], granted: boolean): void {
    this.phoneSims = sms
    this.callAccounts = call
    this.simsGranted = granted
    this.simsLoading = false
    this.changed()
  }

  simLabelFor(subscriptionId: number): string | undefined {
    return this.phoneSims.find((sim) => sim.id === String(subscriptionId))?.label
  }

  resetSims(): void {
    if (
      this.phoneSims.length === 0 &&
      this.callAccounts.length === 0 &&
      !this.simsGranted &&
      !this.simsLoading
    ) {
      return
    }
    this.phoneSims = []
    this.callAccounts = []
    this.simsGranted = false
    this.simsLoading = false
    this.changed()
  }

  resetContacts(): void {
    if (
      this.phoneContacts.length === 0 &&
      this.callHistory.length === 0 &&
      !this.contactsLoading &&
      !this.callLogLoading &&
      !this.contactsGranted &&
      !this.callLogGranted
    ) {
      return
    }
    this.phoneContacts = []
    this.callHistory = []
    this.contactsGranted = false
    this.callLogGranted = false
    this.contactsLoading = false
    this.callLogLoading = false
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
      mutedNotifications: this.persisted.mutedNotifications,
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
      callOutgoingReady: this.callOutgoingReady,
      callControlReady: this.callControlReady,
      callPlaced: this.callPlaced,
      callSpeaker: this.callSpeaker,
      callMuted: this.callMuted,
      callError: this.callError,
      phoneContacts: this.phoneContacts,
      contactsGranted: this.contactsGranted,
      contactsLoading: this.contactsLoading,
      phoneSims: this.phoneSims,
      callAccounts: this.callAccounts,
      simsGranted: this.simsGranted,
      simsLoading: this.simsLoading,
      callHistory: this.callHistory,
      callLogGranted: this.callLogGranted,
      callLogLoading: this.callLogLoading,
      launchAtLogin: this.launchAtLogin,
      launchAtLoginSupported: this.launchAtLoginSupported,
      clipboardEnabled: this.persisted.clipboardEnabled,
      downloadsDir: this.downloadsDir,
      clippingInFlight: false
    }
  }

  private changed(): void {
    this.emit('change', this.snapshot())
  }
}

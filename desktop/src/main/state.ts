import { EventEmitter } from 'node:events'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import crypto from 'node:crypto'
import type {
  AppState,
  DeviceInfo,
  NotificationItem,
  Platform,
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
  private live = new Map<string, DeviceInfo>()
  private addresses: string[] = []
  port = 0
  pairingCode = ''

  constructor(dir: string) {
    super()
    this.file = path.join(dir, 'config.json')
    this.downloadsDir = path.join(os.homedir(), 'Downloads', 'LinkPhone')
    fs.mkdirSync(this.downloadsDir, { recursive: true })
    this.persisted = this.load(dir)
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

  setPort(port: number): void {
    this.port = port
    this.changed()
  }

  setPairingCode(code: string): void {
    this.pairingCode = code
    this.changed()
  }

  markConnected(device: DeviceInfo): void {
    this.live.set(device.deviceId, { ...device, connected: true, lastSeen: Date.now() })
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
        lastSeen: live?.lastSeen
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
      devices,
      notifications: this.notifications,
      transfers: this.transfers,
      clipboardEnabled: this.persisted.clipboardEnabled,
      downloadsDir: this.downloadsDir,
      clippingInFlight: false
    }
  }

  private changed(): void {
    this.emit('change', this.snapshot())
  }
}

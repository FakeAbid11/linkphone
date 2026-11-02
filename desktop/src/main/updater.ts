import { autoUpdater, type UpdateInfo } from 'electron-updater'
import type { AppStore } from './state'

const FIRST_CHECK_MS = 20_000
const CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000

/** GitHub Releases updater for the packaged NSIS application. */
export class DesktopUpdater {
  private firstTimer: NodeJS.Timeout | null = null
  private interval: NodeJS.Timeout | null = null
  private checking = false

  constructor(
    private readonly store: AppStore,
    private readonly packaged: boolean
  ) {
    autoUpdater.autoDownload = false
    autoUpdater.autoInstallOnAppQuit = true
    autoUpdater.allowPrerelease = false

    autoUpdater.on('checking-for-update', () => {
      this.store.setDesktopUpdate({ status: 'checking', message: undefined })
    })
    autoUpdater.on('update-available', (info: UpdateInfo) => {
      this.checking = false
      this.store.setDesktopUpdate({
        status: 'available',
        availableVersion: info.version,
        progress: 0,
        checkedAt: Date.now(),
        message: undefined
      })
    })
    autoUpdater.on('update-not-available', () => {
      this.checking = false
      this.store.setDesktopUpdate({
        status: 'up-to-date',
        availableVersion: undefined,
        progress: undefined,
        checkedAt: Date.now(),
        message: undefined
      })
    })
    autoUpdater.on('download-progress', (progress) => {
      this.store.setDesktopUpdate({
        status: 'downloading',
        progress: Math.max(0, Math.min(100, progress.percent))
      })
    })
    autoUpdater.on('update-downloaded', (info: UpdateInfo) => {
      this.store.setDesktopUpdate({
        status: 'ready',
        availableVersion: info.version,
        progress: 100,
        message: undefined
      })
    })
    autoUpdater.on('error', (error: Error) => {
      this.checking = false
      this.store.setDesktopUpdate({
        status: 'error',
        message: friendlyError(error),
        progress: undefined
      })
    })
  }

  start(): void {
    if (!this.packaged) return
    this.firstTimer = setTimeout(() => void this.check(false), FIRST_CHECK_MS)
    this.firstTimer.unref()
    this.interval = setInterval(() => void this.check(false), CHECK_INTERVAL_MS)
    this.interval.unref()
  }

  stop(): void {
    if (this.firstTimer) clearTimeout(this.firstTimer)
    if (this.interval) clearInterval(this.interval)
    this.firstTimer = null
    this.interval = null
  }

  async check(manual = true): Promise<void> {
    if (!this.packaged) {
      if (manual) {
        this.store.setDesktopUpdate({
          status: 'error',
          message: 'Update checks are available in the installed app.'
        })
      }
      return
    }
    if (this.checking) return
    this.checking = true
    try {
      await autoUpdater.checkForUpdates()
    } catch (error) {
      this.checking = false
      this.store.setDesktopUpdate({
        status: 'error',
        message: friendlyError(error)
      })
    }
  }

  async download(): Promise<void> {
    if (!this.packaged) return
    this.store.setDesktopUpdate({ status: 'downloading', progress: 0, message: undefined })
    try {
      await autoUpdater.downloadUpdate()
    } catch (error) {
      this.store.setDesktopUpdate({ status: 'error', message: friendlyError(error) })
    }
  }

  install(): void {
    if (!this.packaged) return
    autoUpdater.quitAndInstall(false, true)
  }
}

function friendlyError(error: unknown): string {
  const text = error instanceof Error ? error.message : String(error)
  return text.replace(/https?:\/\/[^\s]+/g, 'GitHub Releases').slice(0, 400)
}

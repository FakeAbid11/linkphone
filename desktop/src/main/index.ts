import fs from 'node:fs'
import path from 'node:path'
import {
  app,
  BrowserWindow,
  clipboard,
  dialog,
  ipcMain,
  Menu,
  nativeImage,
  Notification,
  powerMonitor,
  shell,
  Tray
} from 'electron'
import type { AppState, PhoneCallInfo } from '../shared/protocol'
import { ClipboardWatcher } from './clipboard'
import { newPairCode } from './crypto'
import { advertise, localAddresses, localLinkLocalIPv6, type Advertisement } from './discovery'
import { LinkServer } from './server'
import { AppStore } from './state'
import { DesktopUpdater } from './updater'

/* Fallback 32x32 phone glyph if the packaged icon resource is missing. */
const FALLBACK_ICON_B64 =
  'iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAEUSURBVFhH1ZchTgNBGEYrkUiO0SNUIis5AhJZWccROAYSWckROAISWfd9ZJNp0vwd+ObtAgkvGbPz9s0/63a1+k/YvpJ0b3v/1ZL0YPumvruY6WBJHx5E0lNtzMb2taRjC7/WW5cv8Hw2x7q2ZtE++3T4oe71mG7fBtjXvVm02w0HqR+hQepHaJD6ERqkfoQGqR+hQepHaJD6ERqkfoQGqR+hQepHaJD6ERqkfoQGqR+hQepHaJD6ERqkfoQGqR+hQepHaJD6ERqkfqQXtL1pz09r852/iF7w7NmJ3t6vDvD3X2D0b8f27qcHWLcBjpIey83r2kl6awNsa2s2kl5adAhJ79PPbO0sQtJt58YXS9Ld6OGfVmF8Qkmi9gEAAAAASUVORK5CYII='

function iconPath(name: string): string {
  return app.isPackaged
    ? path.join(process.resourcesPath, name)
    : path.join(__dirname, '../../resources', name)
}

function appIcon(name: string): Electron.NativeImage {
  const image = nativeImage.createFromPath(iconPath(name))
  return image.isEmpty()
    ? nativeImage.createFromBuffer(Buffer.from(FALLBACK_ICON_B64, 'base64'))
    : image
}

const LOGIN_ITEM_NAME = 'LinkPhone'
const HIDDEN_ARG = '--hidden'
const SEND_ARG = '--send'
const MAX_SEND_FILES = 100
const LOGIN_ITEM_ARGS = [HIDDEN_ARG]

function launchAtLoginSupported(): boolean {
  return process.platform === 'win32' && app.isPackaged
}

function readLaunchAtLogin(): boolean {
  if (!launchAtLoginSupported()) return false
  try {
    // executableWillLaunchAtLogin accounts for the Windows StartupApproved state
    // and ignores the command-line args; launchItems is not a Windows check.
    return app.getLoginItemSettings({
      path: process.execPath,
      args: LOGIN_ITEM_ARGS
    }).executableWillLaunchAtLogin
  } catch (error) {
    console.error('[startup] could not read login item', error)
    return false
  }
}

function syncLaunchAtLogin(): void {
  const supported = launchAtLoginSupported()
  store.setLaunchAtLogin(supported ? readLaunchAtLogin() : false, supported)
}

function applyLaunchAtLogin(enabled: boolean): void {
  if (!launchAtLoginSupported()) {
    store.setLaunchAtLogin(false, false)
    return
  }
  try {
    app.setLoginItemSettings({
      openAtLogin: enabled,
      path: process.execPath,
      args: LOGIN_ITEM_ARGS,
      name: LOGIN_ITEM_NAME,
      ...(enabled ? { enabled: true } : {})
    })
  } catch (error) {
    console.error('[startup] could not update login item', error)
  }
  syncLaunchAtLogin()
}

let win: BrowserWindow | null = null
let tray: Tray | null = null
let store: AppStore
let server: LinkServer
let advertisement: Advertisement | null = null
let watcher: ClipboardWatcher
let updater: DesktopUpdater
let quitting = false
let booted = false
let uiReady = false
let pendingTab: 'files' | 'settings' | null = null
let lastUpdateNoticeVersion = ''
let sendQueue: string[] = []
let sendDraining = false
let sendTimer: NodeJS.Timeout | null = null
let sendToast: Notification | null = null
let sendToastCount = 0
let sendIntentSeen = false

function sendState(state: AppState): void {
  if (win && !win.isDestroyed()) win.webContents.send('state:update', state)
}

function showWindow(): void {
  if (!win) return
  if (win.isMinimized()) win.restore()
  win.show()
  win.focus()
}

function existingFiles(input: unknown): string[] {
  if (!Array.isArray(input)) return []
  const files: string[] = []
  const seen = new Set<string>()
  for (const raw of input.slice(0, 500)) {
    if (typeof raw !== 'string') continue
    const value = raw.trim()
    if (!value) continue
    try {
      const file = path.resolve(value)
      if (!fs.statSync(file).isFile()) continue
      const key = file.toLowerCase()
      if (seen.has(key)) continue
      seen.add(key)
      files.push(file)
      if (files.length >= MAX_SEND_FILES) break
    } catch {
      /* skip missing or inaccessible files */
    }
  }
  return files
}

function parseSendPaths(argv: string[]): string[] {
  const index = argv.indexOf(SEND_ARG)
  if (index < 0) return []
  return existingFiles(
    argv.slice(index + 1).filter((raw) => raw && !raw.startsWith('-'))
  )
}

function navigateTo(tab: 'files' | 'settings'): void {
  if (!booted || !win) return
  pendingTab = tab
  showWindow()
  if (uiReady && !win.isDestroyed()) {
    win.webContents.send('ui:navigate', tab)
    pendingTab = null
  }
}

function notifyUpdateAvailable(version: string): void {
  if (!version || version === lastUpdateNoticeVersion) return
  if (win?.isFocused()) return
  if (!Notification.isSupported()) return
  try {
    const toast = new Notification({
      title: 'LinkPhone update available',
      body: `v${version} is available. Open Settings to download it.`,
      icon: appIcon('icon.png'),
      silent: true
    })
    toast.on('click', () => navigateTo('settings'))
    toast.show()
    lastUpdateNoticeVersion = version
  } catch {
    /* notifications unavailable */
  }
}

function showQueueToast(): void {
  if (!Notification.isSupported() || sendQueue.length === 0) return
  if (sendToast && sendToastCount === sendQueue.length) return
  sendToast?.close()
  sendToast = null
  sendToastCount = sendQueue.length
  try {
    const toast = new Notification({
      title: 'Waiting for your phone',
      body: `${sendQueue.length} file${sendQueue.length === 1 ? '' : 's'} queued — connect your phone to send.`,
      silent: true
    })
    toast.on('click', () => navigateTo('files'))
    toast.on('close', () => {
      if (sendToast === toast) sendToast = null
    })
    toast.show()
    sendToast = toast
  } catch {
    /* notifications unavailable */
  }
}

function closeQueueToast(): void {
  sendToast?.close()
  sendToast = null
  sendToastCount = 0
}

function scheduleSendDrain(): void {
  if (sendTimer) clearTimeout(sendTimer)
  sendTimer = setTimeout(() => {
    sendTimer = null
    void drainSendQueue()
  }, 250)
}

async function drainSendQueue(): Promise<void> {
  if (sendDraining || sendQueue.length === 0) return
  if (!server || !server.hasConnectedPeer()) {
    showQueueToast()
    return
  }
  sendDraining = true
  closeQueueToast()
  const batch = sendQueue
  sendQueue = []
  try {
    await server.sendFiles(batch)
  } catch (error) {
    console.error('[send] file transfer failed', error)
  } finally {
    sendDraining = false
    if (sendQueue.length > 0 && server?.hasConnectedPeer()) scheduleSendDrain()
  }
}

function handleSendIntent(paths: string[]): void {
  sendIntentSeen = true
  const known = new Set(sendQueue.map((file) => file.toLowerCase()))
  for (const file of paths) {
    const key = file.toLowerCase()
    if (known.has(key)) continue
    known.add(key)
    sendQueue.push(file)
    if (sendQueue.length >= MAX_SEND_FILES) break
  }
  if (!booted) return
  navigateTo('files')
  scheduleSendDrain()
}

function createWindow(): void {
  const startHidden = process.argv.includes(HIDDEN_ARG)
  win = new BrowserWindow({
    width: 1080,
    height: 720,
    minWidth: 880,
    minHeight: 560,
    show: false,
    backgroundColor: '#000000',
    title: 'LinkPhone',
    icon: appIcon('icon.png'),
    webPreferences: {
      preload: path.join(__dirname, '../preload/index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  })

  win.on('ready-to-show', () => {
    if (!startHidden) win?.show()
  })
  win.on('focus', () => syncLaunchAtLogin())
  win.webContents.on('did-start-loading', () => {
    uiReady = false
  })
  win.on('close', (event) => {
    if (quitting) return
    event.preventDefault()
    win?.hide()
  })

  const devUrl = process.env['ELECTRON_RENDERER_URL']
  if (devUrl) void win.loadURL(devUrl)
  else void win.loadFile(path.join(__dirname, '../renderer/index.html'))
}

function createTray(): void {
  tray = new Tray(appIcon('tray.png'))
  tray.setToolTip('LinkPhone')
  tray.on('click', () => showWindow())
  rebuildTrayMenu()
}

function rebuildTrayMenu(): void {
  if (!tray) return
  const menu = Menu.buildFromTemplate([
    { label: 'Open LinkPhone', click: () => showWindow() },
    { type: 'separator' },
    {
      label: `Pairing code: ${store.pairingCode}`,
      click: () => {
        clipboard.writeText(store.pairingCode)
      }
    },
    {
      label: 'New pairing code',
      click: () => {
        store.setPairingCode(newPairCode())
        rebuildTrayMenu()
      }
    },
    { type: 'separator' },
    {
      label: 'Quit',
      click: () => {
        quitting = true
        app.quit()
      }
    }
  ])
  tray.setContextMenu(menu)
}

function registerIpc(): void {
  ipcMain.handle('state:get', () => store.snapshot())

  ipcMain.handle('ui:ready', () => {
    uiReady = true
    const tab = pendingTab
    pendingTab = null
    return tab
  })

  ipcMain.handle('pair:new', () => {
    store.setPairingCode(newPairCode())
    rebuildTrayMenu()
    return store.pairingCode
  })

  ipcMain.handle('addresses:refresh', () => {
    store.setAddresses(localAddresses())
    store.setAddresses6(localLinkLocalIPv6())
  })

  ipcMain.handle('device:forget', (_event, deviceId: unknown) => {
    if (typeof deviceId === 'string') store.forget(deviceId)
  })

  ipcMain.handle('files:send', async () => {
    const result = await dialog.showOpenDialog({
      title: 'Send to phone',
      properties: ['openFile', 'multiSelections']
    })
    if (result.canceled || result.filePaths.length === 0) return 0
    void server.sendFiles(result.filePaths)
    return result.filePaths.length
  })

  ipcMain.handle('files:send-paths', (event, paths: unknown) => {
    if (event.sender !== win?.webContents) return 0
    const files = existingFiles(paths)
    if (files.length > 0) handleSendIntent(files)
    return files.length
  })

  ipcMain.handle('clipboard:set', (_event, text: unknown) => {
    if (typeof text !== 'string') return
    clipboard.writeText(text)
    server.sendClipboard(text)
  })

  ipcMain.handle('clipboard:copy', (_event, text: unknown) => {
    if (typeof text === 'string') clipboard.writeText(text)
  })

  ipcMain.handle('clipboard:enable', (_event, enabled: unknown) => {
    store.setClipboardEnabled(Boolean(enabled))
  })

  ipcMain.handle('startup:set', (_event, enabled: unknown) => {
    if (typeof enabled === 'boolean') applyLaunchAtLogin(enabled)
  })

  const dismissNotificationEverywhere = (key: string): void => {
    store.dismissNotification(key)
    server.sendNotificationCancel([key])
  }

  ipcMain.handle('notifications:clear', () => {
    const keys = store.clearNotifications()
    server.sendNotificationCancel(keys)
  })

  ipcMain.handle('notification:dismiss', (_event, key: unknown) => {
    if (typeof key === 'string') dismissNotificationEverywhere(key)
  })

  ipcMain.handle('notification:reply', (_event, key: unknown, actionId: unknown, text: unknown) => {
    if (typeof key !== 'string' || typeof actionId !== 'string' || typeof text !== 'string') return
    server.sendNotificationAction(key, actionId, text)
  })

  ipcMain.handle('notification:action', (_event, key: unknown, actionId: unknown) => {
    if (typeof key !== 'string' || typeof actionId !== 'string') return
    if (!/^(content|\d{1,2})$/.test(actionId)) return
    server.sendNotificationAction(key, actionId)
  })

  ipcMain.handle('notification:set-muted', (_event, pkg: unknown, app: unknown, muted: unknown) => {
    if (typeof pkg !== 'string' || typeof app !== 'string' || typeof muted !== 'boolean') return
    store.setNotificationMuted(pkg, app, muted)
  })

  ipcMain.handle('notifications:clear-app', (_event, pkg: unknown, app: unknown) => {
    if (typeof pkg !== 'string' || typeof app !== 'string') return
    const keys = store.clearNotificationsForApp(pkg, app)
    server.sendNotificationCancel(keys)
  })

  ipcMain.handle('files:open-downloads', () => shell.openPath(store.downloadsDir))

  ipcMain.handle('sms:threads', () => server.requestSmsThreads())

  ipcMain.handle('sms:thread', (_event, threadId: unknown, address: unknown) => {
    if (typeof threadId !== 'string') return
    server.requestSmsThread(threadId, typeof address === 'string' ? address : '')
  })

  ipcMain.handle('sms:close', () => store.openSmsThread(null))

  ipcMain.handle('sms:send', (_event, threadId: unknown, address: unknown, body: unknown, subscriptionId: unknown) => {
    if (typeof threadId !== 'string' || typeof address !== 'string' || typeof body !== 'string') {
      return
    }
    const subId = typeof subscriptionId === 'number' && Number.isFinite(subscriptionId)
      ? Math.floor(subscriptionId)
      : undefined
    server.sendSms(threadId, address, body, subId)
  })

  ipcMain.handle('gallery:albums', () => server.requestGalleryAlbums())

  ipcMain.handle('gallery:album', (_event, albumId: unknown, name: unknown) => {
    if (typeof albumId !== 'string' || typeof name !== 'string') return
    server.openGalleryAlbum(albumId, name)
  })

  ipcMain.handle('gallery:close-album', () => server.closeGalleryAlbum())

  ipcMain.handle('gallery:more', (_event, albumId: unknown, offset: unknown) => {
    if (typeof albumId !== 'string' || typeof offset !== 'number') return
    server.loadMoreGallery(albumId, offset)
  })

  ipcMain.handle('gallery:thumbnails', (_event, photoIds: unknown) => {
    if (!Array.isArray(photoIds)) return
    server.requestGalleryThumbnails(photoIds.filter((id): id is string => typeof id === 'string'))
  })

  ipcMain.handle('gallery:preview', (_event, photoId: unknown, name: unknown) => {
    if (typeof photoId !== 'string' || typeof name !== 'string') return
    server.requestGalleryPreview(photoId, name)
  })

  ipcMain.handle('gallery:close-preview', () => server.closeGalleryPreview())

  ipcMain.handle('gallery:download', (_event, photoId: unknown) => {
    if (typeof photoId === 'string') server.downloadGalleryPhoto(photoId)
  })

  ipcMain.handle('update:check', () => updater.check(true))
  ipcMain.handle('update:download', () => updater.download())
  ipcMain.on('update:install', () => updater.install())

  ipcMain.handle('call:place', (_event, number: unknown, accountId: unknown) => {
    if (typeof number === 'string') {
      server.placeCall(number, typeof accountId === 'string' ? accountId : undefined)
    }
  })
  ipcMain.handle('call:answer', (_event, callId: unknown) => {
    if (typeof callId === 'string') server.answerCall(callId)
  })
  ipcMain.handle('call:reject', (_event, callId: unknown) => {
    if (typeof callId === 'string') server.rejectCall(callId)
  })
  ipcMain.handle('call:disconnect', (_event, callId: unknown) => {
    if (typeof callId === 'string') server.disconnectCall(callId)
  })
  ipcMain.handle('call:speaker', (_event, enabled: unknown) => {
    server.setCallSpeaker(Boolean(enabled))
  })
  ipcMain.handle('call:placed-dismiss', () => store.dismissCallPlaced())
  ipcMain.handle('call:error-dismiss', () => store.dismissCallError())

  ipcMain.handle('contacts:request', () => server.requestContacts())
  ipcMain.handle('calllog:request', () => server.requestCallHistory())
  ipcMain.handle('sims:request', () => server.requestSims())

  ipcMain.handle('shell:open', (_event, url: unknown) => {
    if (typeof url === 'string' && /^https?:\/\//i.test(url)) return shell.openExternal(url)
    return undefined
  })

  ipcMain.on('app:quit', () => {
    quitting = true
    app.quit()
  })

  ipcMain.on('app:hide', () => win?.hide())
}

async function bootstrap(): Promise<void> {
  app.setAppUserModelId('dev.linkphone.desktop')
  app.setName('LinkPhone')

  // No File/Edit/View menu on Windows/Linux; macOS keeps its required app menu.
  if (process.platform !== 'darwin') {
    Menu.setApplicationMenu(null)
  }

  store = new AppStore(app.getPath('userData'), app.getVersion())
  store.setPairingCode(newPairCode())
  syncLaunchAtLogin()

  server = new LinkServer(store)
  updater = new DesktopUpdater(store, app.isPackaged, notifyUpdateAvailable)
  powerMonitor.on('resume', () => {
    void updater.check(false)
  })
  watcher = new ClipboardWatcher(
    (text) => server.sendClipboard(text),
    () => store.clipboardEnabled
  )

  await server.start()
  server.on('peer-ready', () => {
    void drainSendQueue()
  })
  store.setAddresses(localAddresses())
  store.setAddresses6(localLinkLocalIPv6())

  try {
    advertisement = advertise({
      serverId: store.serverId,
      serverName: store.serverName,
      port: server.port
    })
  } catch {
    advertisement = null
  }

  server.on('clipboardIn', (text: string) => watcher.applyRemote(text))

  server.on('notification', (item) => {
    if (!Notification.isSupported()) return
    try {
      const n = new Notification({
        title: item.title || item.app,
        body: item.text,
        silent: false
      })
      n.on('click', () => {
        showWindow()
        store.dismissNotification(item.key)
        server.sendNotificationCancel([item.key])
      })
      n.show()
    } catch {
      /* notifications unavailable */
    }
  })

  server.on('incomingCall', (call: PhoneCallInfo) => {
    if (!Notification.isSupported()) return
    try {
      const toast = new Notification({
        title: 'Incoming phone call',
        body: call.displayName || call.number || 'Unknown caller',
        silent: false
      })
      toast.on('click', () => showWindow())
      toast.show()
    } catch {
      /* notifications unavailable */
    }
  })

  store.on('change', (state: AppState) => sendState(state))
  watcher.start()

  createWindow()
  createTray()
  registerIpc()
  booted = true
  if (sendIntentSeen || process.argv.includes(SEND_ARG)) {
    handleSendIntent(parseSendPaths(process.argv))
  } else if (sendQueue.length > 0) {
    navigateTo('files')
    scheduleSendDrain()
  }
  updater.start()

  app.on('activate', () => showWindow())
}

const gotLock = app.requestSingleInstanceLock()
if (!gotLock) {
  app.quit()
} else {
  app.on('second-instance', (_event, argv) => {
    if (argv.includes(SEND_ARG)) {
      handleSendIntent(parseSendPaths(argv))
    } else if (!argv.includes(HIDDEN_ARG)) {
      showWindow()
    }
  })

  void app.whenReady().then(bootstrap)

  app.on('window-all-closed', () => {
    // Stay resident in the tray on Windows/Linux.
    if (process.platform === 'darwin') return
  })

  app.on('before-quit', () => {
    quitting = true
    watcher?.stop()
    updater?.stop()
    advertisement?.stop()
    server?.stop()
  })
}

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

function sendState(state: AppState): void {
  if (win && !win.isDestroyed()) win.webContents.send('state:update', state)
}

function showWindow(): void {
  if (!win) return
  if (win.isMinimized()) win.restore()
  win.show()
  win.focus()
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

  ipcMain.handle('notifications:clear', () => store.clearNotifications())

  ipcMain.handle('notification:dismiss', (_event, key: unknown) => {
    if (typeof key === 'string') store.dismissNotification(key)
  })

  ipcMain.handle('notification:reply', (_event, key: unknown, actionId: unknown, text: unknown) => {
    if (typeof key !== 'string' || typeof actionId !== 'string' || typeof text !== 'string') return
    server.sendNotificationAction(key, actionId, text)
  })

  ipcMain.handle('files:open-downloads', () => shell.openPath(store.downloadsDir))

  ipcMain.handle('sms:threads', () => server.requestSmsThreads())

  ipcMain.handle('sms:thread', (_event, threadId: unknown, address: unknown) => {
    if (typeof threadId !== 'string') return
    server.requestSmsThread(threadId, typeof address === 'string' ? address : '')
  })

  ipcMain.handle('sms:close', () => store.openSmsThread(null))

  ipcMain.handle('sms:send', (_event, threadId: unknown, address: unknown, body: unknown) => {
    if (typeof threadId !== 'string' || typeof address !== 'string' || typeof body !== 'string') {
      return
    }
    server.sendSms(threadId, address, body)
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

  ipcMain.handle('call:place', (_event, number: unknown) => {
    if (typeof number === 'string') server.placeCall(number)
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
  updater = new DesktopUpdater(store, app.isPackaged)
  watcher = new ClipboardWatcher(
    (text) => server.sendClipboard(text),
    () => store.clipboardEnabled
  )

  await server.start()
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
  updater.start()

  app.on('activate', () => showWindow())
}

const gotLock = app.requestSingleInstanceLock()
if (!gotLock) {
  app.quit()
} else {
  app.on('second-instance', (_event, argv) => {
    if (!argv.includes(HIDDEN_ARG)) showWindow()
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

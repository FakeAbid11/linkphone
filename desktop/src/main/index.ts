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
import type { AppState } from '../shared/protocol'
import { ClipboardWatcher } from './clipboard'
import { newPairCode } from './crypto'
import { advertise, localAddresses, type Advertisement } from './discovery'
import { LinkServer } from './server'
import { AppStore } from './state'
import { DesktopUpdater } from './updater'

/* 32x32 phone glyph, generated offline; keeps the repo free of binary assets. */
const TRAY_ICON_B64 =
  'iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAABOElEQVR42u2X20pCURRF/YA+oJdulpVlfmwYEhERiUSEiSZmaqJJF7rQH02fVkw2x6Nrbn1zw3jbgznO29mZzOo4z8b4D2ksZXRz9AuFhYxvDX8QQ3zA4BvM2no2lfB+dMB2/wvGrHGDnbjx7ieMecf/I8iVA3Y6HzC8AezKAdn2OwxvALt6QOsNhjuAXDlgtzmG4Q1gVw7Ya7zC8AawqwfURzDcAeTKAbnaEIY3gF094H4Awx1Arhywf/cCwxvArhxwcNuH4Q1gVw+o9mC4A8iVAw4rzzC8AezKAfmbDgxvALt6wPUTDHcAuXLA0VUbhjeAXT3gsgVm7vHAkwOOLx4RMms8yYn6KyqcNxFD9D9hodxAEuGXT7sXHXByVkcSYcC0ewt5GxRLD1BYyiupeFpDGqvHq/dMALVvcVhpB/PlAAAAAElFTkSuQmCC'

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
  win = new BrowserWindow({
    width: 1080,
    height: 720,
    minWidth: 880,
    minHeight: 560,
    show: false,
    backgroundColor: '#0b1220',
    title: 'LinkPhone',
    icon: nativeImage.createFromBuffer(Buffer.from(TRAY_ICON_B64, 'base64')),
    webPreferences: {
      preload: path.join(__dirname, '../preload/index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  })

  win.on('ready-to-show', () => win?.show())
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
  const icon = nativeImage.createFromBuffer(Buffer.from(TRAY_ICON_B64, 'base64'))
  tray = new Tray(icon)
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

  store = new AppStore(app.getPath('userData'), app.getVersion())
  store.setPairingCode(newPairCode())

  server = new LinkServer(store)
  updater = new DesktopUpdater(store, app.isPackaged)
  watcher = new ClipboardWatcher(
    (text) => server.sendClipboard(text),
    () => store.clipboardEnabled
  )

  await server.start()
  store.setAddresses(localAddresses())

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
  app.on('second-instance', () => showWindow())

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

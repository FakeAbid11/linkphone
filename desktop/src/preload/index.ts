import { contextBridge, ipcRenderer, webUtils } from 'electron'
import type { LinkPhoneApi } from '../shared/api'
import type { AppState, GalleryPreview, NotificationItem, TransferItem } from '../shared/protocol'

const api: LinkPhoneApi = {
  getState: () => ipcRenderer.invoke('state:get') as Promise<AppState>,
  onState: (cb) => {
    const handler = (_event: Electron.IpcRendererEvent, state: AppState): void => cb(state)
    ipcRenderer.on('state:update', handler)
    return () => ipcRenderer.removeListener('state:update', handler)
  },
  onGalleryThumbs: (cb) => {
    const handler = (
      _event: Electron.IpcRendererEvent,
      items: Array<{ photoId: string; dataUrl: string }>
    ): void => cb(items)
    ipcRenderer.on('gallery:thumbs', handler)
    return () => ipcRenderer.removeListener('gallery:thumbs', handler)
  },
  onGalleryPreview: (cb) => {
    const handler = (_event: Electron.IpcRendererEvent, preview: GalleryPreview | null): void =>
      cb(preview)
    ipcRenderer.on('gallery:preview', handler)
    return () => ipcRenderer.removeListener('gallery:preview', handler)
  },
  onNavigate: (cb) => {
    const handler = (
      _event: Electron.IpcRendererEvent,
      tab: 'files' | 'settings'
    ): void => cb(tab)
    ipcRenderer.on('ui:navigate', handler)
    return () => ipcRenderer.removeListener('ui:navigate', handler)
  },
  uiReady: () => ipcRenderer.invoke('ui:ready') as Promise<'files' | 'settings' | null>,
  newPairingCode: () => ipcRenderer.invoke('pair:new') as Promise<string>,
  refreshAddresses: () => ipcRenderer.invoke('addresses:refresh') as Promise<void>,
  forgetDevice: (deviceId) => ipcRenderer.invoke('device:forget', deviceId) as Promise<void>,
  renameDevice: (deviceId, name) =>
    ipcRenderer.invoke('device:rename', deviceId, name) as Promise<void>,
  sendFiles: () => ipcRenderer.invoke('files:send') as Promise<number>,
  sendPhotosToPhone: () => ipcRenderer.invoke('files:send-photos') as Promise<number>,
  retryTransfer: (id) => ipcRenderer.invoke('files:retry', id) as Promise<void>,
  requestStorageList: (cursor) =>
    ipcRenderer.invoke('storage:list', cursor) as Promise<void>,
  pullStorageFile: (id, name, mime) =>
    ipcRenderer.invoke('storage:pull', id, name, mime) as Promise<void>,
  pathForFile: (file) => {
    try {
      return webUtils.getPathForFile(
        file as Parameters<typeof webUtils.getPathForFile>[0]
      )
    } catch {
      return ''
    }
  },
  sendFilesFromPaths: (paths) =>
    ipcRenderer.invoke('files:send-paths', paths) as Promise<number>,
  sendClipboard: (text) => ipcRenderer.invoke('clipboard:set', text) as Promise<boolean>,
  clearNotifications: () => ipcRenderer.invoke('notifications:clear') as Promise<void>,
  pauseNotifications: (until) =>
    ipcRenderer.invoke('notifications:pause', until) as Promise<void>,
  getNotificationHistory: () =>
    ipcRenderer.invoke('notifications:history') as Promise<NotificationItem[]>,
  clearNotificationHistory: () =>
    ipcRenderer.invoke('notifications:history-clear') as Promise<void>,
  dismissNotification: (key) => ipcRenderer.invoke('notification:dismiss', key) as Promise<void>,
  setNotificationMuted: (pkg, app, muted) =>
    ipcRenderer.invoke('notification:set-muted', pkg, app, muted) as Promise<void>,
  clearNotificationApp: (pkg, app) =>
    ipcRenderer.invoke('notifications:clear-app', pkg, app) as Promise<void>,
  sendNotificationReply: (key, actionId, text) =>
    ipcRenderer.invoke('notification:reply', key, actionId, text) as Promise<void>,
  sendNotificationAction: (key, actionId) =>
    ipcRenderer.invoke('notification:action', key, actionId) as Promise<void>,
  requestSmsThreads: () => ipcRenderer.invoke('sms:threads') as Promise<void>,
  openSmsThread: (threadId, address) =>
    ipcRenderer.invoke('sms:thread', threadId, address) as Promise<void>,
  closeSmsThread: () => ipcRenderer.invoke('sms:close') as Promise<void>,
  sendSms: (threadId, address, body, subscriptionId) =>
    ipcRenderer.invoke('sms:send', threadId, address, body, subscriptionId) as Promise<
      'queued' | 'no-peer' | 'invalid'
    >,
  startSmsConversation: (address, body, subscriptionId) =>
    ipcRenderer.invoke('sms:start', address, body, subscriptionId) as Promise<
      'queued' | 'invalid' | 'no-peer'
    >,
  requestGalleryAlbums: (kind) =>
    ipcRenderer.invoke('gallery:albums', kind) as Promise<void>,
  openGalleryAlbum: (kind, albumId, name) =>
    ipcRenderer.invoke('gallery:album', kind, albumId, name) as Promise<void>,
  closeGalleryAlbum: () => ipcRenderer.invoke('gallery:close-album') as Promise<void>,
  loadMoreGallery: (kind, albumId, offset) =>
    ipcRenderer.invoke('gallery:more', kind, albumId, offset) as Promise<void>,
  requestGalleryThumbnails: (kind, photoIds) =>
    ipcRenderer.invoke('gallery:thumbnails', kind, photoIds) as Promise<void>,
  requestGalleryPreview: (kind, photoId, name) =>
    ipcRenderer.invoke('gallery:preview', kind, photoId, name) as Promise<void>,
  closeGalleryPreview: () => ipcRenderer.invoke('gallery:close-preview') as Promise<void>,
  downloadGalleryPhoto: (kind, photoId) =>
    ipcRenderer.invoke('gallery:download', kind, photoId) as Promise<void>,
  checkDesktopUpdate: () => ipcRenderer.invoke('update:check') as Promise<void>,
  downloadDesktopUpdate: () => ipcRenderer.invoke('update:download') as Promise<void>,
  installDesktopUpdate: () => ipcRenderer.send('update:install'),
  placeCall: (number, accountId) =>
    ipcRenderer.invoke('call:place', number, accountId) as Promise<void>,
  answerCall: (callId) => ipcRenderer.invoke('call:answer', callId) as Promise<void>,
  rejectCall: (callId) => ipcRenderer.invoke('call:reject', callId) as Promise<void>,
  disconnectCall: (callId) => ipcRenderer.invoke('call:disconnect', callId) as Promise<void>,
  setCallSpeaker: (enabled) => ipcRenderer.invoke('call:speaker', enabled) as Promise<void>,
  dismissCallPlaced: () => ipcRenderer.invoke('call:placed-dismiss') as Promise<void>,
  dismissCallError: () => ipcRenderer.invoke('call:error-dismiss') as Promise<void>,
  ringPhone: (deviceId) => ipcRenderer.invoke('phone:ring', deviceId) as Promise<void>,
  stopRingPhone: (deviceId) => ipcRenderer.invoke('phone:ring-stop', deviceId) as Promise<void>,
  sendMediaAction: (action) => ipcRenderer.invoke('media:action', action) as Promise<void>,
  requestContacts: () => ipcRenderer.invoke('contacts:request') as Promise<void>,
  requestCallHistory: () => ipcRenderer.invoke('calllog:request') as Promise<void>,
  requestSims: () => ipcRenderer.invoke('sims:request') as Promise<void>,
  setClipboardEnabled: (enabled) =>
    ipcRenderer.invoke('clipboard:enable', enabled) as Promise<void>,
  setLaunchAtLogin: (enabled) =>
    ipcRenderer.invoke('startup:set', enabled) as Promise<void>,
  openDownloads: () => ipcRenderer.invoke('files:open-downloads') as Promise<void>,
  copyToClipboard: (text) => ipcRenderer.invoke('clipboard:copy', text) as Promise<void>,
  openExternal: (url) => ipcRenderer.invoke('shell:open', url) as Promise<void>,
  onTransfer: (cb) => {
    const handler = (_event: Electron.IpcRendererEvent, transfer: TransferItem): void => cb(transfer)
    ipcRenderer.on('transfer:update', handler)
    return () => ipcRenderer.removeListener('transfer:update', handler)
  },
  quit: () => ipcRenderer.send('app:quit'),
  hideToTray: () => ipcRenderer.send('app:hide')
}

contextBridge.exposeInMainWorld('linkphone', api)

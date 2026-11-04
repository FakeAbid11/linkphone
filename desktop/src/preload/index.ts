import { contextBridge, ipcRenderer, webUtils } from 'electron'
import type { LinkPhoneApi } from '../shared/api'
import type { AppState, TransferItem } from '../shared/protocol'

const api: LinkPhoneApi = {
  getState: () => ipcRenderer.invoke('state:get') as Promise<AppState>,
  onState: (cb) => {
    const handler = (_event: Electron.IpcRendererEvent, state: AppState): void => cb(state)
    ipcRenderer.on('state:update', handler)
    return () => ipcRenderer.removeListener('state:update', handler)
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
  sendFiles: () => ipcRenderer.invoke('files:send') as Promise<number>,
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
  sendClipboard: (text) => ipcRenderer.invoke('clipboard:set', text) as Promise<void>,
  clearNotifications: () => ipcRenderer.invoke('notifications:clear') as Promise<void>,
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
    ipcRenderer.invoke('sms:send', threadId, address, body, subscriptionId) as Promise<void>,
  requestGalleryAlbums: () => ipcRenderer.invoke('gallery:albums') as Promise<void>,
  openGalleryAlbum: (albumId, name) =>
    ipcRenderer.invoke('gallery:album', albumId, name) as Promise<void>,
  closeGalleryAlbum: () => ipcRenderer.invoke('gallery:close-album') as Promise<void>,
  loadMoreGallery: (albumId, offset) =>
    ipcRenderer.invoke('gallery:more', albumId, offset) as Promise<void>,
  requestGalleryThumbnails: (photoIds) =>
    ipcRenderer.invoke('gallery:thumbnails', photoIds) as Promise<void>,
  requestGalleryPreview: (photoId, name) =>
    ipcRenderer.invoke('gallery:preview', photoId, name) as Promise<void>,
  closeGalleryPreview: () => ipcRenderer.invoke('gallery:close-preview') as Promise<void>,
  downloadGalleryPhoto: (photoId) =>
    ipcRenderer.invoke('gallery:download', photoId) as Promise<void>,
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

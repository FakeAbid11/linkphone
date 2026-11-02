import { contextBridge, ipcRenderer } from 'electron'
import type { LinkPhoneApi } from '../shared/api'
import type { AppState, TransferItem } from '../shared/protocol'

const api: LinkPhoneApi = {
  getState: () => ipcRenderer.invoke('state:get') as Promise<AppState>,
  onState: (cb) => {
    const handler = (_event: Electron.IpcRendererEvent, state: AppState): void => cb(state)
    ipcRenderer.on('state:update', handler)
    return () => ipcRenderer.removeListener('state:update', handler)
  },
  newPairingCode: () => ipcRenderer.invoke('pair:new') as Promise<string>,
  forgetDevice: (deviceId) => ipcRenderer.invoke('device:forget', deviceId) as Promise<void>,
  sendFiles: () => ipcRenderer.invoke('files:send') as Promise<number>,
  sendClipboard: (text) => ipcRenderer.invoke('clipboard:set', text) as Promise<void>,
  clearNotifications: () => ipcRenderer.invoke('notifications:clear') as Promise<void>,
  dismissNotification: (key) => ipcRenderer.invoke('notification:dismiss', key) as Promise<void>,
  sendNotificationReply: (key, actionId, text) =>
    ipcRenderer.invoke('notification:reply', key, actionId, text) as Promise<void>,
  requestSmsThreads: () => ipcRenderer.invoke('sms:threads') as Promise<void>,
  openSmsThread: (threadId, address) =>
    ipcRenderer.invoke('sms:thread', threadId, address) as Promise<void>,
  closeSmsThread: () => ipcRenderer.invoke('sms:close') as Promise<void>,
  sendSms: (threadId, address, body) =>
    ipcRenderer.invoke('sms:send', threadId, address, body) as Promise<void>,
  setClipboardEnabled: (enabled) =>
    ipcRenderer.invoke('clipboard:enable', enabled) as Promise<void>,
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

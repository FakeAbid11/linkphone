import type { AppState, TransferItem } from './protocol'

export interface LinkPhoneApi {
  getState: () => Promise<AppState>
  onState: (cb: (state: AppState) => void) => () => void
  newPairingCode: () => Promise<string>
  forgetDevice: (deviceId: string) => Promise<void>
  sendFiles: () => Promise<number>
  sendClipboard: (text: string) => Promise<void>
  clearNotifications: () => Promise<void>
  dismissNotification: (key: string) => Promise<void>
  sendNotificationReply: (key: string, actionId: string, text: string) => Promise<void>
  requestSmsThreads: () => Promise<void>
  openSmsThread: (threadId: string, address: string) => Promise<void>
  closeSmsThread: () => Promise<void>
  sendSms: (threadId: string, address: string, body: string) => Promise<void>
  requestGalleryAlbums: () => Promise<void>
  openGalleryAlbum: (albumId: string, name: string) => Promise<void>
  closeGalleryAlbum: () => Promise<void>
  loadMoreGallery: (albumId: string, offset: number) => Promise<void>
  requestGalleryThumbnails: (photoIds: string[]) => Promise<void>
  requestGalleryPreview: (photoId: string, name: string) => Promise<void>
  closeGalleryPreview: () => Promise<void>
  downloadGalleryPhoto: (photoId: string) => Promise<void>
  setClipboardEnabled: (enabled: boolean) => Promise<void>
  openDownloads: () => Promise<void>
  copyToClipboard: (text: string) => Promise<void>
  openExternal: (url: string) => Promise<void>
  onTransfer: (cb: (transfer: TransferItem) => void) => () => void
  quit: () => void
  hideToTray: () => void
}

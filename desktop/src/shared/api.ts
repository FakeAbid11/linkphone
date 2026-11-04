import type { AppState, NotificationItem, TransferItem } from './protocol'

export interface LinkPhoneApi {
  getState: () => Promise<AppState>
  onState: (cb: (state: AppState) => void) => () => void
  onNavigate: (cb: (tab: 'files' | 'settings') => void) => () => void
  uiReady: () => Promise<'files' | 'settings' | null>
  newPairingCode: () => Promise<string>
  refreshAddresses: () => Promise<void>
  forgetDevice: (deviceId: string) => Promise<void>
  sendFiles: () => Promise<number>
  sendPhotosToPhone: () => Promise<number>
  retryTransfer: (id: string) => Promise<void>
  requestStorageList: (cursor?: string) => Promise<void>
  pullStorageFile: (id: string, name: string, mime: string) => Promise<void>
  pathForFile: (file: unknown) => string
  sendFilesFromPaths: (paths: string[]) => Promise<number>
  sendClipboard: (text: string) => Promise<boolean>
  clearNotifications: () => Promise<void>
  getNotificationHistory: () => Promise<NotificationItem[]>
  clearNotificationHistory: () => Promise<void>
  dismissNotification: (key: string) => Promise<void>
  setNotificationMuted: (pkg: string, app: string, muted: boolean) => Promise<void>
  clearNotificationApp: (pkg: string, app: string) => Promise<void>
  sendNotificationReply: (key: string, actionId: string, text: string) => Promise<void>
  sendNotificationAction: (key: string, actionId: string) => Promise<void>
  requestSmsThreads: () => Promise<void>
  openSmsThread: (threadId: string, address: string) => Promise<void>
  closeSmsThread: () => Promise<void>
  sendSms: (
    threadId: string,
    address: string,
    body: string,
    subscriptionId?: number
  ) => Promise<'queued' | 'no-peer' | 'invalid'>
  startSmsConversation: (
    address: string,
    body: string,
    subscriptionId?: number
  ) => Promise<'queued' | 'invalid' | 'no-peer'>
  requestGalleryAlbums: () => Promise<void>
  openGalleryAlbum: (albumId: string, name: string) => Promise<void>
  closeGalleryAlbum: () => Promise<void>
  loadMoreGallery: (albumId: string, offset: number) => Promise<void>
  requestGalleryThumbnails: (photoIds: string[]) => Promise<void>
  requestGalleryPreview: (photoId: string, name: string) => Promise<void>
  closeGalleryPreview: () => Promise<void>
  downloadGalleryPhoto: (photoId: string) => Promise<void>
  checkDesktopUpdate: () => Promise<void>
  downloadDesktopUpdate: () => Promise<void>
  installDesktopUpdate: () => void
  placeCall: (number: string, accountId?: string) => Promise<void>
  answerCall: (callId: string) => Promise<void>
  rejectCall: (callId: string) => Promise<void>
  disconnectCall: (callId: string) => Promise<void>
  setCallSpeaker: (enabled: boolean) => Promise<void>
  dismissCallPlaced: () => Promise<void>
  dismissCallError: () => Promise<void>
  ringPhone: (deviceId: string) => Promise<void>
  stopRingPhone: (deviceId: string) => Promise<void>
  sendMediaAction: (action: 'play' | 'pause' | 'next' | 'previous') => Promise<void>
  requestContacts: () => Promise<void>
  requestCallHistory: () => Promise<void>
  requestSims: () => Promise<void>
  setClipboardEnabled: (enabled: boolean) => Promise<void>
  setLaunchAtLogin: (enabled: boolean) => Promise<void>
  openDownloads: () => Promise<void>
  copyToClipboard: (text: string) => Promise<void>
  openExternal: (url: string) => Promise<void>
  onTransfer: (cb: (transfer: TransferItem) => void) => () => void
  quit: () => void
  hideToTray: () => void
}

/**
 * LinkPhone wire protocol v1 — shared by the Electron main process and the renderer.
 * See docs/PROTOCOL.md for the full description.
 */

export const PROTO_VERSION = 1
export const CAP_NOTIF_PROGRESS = 'notif.progress'
export const SERVICE_TYPE = 'linkphone'
export const DEFAULT_PORT = 8765
export const MAX_FILE_CHUNK = 64 * 1024
export const MAX_CONTACTS = 800
export const MAX_CONTACT_NUMBERS = 5
export const MAX_CALL_LOG = 200

/** Binary frame plaintext kinds (first byte after decryption). */
export const KIND_CONTROL = 0x10
export const KIND_FILE = 0x20

export type Direction = 'c2s' | 's2c'
export type Platform = 'android' | 'ios' | 'desktop'

export interface NotificationAction {
  id: string
  label: string
  reply: boolean
}

export interface NotificationItem {
  key: string
  app: string
  pkg: string
  title: string
  text: string
  when: number
  canReply: boolean
  actions: NotificationAction[]
  deviceId: string
  dismissed?: boolean
  progress?: number
  progressMax?: number
  indeterminate?: boolean
  ongoing?: boolean
}

export interface MutedApp {
  pkg: string
  app: string
}

export type TransferStatus = 'pending' | 'active' | 'done' | 'failed' | 'declined' | 'canceled'

export interface SmsThread {
  threadId: string
  address: string
  name?: string
  snippet: string
  date: number
  count: number
  subscriptionId?: number
  simLabel?: string
}

export interface SmsMessage {
  id: string
  threadId: string
  address: string
  name?: string
  body: string
  date: number
  outgoing: boolean
  pending?: boolean
  failed?: boolean
  subscriptionId?: number
  simLabel?: string
}

export interface SmsActiveThread {
  threadId: string
  address: string
  name?: string
  messages: SmsMessage[]
}

export interface GalleryAlbum {
  id: string
  name: string
  count: number
  coverPhotoId: string
}

export interface GalleryPhoto {
  id: string
  albumId: string
  name: string
  mime: string
  size: number
  dateTaken: number
  width: number
  height: number
}

export interface GalleryPreview {
  photoId: string
  name: string
  dataUrl: string
  loading: boolean
  error?: string
}

export type DesktopUpdateStatus =
  | 'idle'
  | 'checking'
  | 'available'
  | 'downloading'
  | 'ready'
  | 'up-to-date'
  | 'error'

export interface DesktopUpdateState {
  status: DesktopUpdateStatus
  currentVersion: string
  availableVersion?: string
  progress?: number
  message?: string
  checkedAt?: number
}

export type PhoneCallPhase =
  | 'new'
  | 'connecting'
  | 'dialing'
  | 'ringing'
  | 'active'
  | 'held'
  | 'disconnecting'
  | 'disconnected'
  | 'unknown'

export interface PhoneCallInfo {
  id: string
  number: string
  displayName: string
  phase: PhoneCallPhase
  incoming: boolean
  startedAt: number
  simLabel?: string
}

export interface PhoneContactNumber {
  number: string
  label: string
}

export interface PhoneContact {
  id: string
  name: string
  numbers: PhoneContactNumber[]
}

export interface SimInfo {
  id: string
  label: string
  carrier?: string
  slot?: number
  isDefault?: boolean
}

export interface CallAccountInfo {
  id: string
  label: string
  isDefault?: boolean
}

export type CallLogType =
  | 'incoming'
  | 'outgoing'
  | 'missed'
  | 'rejected'
  | 'blocked'
  | 'voicemail'
  | 'unknown'

export interface CallLogEntry {
  id: string
  number: string
  name?: string
  type: CallLogType
  date: number
  duration: number
  simLabel?: string
}

export interface TransferItem {
  id: string
  name: string
  size: number
  received: number
  direction: 'in' | 'out'
  status: TransferStatus
  path?: string
  error?: string
  deviceId: string
  startedAt: number
}

export interface DeviceInfo {
  deviceId: string
  name: string
  platform: Platform
  paired: boolean
  connected: boolean
  address?: string
  lastSeen?: number
  batteryLevel?: number
  charging?: boolean
}

export interface AppState {
  serverId: string
  serverName: string
  port: number
  pairingCode: string
  addresses: string[]
  /** Link-local IPv6 addresses, tried when the router isolates IPv4 clients. */
  addresses6: string[]
  devices: DeviceInfo[]
  notifications: NotificationItem[]
  mutedNotifications: MutedApp[]
  transfers: TransferItem[]
  smsThreads: SmsThread[]
  smsActive: SmsActiveThread | null
  smsLoading: boolean
  galleryAlbums: GalleryAlbum[]
  galleryPhotos: GalleryPhoto[]
  galleryAlbumId: string | null
  galleryAlbumName: string
  galleryNextOffset: number
  galleryHasMore: boolean
  galleryLoading: boolean
  galleryThumbs: Record<string, string>
  galleryPreview: GalleryPreview | null
  galleryError: string
  desktopUpdate: DesktopUpdateState
  phoneCalls: PhoneCallInfo[]
  callDialerReady: boolean
  callOutgoingReady: boolean
  callControlReady: boolean
  callPlaced: { number: string; at: number } | null
  callSpeaker: boolean
  callMuted: boolean
  callError: string
  phoneContacts: PhoneContact[]
  contactsGranted: boolean
  contactsLoading: boolean
  phoneSims: SimInfo[]
  callAccounts: CallAccountInfo[]
  simsGranted: boolean
  simsLoading: boolean
  callHistory: CallLogEntry[]
  callLogGranted: boolean
  callLogLoading: boolean
  launchAtLogin: boolean
  launchAtLoginSupported: boolean
  clipboardEnabled: boolean
  downloadsDir: string
  clippingInFlight: boolean
}

/* ------------------------------------------------------------------ */
/* Control messages                                                     */
/* ------------------------------------------------------------------ */

export interface HelloMsg {
  t: 'hello'
  proto: number
  deviceId: string
  deviceName: string
  platform: Platform
  /** Set when the user explicitly initiated pairing (QR or typed code). */
  pair?: boolean
}

export interface HelloResultMsg {
  t: 'hello.result'
  proto: number
  serverId: string
  serverName: string
  needPair: boolean
  needAuth: boolean
  /** Optional capability strings advertised by the PC. */
  caps?: string[]
  /** base64, 16 random bytes. Present when needPair. */
  salt?: string
}

export interface PairRequestMsg {
  t: 'pair.request'
  deviceName: string
  platform: Platform
  /** base64 HMAC-SHA256(psk, "pair:" + deviceId) */
  proof: string
}

export interface PairOkMsg {
  t: 'pair.ok'
  serverId: string
  serverName: string
}

export interface PairRejectMsg {
  t: 'pair.reject'
  reason: string
}

export interface AuthChallengeMsg {
  t: 'auth.challenge'
  /** base64, 32 random bytes */
  nonce: string
}

export interface AuthResponseMsg {
  t: 'auth.response'
  /** base64 HMAC-SHA256(psk, "auth:" + nonce) */
  proof: string
}

export interface AuthOkMsg {
  t: 'auth.ok'
  serverId: string
  serverName: string
}

export interface NotifPostMsg {
  t: 'notif.post'
  notif: Omit<NotificationItem, 'deviceId' | 'dismissed'>
}

export interface NotifDismissMsg {
  t: 'notif.dismiss'
  key: string
}

export interface NotifActionMsg {
  t: 'notif.action'
  key: string
  actionId: string
  replyText?: string
}

export interface ClipSetMsg {
  t: 'clip.set'
  text: string
  ts: number
}

export interface FileOfferMsg {
  t: 'file.offer'
  id: string
  name: string
  size: number
  mime: string
}

export interface FileAcceptMsg {
  t: 'file.accept'
  id: string
}

export interface FileDeclineMsg {
  t: 'file.decline'
  id: string
  reason?: string
}

export interface FileAckMsg {
  t: 'file.ack'
  id: string
  offset: number
}

export interface FileDoneMsg {
  t: 'file.done'
  id: string
}

export interface FileCancelMsg {
  t: 'file.cancel'
  id: string
  reason?: string
}

export interface PingMsg {
  t: 'ping'
  ts: number
}

export interface PongMsg {
  t: 'pong'
  ts: number
}

/* SMS ------------------------------------------------------------------ */

/** desktop -> phone */
export interface SmsThreadsRequestMsg {
  t: 'sms.threads.request'
}

/** phone -> desktop */
export interface SmsThreadsMsg {
  t: 'sms.threads'
  threads: SmsThread[]
}

/** desktop -> phone */
export interface SmsThreadRequestMsg {
  t: 'sms.thread.request'
  threadId: string
}

/** phone -> desktop */
export interface SmsThreadMsg {
  t: 'sms.thread'
  threadId: string
  address: string
  messages: SmsMessage[]
}

/** desktop -> phone */
export interface SmsSendMsg {
  t: 'sms.send'
  tempId: string
  address: string
  body: string
  subscriptionId?: number
}

/** phone -> desktop */
export interface SmsSentMsg {
  t: 'sms.sent'
  tempId: string
  ok: boolean
  error?: string
}

/** phone -> desktop: a message arrived while connected */
export interface SmsNewMsg {
  t: 'sms.new'
  message: SmsMessage
}

/** phone -> desktop: an SMS operation failed (permissions, SIM, ...) */
export interface SmsErrorMsg {
  t: 'sms.error'
  op: string
  error: string
}

/* Device state ---------------------------------------------------------- */

export interface DeviceStatusMsg {
  t: 'device.status'
  batteryLevel: number
  charging: boolean
}

/* Gallery --------------------------------------------------------------- */

export interface GalleryAlbumsRequestMsg {
  t: 'gallery.albums.request'
}

export interface GalleryAlbumsMsg {
  t: 'gallery.albums'
  albums: GalleryAlbum[]
}

export interface GalleryPhotosRequestMsg {
  t: 'gallery.photos.request'
  albumId: string
  offset: number
  limit: number
}

export interface GalleryPhotosMsg {
  t: 'gallery.photos'
  albumId: string
  offset: number
  photos: GalleryPhoto[]
  hasMore: boolean
}

export interface GalleryThumbsRequestMsg {
  t: 'gallery.thumbnails.request'
  photoIds: string[]
}

export interface GalleryThumbData {
  photoId: string
  mime: string
  data: string
}

export interface GalleryThumbsMsg {
  t: 'gallery.thumbnails'
  items: GalleryThumbData[]
}

export interface GalleryPreviewRequestMsg {
  t: 'gallery.preview.request'
  photoId: string
}

export interface GalleryPreviewMsg {
  t: 'gallery.preview'
  photoId: string
  mime: string
  data: string
}

export interface GalleryDownloadMsg {
  t: 'gallery.download'
  photoId: string
}

export interface GalleryErrorMsg {
  t: 'gallery.error'
  op: string
  error: string
}

/* Calls ----------------------------------------------------------------- */

export interface CallStateMsg {
  t: 'call.state'
  calls: PhoneCallInfo[]
  dialerReady: boolean
  outgoingReady: boolean
  controlReady: boolean
  speaker: boolean
  muted: boolean
}

export interface CallPlaceMsg {
  t: 'call.place'
  number: string
  accountId?: string
}

export interface CallPlacedMsg {
  t: 'call.placed'
  number: string
}

export interface CallAnswerMsg {
  t: 'call.answer'
  callId: string
}

export interface CallRejectMsg {
  t: 'call.reject'
  callId: string
}

export interface CallDisconnectMsg {
  t: 'call.disconnect'
  callId: string
}

export interface CallSpeakerMsg {
  t: 'call.speaker'
  enabled: boolean
}

export interface CallErrorMsg {
  t: 'call.error'
  error: string
}

/* SIMs ------------------------------------------------------------------ */

/** desktop -> phone */
export interface SimsRequestMsg {
  t: 'sims.request'
}

/** phone -> desktop */
export interface SimsSyncMsg {
  t: 'sims.sync'
  sms: SimInfo[]
  call: CallAccountInfo[]
  granted: boolean
}

/* Contacts and call history --------------------------------------------- */

/** desktop -> phone */
export interface ContactsRequestMsg {
  t: 'contacts.request'
}

/** phone -> desktop */
export interface ContactsSyncMsg {
  t: 'contacts.sync'
  contacts: PhoneContact[]
  granted: boolean
}

/** desktop -> phone */
export interface CallLogRequestMsg {
  t: 'calllog.request'
  limit?: number
}

/** phone -> desktop */
export interface CallLogSyncMsg {
  t: 'calllog.sync'
  entries: CallLogEntry[]
  granted: boolean
}

export type ControlMsg =
  | HelloMsg
  | HelloResultMsg
  | PairRequestMsg
  | PairOkMsg
  | PairRejectMsg
  | AuthChallengeMsg
  | AuthResponseMsg
  | AuthOkMsg
  | NotifPostMsg
  | NotifDismissMsg
  | NotifActionMsg
  | ClipSetMsg
  | FileOfferMsg
  | FileAcceptMsg
  | FileDeclineMsg
  | FileAckMsg
  | FileDoneMsg
  | FileCancelMsg
  | PingMsg
  | PongMsg
  | SmsThreadsRequestMsg
  | SmsThreadsMsg
  | SmsThreadRequestMsg
  | SmsThreadMsg
  | SmsSendMsg
  | SmsSentMsg
  | SmsNewMsg
  | SmsErrorMsg
  | DeviceStatusMsg
  | GalleryAlbumsRequestMsg
  | GalleryAlbumsMsg
  | GalleryPhotosRequestMsg
  | GalleryPhotosMsg
  | GalleryThumbsRequestMsg
  | GalleryThumbsMsg
  | GalleryPreviewRequestMsg
  | GalleryPreviewMsg
  | GalleryDownloadMsg
  | GalleryErrorMsg
  | CallStateMsg
  | CallPlaceMsg
  | CallAnswerMsg
  | CallRejectMsg
  | CallDisconnectMsg
  | CallSpeakerMsg
  | CallErrorMsg
  | SimsRequestMsg
  | SimsSyncMsg
  | ContactsRequestMsg
  | ContactsSyncMsg
  | CallLogRequestMsg
  | CallLogSyncMsg

/** Loose view used when reading untrusted input off the wire. */
export type RawMsg = { t?: unknown; [key: string]: unknown }

export function isRawMsg(value: unknown): value is RawMsg {
  return typeof value === 'object' && value !== null
}

export function str(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback
}

export function num(value: unknown, fallback = 0): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

export function bool(value: unknown, fallback = false): boolean {
  return typeof value === 'boolean' ? value : fallback
}

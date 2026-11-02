/**
 * LinkPhone wire protocol v1 — shared by the Electron main process and the renderer.
 * See docs/PROTOCOL.md for the full description.
 */

export const PROTO_VERSION = 1
export const SERVICE_TYPE = 'linkphone'
export const DEFAULT_PORT = 8765
export const MAX_FILE_CHUNK = 64 * 1024

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
}

export type TransferStatus = 'pending' | 'active' | 'done' | 'failed' | 'declined' | 'canceled'

export interface SmsThread {
  threadId: string
  address: string
  snippet: string
  date: number
  count: number
}

export interface SmsMessage {
  id: string
  threadId: string
  address: string
  body: string
  date: number
  outgoing: boolean
  pending?: boolean
  failed?: boolean
}

export interface SmsActiveThread {
  threadId: string
  address: string
  messages: SmsMessage[]
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
}

export interface AppState {
  serverId: string
  serverName: string
  port: number
  pairingCode: string
  addresses: string[]
  devices: DeviceInfo[]
  notifications: NotificationItem[]
  transfers: TransferItem[]
  smsThreads: SmsThread[]
  smsActive: SmsActiveThread | null
  smsLoading: boolean
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
}

export interface HelloResultMsg {
  t: 'hello.result'
  proto: number
  serverId: string
  serverName: string
  needPair: boolean
  needAuth: boolean
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

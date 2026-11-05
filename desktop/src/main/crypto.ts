import crypto from 'node:crypto'
import type { Direction } from '../shared/protocol'

/** 32 unambiguous characters -> 5 bits each. 8 chars = 40 bits of pairing entropy. */
export const PAIR_CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
export const PAIR_CODE_LENGTH = 8
const PBKDF2_ROUNDS = 600_000

export function newPairCode(): string {
  let code = ''
  for (let i = 0; i < PAIR_CODE_LENGTH; i++) {
    code += PAIR_CODE_ALPHABET[crypto.randomInt(PAIR_CODE_ALPHABET.length)]
  }
  return code
}

export function normalizeCode(code: string): string {
  // Must match Crypto.normalize() on Android byte for byte.
  return code.toUpperCase().replace(/[^A-Z0-9]/g, '')
}

export function deriveKey(code: string, salt: Buffer): Buffer {
  return crypto.pbkdf2Sync(normalizeCode(code), salt, PBKDF2_ROUNDS, 32, 'sha256')
}

/** Async variant so unauthenticated pairing cannot block the Electron main thread. */
export function deriveKeyAsync(code: string, salt: Buffer): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    crypto.pbkdf2(
      normalizeCode(code),
      salt,
      PBKDF2_ROUNDS,
      32,
      'sha256',
      (err, derived) => (err ? reject(err) : resolve(derived))
    )
  })
}

export function hmac(key: Buffer, label: string, data?: Buffer): Buffer {
  const h = crypto.createHmac('sha256', key)
  h.update(label, 'utf8')
  if (data && data.length > 0) h.update(data)
  return h.digest()
}

export function safeEqual(a: Buffer, b: Buffer): boolean {
  if (a.length !== b.length) return false
  return crypto.timingSafeEqual(a, b)
}

function aad(direction: Direction, seq: number): Buffer {
  const head = Buffer.from(`lp1:${direction}:`, 'utf8')
  const tail = Buffer.alloc(8)
  tail.writeBigUInt64BE(BigInt(seq))
  return Buffer.concat([head, tail])
}

/**
 * frame = nonce(12) || ciphertext || tag(16)
 * AAD binds protocol version, direction and monotonic sequence.
 */
export function sealFrame(key: Buffer, direction: Direction, seq: number, plaintext: Buffer): Buffer {
  const nonce = crypto.randomBytes(12)
  const cipher = crypto.createCipheriv('aes-256-gcm', key, nonce)
  cipher.setAAD(aad(direction, seq))
  const body = Buffer.concat([cipher.update(plaintext), cipher.final()])
  return Buffer.concat([nonce, body, cipher.getAuthTag()])
}

export function openFrame(key: Buffer, direction: Direction, seq: number, frame: Buffer): Buffer {
  if (frame.length < 12 + 16) throw new Error('frame too short')
  const nonce = frame.subarray(0, 12)
  const tag = frame.subarray(frame.length - 16)
  const body = frame.subarray(12, frame.length - 16)
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, nonce)
  decipher.setAAD(aad(direction, seq))
  decipher.setAuthTag(tag)
  return Buffer.concat([decipher.update(body), decipher.final()])
}

export function encodeControl(kindControl: number, json: unknown): Buffer {
  return Buffer.concat([Buffer.from([kindControl]), Buffer.from(JSON.stringify(json), 'utf8')])
}

/** payload = kind(1) || idLen(1) || id || offset(8 BE) || data */
export function fileChunkPayload(
  kindFile: number,
  transferId: string,
  offset: number,
  chunk: Buffer
): Buffer {
  const idBuf = Buffer.from(transferId, 'utf8')
  if (idBuf.length > 255) throw new Error('transfer id too long')
  const head = Buffer.alloc(2)
  head.writeUInt8(kindFile, 0)
  head.writeUInt8(idBuf.length, 1)
  const offsetBuf = Buffer.alloc(8)
  offsetBuf.writeBigUInt64BE(BigInt(offset))
  return Buffer.concat([head, idBuf, offsetBuf, chunk])
}

export function decodeFileChunk(payload: Buffer): { transferId: string; offset: number; data: Buffer } {
  if (payload.length < 2 + 8) throw new Error('chunk too short')
  const idLen = payload.readUInt8(1)
  if (payload.length < 2 + idLen + 8) throw new Error('chunk truncated')
  const transferId = payload.subarray(2, 2 + idLen).toString('utf8')
  const offset = Number(payload.readBigUInt64BE(2 + idLen))
  const data = payload.subarray(2 + idLen + 8)
  return { transferId, offset, data }
}

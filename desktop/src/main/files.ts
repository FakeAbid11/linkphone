import fs from 'node:fs'
import path from 'node:path'
import type { FileOfferMsg, TransferItem } from '../shared/protocol'

interface Sink {
  id: string
  name: string
  size: number
  deviceId: string
  fd: number | null
  finalPath: string
  partPath: string
  received: number
  aborted: boolean
}

function safeName(name: string): string {
  const base = path.basename(name.replace(/\\/g, '/'))
    .replace(/[\u0000-\u001f<>:"/\\|?*]/g, '_')
    .trim()
  return base.length > 0 ? base.slice(0, 180) : 'unnamed.bin'
}

function uniquePath(dir: string, name: string): string {
  const ext = path.extname(name)
  const stem = path.basename(name, ext)
  let candidate = path.join(dir, name)
  let counter = 1
  while (fs.existsSync(candidate) || fs.existsSync(`${candidate}.part`)) {
    candidate = path.join(dir, `${stem} (${counter})${ext}`)
    counter += 1
  }
  return candidate
}

/** Streams incoming files to ~/Downloads/LinkPhone, in order, with a hard size cap. */
export class FileReceiver {
  private sinks = new Map<string, Sink>()
  private lastReport = new Map<string, number>()

  constructor(
    private readonly dir: string,
    private readonly onUpdate: (patch: Partial<TransferItem> & { id: string }) => void,
    private readonly onAccept: (id: string, offset: number, deviceId: string) => void,
    private readonly onDecline: (id: string, reason: string, deviceId: string) => void
  ) {
    this.sweepPartials()
  }

  offer(msg: FileOfferMsg, deviceId: string): void {
    if (!msg.id || typeof msg.id !== 'string') return
    if (this.sinks.size >= 100) {
      this.onDecline(msg.id, 'busy', deviceId)
      return
    }
    if (!Number.isFinite(msg.size) || msg.size < 0 || msg.size > 8 * 1024 * 1024 * 1024) {
      this.onDecline(msg.id, 'size', deviceId)
      return
    }

    const name = safeName(msg.name)
    const existing = this.sinks.get(msg.id)
    if (existing && !existing.aborted) {
      const canResume = msg.resumeFrom !== undefined && existing.size === msg.size
      if (canResume) {
        if (existing.fd === null) {
          try {
            existing.fd = fs.openSync(existing.partPath, 'a')
          } catch {
            this.discard(existing)
            this.onDecline(msg.id, 'io', deviceId)
            return
          }
        }
        this.onUpdate({
          id: msg.id,
          status: 'active',
          received: existing.received,
          path: existing.finalPath
        })
        this.onAccept(msg.id, existing.received, deviceId)
        return
      }
      this.discard(existing)
    }

    const finalPath = uniquePath(this.dir, name)
    const partPath = `${finalPath}.part`
    let fd: number
    try {
      fd = fs.openSync(partPath, 'w')
    } catch {
      this.onDecline(msg.id, 'io', deviceId)
      return
    }

    this.sinks.set(msg.id, {
      id: msg.id,
      name: path.basename(finalPath),
      size: msg.size,
      deviceId,
      fd,
      finalPath,
      partPath,
      received: 0,
      aborted: false
    })

    this.onUpdate({
      id: msg.id,
      name: path.basename(finalPath),
      size: msg.size,
      received: 0,
      direction: 'in',
      status: 'active',
      path: finalPath,
      deviceId,
      startedAt: Date.now()
    })
    this.onAccept(msg.id, 0, deviceId)
  }

  write(id: string, offset: number, data: Buffer): void {
    const sink = this.sinks.get(id)
    if (!sink || sink.aborted || sink.fd === null) return
    if (offset !== sink.received) {
      // Out-of-order or duplicate chunk: drop, the sender will resend from the ack.
      return
    }
    if (offset + data.length > sink.size) {
      this.abort(id, 'size exceeded')
      return
    }
    try {
      fs.writeSync(sink.fd, data)
    } catch {
      this.abort(id, 'write failed')
      return
    }
    sink.received += data.length

    const reported = this.lastReport.get(id) ?? 0
    if (sink.received - reported >= 512 * 1024 || sink.received === sink.size) {
      this.lastReport.set(id, sink.received)
      this.onUpdate({ id, received: sink.received })
    }
  }

  finish(id: string): void {
    const sink = this.sinks.get(id)
    if (!sink) return
    if (sink.received !== sink.size) {
      this.interrupt(id, 'incomplete')
      return
    }
    this.close(sink)
    try {
      fs.renameSync(sink.partPath, sink.finalPath)
    } catch {
      this.sinks.delete(id)
      this.lastReport.delete(id)
      this.onUpdate({ id, status: 'failed', error: 'could not finalize file' })
      return
    }
    this.sinks.delete(id)
    this.lastReport.delete(id)
    this.onUpdate({
      id,
      received: sink.received,
      status: 'done',
      path: sink.finalPath,
      resumeOffset: 0
    })
  }

  /** Keeps the partial file so a retry can resume from the same offset. */
  interrupt(id: string, reason: string): void {
    const sink = this.sinks.get(id)
    if (!sink) return
    this.close(sink)
    this.onUpdate({ id, status: 'interrupted', error: reason })
  }

  abort(id: string, reason: string): void {
    const sink = this.sinks.get(id)
    if (!sink) return
    sink.aborted = true
    this.discard(sink)
    this.onUpdate({ id, status: 'failed', error: reason })
  }

  pauseAllForDevice(deviceId: string): void {
    for (const sink of [...this.sinks.values()]) {
      if (sink.deviceId === deviceId) this.interrupt(sink.id, 'connection lost')
    }
  }

  private discard(sink: Sink): void {
    this.close(sink)
    this.sinks.delete(sink.id)
    this.lastReport.delete(sink.id)
    try {
      fs.unlinkSync(sink.partPath)
    } catch {
      /* ignore */
    }
    try {
      fs.unlinkSync(sink.finalPath)
    } catch {
      /* ignore */
    }
  }

  private close(sink: Sink): void {
    if (sink.fd !== null) {
      try {
        fs.closeSync(sink.fd)
      } catch {
        /* ignore */
      }
      sink.fd = null
    }
  }

  private sweepPartials(): void {
    try {
      const cutoff = Date.now() - 7 * 24 * 60 * 60 * 1000
      for (const name of fs.readdirSync(this.dir)) {
        if (!name.endsWith('.part')) continue
        const filePath = path.join(this.dir, name)
        try {
          if (fs.statSync(filePath).mtimeMs < cutoff) fs.unlinkSync(filePath)
        } catch {
          /* ignore */
        }
      }
    } catch {
      /* ignore */
    }
  }
}

/** Reads a local file into 64 KiB chunks for outbound transfer. */
export class FileSource {
  readonly fd: number
  readonly size: number
  readonly mtimeMs: number
  offset = 0

  constructor(
    readonly path: string,
    readonly name: string
  ) {
    this.fd = fs.openSync(path, 'r')
    const stat = fs.fstatSync(this.fd)
    this.size = stat.size
    this.mtimeMs = stat.mtimeMs
  }

  seek(offset: number): void {
    this.offset = Math.max(0, Math.min(offset, this.size))
  }

  readChunk(max: number): Buffer {
    const remaining = this.size - this.offset
    if (remaining <= 0) return Buffer.alloc(0)
    const buf = Buffer.allocUnsafe(Math.min(max, remaining))
    const read = fs.readSync(this.fd, buf, 0, buf.length, this.offset)
    this.offset += read
    return buf.subarray(0, read)
  }

  close(): void {
    try {
      fs.closeSync(this.fd)
    } catch {
      /* ignore */
    }
  }
}

import fs from 'node:fs'
import path from 'node:path'
import type { FileOfferMsg, TransferItem } from '../shared/protocol'

interface Sink {
  id: string
  name: string
  size: number
  deviceId: string
  fd: number
  finalPath: string
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
  while (fs.existsSync(candidate)) {
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
    private readonly onAccept: (id: string) => void,
    private readonly onDecline: (id: string, reason: string) => void
  ) {}

  offer(msg: FileOfferMsg, deviceId: string): void {
    if (!msg.id || typeof msg.id !== 'string') return
    if (this.sinks.has(msg.id)) return
    if (!Number.isFinite(msg.size) || msg.size < 0 || msg.size > 8 * 1024 * 1024 * 1024) {
      this.onDecline(msg.id, 'size')
      return
    }

    const finalPath = uniquePath(this.dir, safeName(msg.name))
    let fd: number
    try {
      fd = fs.openSync(finalPath, 'w')
    } catch {
      this.onDecline(msg.id, 'io')
      return
    }

    this.sinks.set(msg.id, {
      id: msg.id,
      name: path.basename(finalPath),
      size: msg.size,
      deviceId,
      fd,
      finalPath,
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
    this.onAccept(msg.id)
  }

  write(id: string, offset: number, data: Buffer): void {
    const sink = this.sinks.get(id)
    if (!sink || sink.aborted) return
    if (offset !== sink.received) {
      // Out-of-order or duplicate chunk: drop, the sender will resend from the ack.
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
    this.close(sink)
    this.onUpdate({
      id,
      received: sink.received,
      status: sink.received >= sink.size ? 'done' : 'failed',
      path: sink.finalPath
    })
  }

  abort(id: string, reason: string): void {
    const sink = this.sinks.get(id)
    if (!sink) return
    sink.aborted = true
    this.close(sink)
    try {
      fs.unlinkSync(sink.finalPath)
    } catch {
      /* ignore */
    }
    this.onUpdate({ id, status: 'failed', error: reason })
  }

  cancelAllForDevice(deviceId: string): void {
    for (const sink of [...this.sinks.values()]) {
      if (sink.deviceId === deviceId) this.abort(sink.id, 'device disconnected')
    }
  }

  private close(sink: Sink): void {
    this.sinks.delete(sink.id)
    this.lastReport.delete(sink.id)
    try {
      fs.closeSync(sink.fd)
    } catch {
      /* ignore */
    }
  }
}

/** Reads a local file into 64 KiB chunks for outbound transfer. */
export class FileSource {
  readonly fd: number
  readonly size: number
  offset = 0

  constructor(
    readonly path: string,
    readonly name: string
  ) {
    this.fd = fs.openSync(path, 'r')
    this.size = fs.fstatSync(this.fd).size
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

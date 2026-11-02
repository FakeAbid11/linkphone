import { clipboard } from 'electron'

/**
 * Watches the PC clipboard and pushes new text to the phone.
 * Remote writes are suppressed so the two sides don't ping-pong forever.
 */
export class ClipboardWatcher {
  private last = ''
  private timer: NodeJS.Timeout | null = null
  private suppressUntil = 0

  constructor(
    private readonly onChange: (text: string) => void,
    private readonly isEnabled: () => boolean
  ) {}

  start(): void {
    if (this.timer) return
    this.last = clipboard.readText()
    this.timer = setInterval(() => this.tick(), 900)
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer)
    this.timer = null
  }

  private tick(): void {
    if (!this.isEnabled()) return
    let text = ''
    try {
      text = clipboard.readText()
    } catch {
      return
    }
    if (!text || text === this.last) return
    this.last = text
    if (Date.now() < this.suppressUntil) return
    if (text.length > 256 * 1024) return
    this.onChange(text)
  }

  applyRemote(text: string): void {
    this.suppressUntil = Date.now() + 1500
    this.last = text
    try {
      clipboard.writeText(text)
    } catch {
      /* ignore */
    }
  }
}

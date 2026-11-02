import { useState } from 'react'

interface Props {
  enabled: boolean
  onToggle: (enabled: boolean) => void
  onSend: (text: string) => void
}

export function ClipboardPanel({ enabled, onToggle, onSend }: Props): JSX.Element {
  const [draft, setDraft] = useState('')
  const [sent, setSent] = useState(false)

  const send = (): void => {
    if (draft.trim().length === 0) return
    onSend(draft)
    setSent(true)
    setTimeout(() => setSent(false), 1400)
  }

  return (
    <div className="clip">
      <div className="clip-toggle">
        <div>
          <strong>Sync clipboard automatically</strong>
          <p className="muted">
            Text copied on this PC is pushed to your phone and vice versa. The phone can only read
            its clipboard while the app is open (an Android restriction).
          </p>
        </div>
        <button
          className={`switch ${enabled ? 'switch-on' : ''}`}
          onClick={() => onToggle(!enabled)}
          aria-pressed={enabled}
        >
          <span />
        </button>
      </div>

      <label className="field-label" htmlFor="clip-text">
        Send text to your phone
      </label>
      <textarea
        id="clip-text"
        className="clip-area"
        value={draft}
        placeholder="Paste or type anything — a link, an address, a Wi-Fi password…"
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) send()
        }}
      />
      <div className="clip-actions">
        <button className="btn" onClick={send}>
          {sent ? 'Sent' : 'Send to phone'}
        </button>
        <span className="muted">Ctrl+Enter</span>
      </div>
    </div>
  )
}

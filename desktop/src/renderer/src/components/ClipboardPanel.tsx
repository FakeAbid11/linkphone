import { useState } from 'react'
import { Icon } from './Icon'

interface Props {
  enabled: boolean
  connected: boolean
  onToggle: (enabled: boolean) => void
  onSend: (text: string) => Promise<boolean>
}

export function ClipboardPanel({ enabled, connected, onToggle, onSend }: Props): JSX.Element {
  const [draft, setDraft] = useState('')
  const [status, setStatus] = useState<'idle' | 'sent' | 'offline'>('idle')

  const send = async (): Promise<void> => {
    if (draft.trim().length === 0) return
    if (!connected) {
      setStatus('offline')
      return
    }
    const sent = await onSend(draft)
    setStatus(sent ? 'sent' : 'offline')
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
          aria-label="Sync clipboard automatically"
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
        onChange={(e) => {
          setDraft(e.target.value)
          setStatus('idle')
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) void send()
        }}
      />
      <div className="clip-actions">
        <button
          className="btn"
          onClick={() => void send()}
          disabled={!connected || draft.trim().length === 0}
        >
          <Icon name={status === 'sent' ? 'check' : 'send'} size={18} />{' '}
          {status === 'sent' ? 'Sent' : status === 'offline' ? 'Not sent' : 'Send to phone'}
        </button>
        <span className="muted">
          {!connected
            ? 'Phone offline — connect to send'
            : status === 'offline'
              ? 'Not sent — phone offline'
              : 'Ctrl+Enter'}
        </span>
      </div>
    </div>
  )
}

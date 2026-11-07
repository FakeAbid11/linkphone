import { useEffect, useRef, useState } from 'react'
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
  const [pullState, setPullState] = useState<'idle' | 'pulling' | 'received' | 'error'>('idle')
  const [pullText, setPullText] = useState('')
  const pullTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    const off = window.linkphone.onClipboardStatus((result) => {
      if (pullTimer.current) {
        clearTimeout(pullTimer.current)
        pullTimer.current = null
      }
      if (result.kind === 'received') {
        setPullState('received')
        setPullText('Received from phone')
      } else {
        setPullState('error')
        setPullText(clipboardError(result.reason))
      }
    })
    return () => {
      off()
      if (pullTimer.current) {
        clearTimeout(pullTimer.current)
        pullTimer.current = null
      }
    }
  }, [])

  const pull = async (): Promise<void> => {
    setPullState('pulling')
    setPullText('Waiting for your phone…')
    const sent = await window.linkphone.requestClipboard()
    if (!sent) {
      setPullState('error')
      setPullText('Phone offline.')
      return
    }
    if (pullTimer.current) clearTimeout(pullTimer.current)
    pullTimer.current = setTimeout(() => {
      pullTimer.current = null
      setPullState('error')
      setPullText('No response — open LinkPhone or enable background sync.')
    }, 5000)
  }

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
            Text copied on this PC is pushed to your phone and vice versa. Phone→PC works while
            LinkPhone is open, or in the background when background clipboard sync is enabled on
            the phone.
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
        placeholder="Paste or type text"
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
        <button
          className="btn"
          onClick={() => void pull()}
          disabled={!connected || pullState === 'pulling'}
          title="Read the current clipboard from your phone"
        >
          <Icon name="download" size={18} />{' '}
          {pullState === 'pulling' ? 'Pulling…' : 'Pull from phone'}
        </button>
        <span className="muted">
          {!connected
            ? 'Phone offline — connect to send'
            : status === 'offline'
              ? 'Not sent — phone offline'
              : 'Ctrl+Enter'}
        </span>
      </div>
      {pullText && <p className={`clip-pull-status ${pullState === 'error' ? 'muted' : ''}`}>{pullText}</p>}
    </div>
  )
}

function clipboardError(reason?: string): string {
  switch (reason) {
    case 'offline':
      return 'Phone offline.'
    case 'empty':
      return 'The phone clipboard has no text.'
    case 'too-large':
      return 'That clip is too large (256 KB max).'
    case 'unavailable':
      return 'Phone couldn’t read its clipboard — open LinkPhone or enable background sync.'
    default:
      return 'Couldn’t read the phone clipboard.'
  }
}

import { useEffect, useRef, useState } from 'react'
import type { SmsActiveThread, SmsThread } from '@shared/protocol'

interface Props {
  threads: SmsThread[]
  active: SmsActiveThread | null
  loading: boolean
  onRefresh: () => void
  onOpen: (threadId: string, address: string) => void
  onClose: () => void
  onSend: (threadId: string, address: string, body: string) => void
}

function time(ts: number): string {
  if (!ts) return ''
  return new Date(ts).toLocaleString([], {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

export function MessagesPanel({
  threads,
  active,
  loading,
  onRefresh,
  onOpen,
  onClose,
  onSend
}: Props): JSX.Element {
  const [draft, setDraft] = useState('')
  const endRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: 'end' })
  }, [active?.messages.length])

  if (active) {
    const send = (): void => {
      const body = draft.trim()
      if (!body) return
      onSend(active.threadId, active.address, body)
      setDraft('')
    }

    return (
      <div className="sms-thread">
        <div className="sms-thread-head">
          <button className="link-btn" onClick={onClose}>
            ← All conversations
          </button>
          <div className="sms-peer">{active.address}</div>
          <button
            className="link-btn"
            onClick={() => onOpen(active.threadId, active.address)}
          >
            Refresh
          </button>
        </div>

        <div className="sms-messages">
          {active.messages.length === 0 && <p className="muted">Loading messages…</p>}
          {active.messages.map((m) => (
            <div key={m.id} className={`bubble-row ${m.outgoing ? 'out' : 'in'}`}>
              <div className={`bubble ${m.pending ? 'bubble-pending' : ''} ${m.failed ? 'bubble-failed' : ''}`}>
                <div className="bubble-text">{m.body}</div>
                <div className="bubble-time">
                  {time(m.date)}
                  {m.pending ? ' · sending' : ''}
                  {m.failed ? ' · failed' : ''}
                </div>
              </div>
            </div>
          ))}
          <div ref={endRef} />
        </div>

        <div className="sms-compose">
          <input
            autoFocus
            value={draft}
            placeholder={`Message ${active.address}`}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') send()
            }}
          />
          <button className="btn" onClick={send} disabled={draft.trim().length === 0}>
            Send
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="sms">
      <div className="files-head">
        <h2>Conversations</h2>
        <button className="link-btn" onClick={onRefresh} disabled={loading}>
          {loading ? 'Loading…' : 'Refresh'}
        </button>
      </div>

      {threads.length === 0 ? (
        <div className="empty">
          <div className="empty-art">💬</div>
          <h2>No messages yet</h2>
          <p className="muted">
            LinkPhone needs SMS permission on the phone to read your texts. Open the app, grant the
            permission it asks for, then hit Refresh.
          </p>
        </div>
      ) : (
        <ul className="sms-list">
          {threads.map((thread) => (
            <li key={thread.threadId}>
              <button
                className="sms-row"
                onClick={() => onOpen(thread.threadId, thread.address)}
              >
                <div className="sms-avatar">{(thread.address || '?').slice(-2)}</div>
                <div className="sms-row-body">
                  <div className="sms-row-top">
                    <span className="sms-address">{thread.address || 'Unknown'}</span>
                    <span className="muted sms-time">{time(thread.date)}</span>
                  </div>
                  <div className="sms-snippet">{thread.snippet}</div>
                </div>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

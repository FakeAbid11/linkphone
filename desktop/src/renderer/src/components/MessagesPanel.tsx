import { useEffect, useRef, useState } from 'react'
import type { SimInfo, SmsActiveThread, SmsThread } from '@shared/protocol'
import { Icon } from './Icon'

interface Props {
  threads: SmsThread[]
  active: SmsActiveThread | null
  loading: boolean
  sims: SimInfo[]
  onRefresh: () => void
  onOpen: (threadId: string, address: string) => void
  onClose: () => void
  onSend: (threadId: string, address: string, body: string, subscriptionId?: number) => void
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
  sims,
  onRefresh,
  onOpen,
  onClose,
  onSend
}: Props): JSX.Element {
  const [draft, setDraft] = useState('')
  const [simId, setSimId] = useState('')
  const endRef = useRef<HTMLDivElement | null>(null)
  const defaultSimId = sims.find((sim) => sim.isDefault)?.id ?? sims[0]?.id ?? ''

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: 'end' })
  }, [active?.messages.length])

  useEffect(() => {
    if (!active) return
    const newest = [...active.messages]
      .reverse()
      .find((message) => message.subscriptionId !== undefined)
    setSimId(
      newest?.subscriptionId !== undefined ? String(newest.subscriptionId) : defaultSimId
    )
  }, [active?.threadId, defaultSimId])

  useEffect(() => {
    if (simId && !sims.some((sim) => sim.id === simId)) setSimId(defaultSimId)
  }, [sims, simId, defaultSimId])

  if (active) {
    const send = (): void => {
      const body = draft.trim()
      if (!body) return
      onSend(
        active.threadId,
        active.address,
        body,
        simId ? Number(simId) : undefined
      )
      setDraft('')
    }

    return (
      <div className="sms-thread">
        <div className="sms-thread-head">
          <button className="link-btn" onClick={onClose}>
            <Icon name="arrow_back" size={18} /> All conversations
          </button>
          <div className="sms-peer">{active.name || active.address}</div>
          <button
            className="link-btn"
            onClick={() => onOpen(active.threadId, active.address)}
          >
            <Icon name="refresh" size={18} /> Refresh
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
                  {m.simLabel ? ` · ${m.simLabel}` : ''}
                </div>
              </div>
            </div>
          ))}
          <div ref={endRef} />
        </div>

        <div className="sms-compose">
          {sims.length > 1 && (
            <select
              className="sim-select"
              value={simId}
              title="Send using"
              onChange={(e) => setSimId(e.target.value)}
            >
              <option value="">Phone default</option>
              {sims.map((sim) => (
                <option key={sim.id} value={sim.id}>
                  {sim.label}
                  {sim.isDefault ? ' (default)' : ''}
                </option>
              ))}
            </select>
          )}
          <input
            autoFocus
            value={draft}
            placeholder={`Message ${active.name || active.address}`}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') send()
            }}
          />
          <button className="btn" onClick={send} disabled={draft.trim().length === 0}>
            <Icon name="send" size={18} /> Send
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
          {loading ? (
            'Loading…'
          ) : (
            <>
              <Icon name="refresh" size={18} /> Refresh
            </>
          )}
        </button>
      </div>

      {threads.length === 0 ? (
        <div className="empty">
          <div className="empty-art"><Icon name="chat" size={34} /></div>
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
                <div className="sms-avatar">
                  {thread.name
                    ? thread.name.slice(0, 2).toUpperCase()
                    : (thread.address || '?').slice(-2)}
                </div>
                <div className="sms-row-body">
                  <div className="sms-row-top">
                    <span className="sms-address">
                      {thread.name || thread.address || 'Unknown'}
                    </span>
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

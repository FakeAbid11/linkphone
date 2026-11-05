import { useEffect, useRef, useState } from 'react'
import type { SimInfo, SmsActiveThread, SmsThread } from '@shared/protocol'
import { Icon } from './Icon'

type SmsResult = 'queued' | 'no-peer' | 'invalid'

interface Props {
  threads: SmsThread[]
  active: SmsActiveThread | null
  loading: boolean
  sims: SimInfo[]
  connected: boolean
  onRefresh: () => void
  onOpen: (threadId: string, address: string) => void
  onClose: () => void
  onSend: (
    threadId: string,
    address: string,
    body: string,
    subscriptionId?: number
  ) => Promise<SmsResult>
  onStartConversation: (
    address: string,
    body: string,
    subscriptionId?: number
  ) => Promise<SmsResult>
  onRetry: (id: string) => void
}

/** Drafts survive the panel unmounting on tab switches; keys are thread ids. */
const drafts = new Map<string, string>()

function rememberDraft(threadId: string, value: string): void {
  if (!value) {
    drafts.delete(threadId)
    return
  }
  if (!drafts.has(threadId) && drafts.size >= 20) {
    const oldest = drafts.keys().next().value
    if (oldest !== undefined) drafts.delete(oldest)
  }
  drafts.set(threadId, value)
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
  connected,
  onRefresh,
  onOpen,
  onClose,
  onSend,
  onStartConversation,
  onRetry
}: Props): JSX.Element {
  const [draft, setDraft] = useState('')
  const [simId, setSimId] = useState('')
  const [query, setQuery] = useState('')
  const [threadQuery, setThreadQuery] = useState('')
  const [composing, setComposing] = useState(false)
  const [to, setTo] = useState('')
  const [newBody, setNewBody] = useState('')
  const [newSimId, setNewSimId] = useState('')
  const [sending, setSending] = useState(false)
  const [sendError, setSendError] = useState('')
  const [composeSending, setComposeSending] = useState(false)
  const [composeError, setComposeError] = useState('')
  const [retryingId, setRetryingId] = useState<string | null>(null)
  const endRef = useRef<HTMLDivElement | null>(null)
  const defaultSimId = sims.find((sim) => sim.isDefault)?.id ?? sims[0]?.id ?? ''

  useEffect(() => {
    if (!composing || newSimId) return
    setNewSimId(defaultSimId)
  }, [composing, newSimId, defaultSimId])

  useEffect(() => {
    setThreadQuery('')
    setSendError('')
    if (active) setDraft(drafts.get(active.threadId) ?? '')
  }, [active?.threadId])

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
    const visibleMessages = threadQuery.trim()
      ? active.messages.filter((message) =>
          message.body.toLowerCase().includes(threadQuery.trim().toLowerCase())
        )
      : active.messages

    const send = async (): Promise<void> => {
      const body = draft.trim()
      if (!body || sending) return
      setSending(true)
      setSendError('')
      let result: SmsResult
      try {
        result = await onSend(
          active.threadId,
          active.address,
          body,
          simId ? Number(simId) : undefined
        )
      } catch {
        result = 'invalid'
      }
      setSending(false)
      if (result === 'queued') {
        setDraft('')
        rememberDraft(active.threadId, '')
      } else {
        setSendError(
          result === 'no-peer'
            ? 'Your phone is disconnected — your message is still here.'
            : 'Could not send that message.'
        )
      }
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

        <div className="contacts-search sms-search">
          <Icon name="search" size={18} />
          <input
            value={threadQuery}
            placeholder="Search this conversation"
            aria-label="Search this conversation"
            onChange={(event) => setThreadQuery(event.target.value.slice(0, 80))}
          />
        </div>

        <div className="sms-messages">
          {active.messages.length === 0 && <p className="muted">Loading messages…</p>}
          {active.messages.length > 0 && visibleMessages.length === 0 && (
            <p className="muted">No messages match.</p>
          )}
          {visibleMessages.map((m) => (
            <div key={m.id} className={`bubble-row ${m.outgoing ? 'out' : 'in'}`}>
              <div className={`bubble ${m.pending ? 'bubble-pending' : ''} ${m.failed ? 'bubble-failed' : ''}`}>
                <div className="bubble-text">{m.body}</div>
                <div className="bubble-time">
                  {time(m.date)}
                  {m.pending ? ' · sending' : ''}
                  {m.failed ? ' · failed' : ''}
                  {m.failed && !m.retried && (
                    <>
                      {' · '}
                      <button
                        type="button"
                        className="bubble-retry"
                        disabled={!connected || retryingId !== null}
                        title={connected ? 'Send this message again' : 'Connect your phone to retry'}
                        onClick={() => {
                          setRetryingId(m.id)
                          onRetry(m.id)
                          window.setTimeout(() => setRetryingId(null), 2000)
                        }}
                      >
                        {retryingId === m.id ? 'Retrying…' : 'Retry'}
                      </button>
                    </>
                  )}
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
            disabled={sending}
            placeholder={`Message ${active.name || active.address}`}
            onChange={(e) => {
              setDraft(e.target.value)
              rememberDraft(active.threadId, e.target.value)
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.nativeEvent.isComposing) void send()
            }}
          />
          <button
            className="btn"
            onClick={() => void send()}
            disabled={draft.trim().length === 0 || sending}
          >
            <Icon name="send" size={18} /> {sending ? 'Sending…' : 'Send'}
          </button>
        </div>
        {sendError && (
          <p className="sms-send-error" role="status">
            {sendError}
          </p>
        )}
      </div>
    )
  }

  const filteredThreads = query.trim()
    ? threads.filter((thread) => {
        const needle = query.trim().toLowerCase()
        return `${thread.name ?? ''} ${thread.address} ${thread.snippet}`
          .toLowerCase()
          .includes(needle)
      })
    : threads

  const startConversation = async (): Promise<void> => {
    if (composeSending) return
    setComposeSending(true)
    setComposeError('')
    let result: SmsResult
    try {
      result = await onStartConversation(
        to.trim(),
        newBody.trim(),
        newSimId ? Number(newSimId) : undefined
      )
    } catch {
      result = 'invalid'
    }
    setComposeSending(false)
    if (result === 'queued') {
      setComposing(false)
      setTo('')
      setNewBody('')
      setNewSimId(defaultSimId)
      return
    }
    setComposeError(
      result === 'invalid'
        ? 'That phone number doesn’t look valid.'
        : 'Your phone is disconnected — try again.'
    )
  }

  return (
    <div className="sms">
      <div className="files-head">
        <h2>Conversations</h2>
        <div className="sms-head-actions">
          <button
            className="link-btn"
            onClick={() => {
              setComposing(true)
              setTo('')
              setNewBody('')
              setNewSimId(defaultSimId)
              setComposeError('')
            }}
          >
            <Icon name="chat" size={18} /> New message
          </button>
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
      </div>

      {composing ? (
        <div className="sms-new">
          <label htmlFor="sms-to">To</label>
          <input
            id="sms-to"
            autoFocus
            value={to}
            disabled={composeSending}
            placeholder="Phone number"
            onChange={(event) => setTo(event.target.value.slice(0, 64))}
          />
          {sims.length > 1 && (
            <select
              className="sim-select"
              value={newSimId}
              title="Send using"
              onChange={(event) => setNewSimId(event.target.value)}
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
          <textarea
            value={newBody}
            placeholder="Message"
            rows={4}
            disabled={composeSending}
            onChange={(event) => setNewBody(event.target.value.slice(0, 2000))}
          />
          <div className="sms-new-actions">
            <button
              className="btn"
              disabled={to.trim().length < 2 || newBody.trim().length === 0 || composeSending}
              onClick={() => void startConversation()}
            >
              <Icon name="send" size={18} /> {composeSending ? 'Sending…' : 'Send'}
            </button>
            <button className="btn btn-ghost" onClick={() => setComposing(false)}>
              Cancel
            </button>
          </div>
          {composeError && (
            <p className="sms-send-error" role="status">
              {composeError}
            </p>
          )}
        </div>
      ) : threads.length === 0 ? (
        <div className="empty">
          <div className="empty-art"><Icon name="chat" size={34} /></div>
          <h2>No messages yet</h2>
          <p className="muted">
            LinkPhone needs SMS permission on the phone to read your texts. Open the app, grant the
            permission it asks for, then hit Refresh.
          </p>
        </div>
      ) : (
        <>
          <div className="contacts-search sms-search">
            <Icon name="search" size={18} />
            <input
              value={query}
              placeholder="Search conversations"
              aria-label="Search conversations"
              onChange={(event) => setQuery(event.target.value.slice(0, 80))}
            />
          </div>
          {filteredThreads.length === 0 ? (
            <p className="muted recent-empty">No conversations match.</p>
          ) : (
            <ul className="sms-list">
              {filteredThreads.map((thread) => (
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
        </>
      )}
    </div>
  )
}

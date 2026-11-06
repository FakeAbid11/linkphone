import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent
} from 'react'
import type { PhoneContact, SimInfo, SmsActiveThread, SmsThread } from '@shared/protocol'
import { Icon } from './Icon'
import { digitsOf, initials, normalizeNumber, numbersMatch } from '../lib/phone'

type SmsResult = 'queued' | 'no-peer' | 'invalid'

interface Suggestion {
  key: string
  name: string
  number: string
  label: string
}

const MAX_SUGGESTIONS = 8

interface Props {
  threads: SmsThread[]
  active: SmsActiveThread | null
  loading: boolean
  sims: SimInfo[]
  connected: boolean
  contacts: PhoneContact[]
  contactsGranted: boolean
  contactsLoading: boolean
  prefill: { address: string; nonce: number } | null
  onRequestContacts: () => void
  onPrefillUsed: () => void
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
  contacts,
  contactsGranted,
  contactsLoading,
  prefill,
  onRequestContacts,
  onPrefillUsed,
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
  const [suggestOpen, setSuggestOpen] = useState(false)
  const [suggestIndex, setSuggestIndex] = useState(-1)
  const endRef = useRef<HTMLDivElement | null>(null)
  const toRef = useRef<HTMLInputElement | null>(null)
  const bodyRef = useRef<HTMLTextAreaElement | null>(null)
  const pickerRef = useRef<HTMLDivElement | null>(null)
  const optionRefs = useRef<Array<HTMLLIElement | null>>([])
  const lastPrefill = useRef(0)
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

  const suggestions = useMemo<Suggestion[]>(() => {
    const needle = to.trim().toLowerCase()
    if (!needle || contacts.length === 0) return []
    const digits = digitsOf(needle)
    const seenNumbers = new Set<string>()
    const scored: Array<{ row: Suggestion; rank: number }> = []
    for (const contact of contacts) {
      const name = contact.name.toLowerCase()
      const nameRank = name.startsWith(needle) ? 0 : name.includes(needle) ? 1 : -1
      for (const entry of contact.numbers) {
        const numberKey = normalizeNumber(entry.number) ?? entry.number
        if (seenNumbers.has(numberKey)) continue
        const numberRank = digits.length >= 2 && digitsOf(entry.number).includes(digits) ? 2 : -1
        const rank = nameRank >= 0 ? nameRank : numberRank
        if (rank < 0) continue
        seenNumbers.add(numberKey)
        scored.push({
          row: {
            key: `${contact.id}:${entry.number}`,
            name: contact.name,
            number: entry.number,
            label: entry.label
          },
          rank
        })
      }
    }
    scored.sort((a, b) => a.rank - b.rank || a.row.name.localeCompare(b.row.name))
    return scored.slice(0, MAX_SUGGESTIONS).map((item) => item.row)
  }, [contacts, to])

  const showSuggestions = suggestOpen && suggestions.length > 0

  const recipient = useMemo(() => {
    const value = to.trim()
    if (!value) return null
    for (const contact of contacts) {
      const entry = contact.numbers.find((item) => numbersMatch(item.number, value))
      if (entry) return { name: contact.name, label: entry.label }
    }
    return null
  }, [contacts, to])

  const hint = !connected
    ? contacts.length > 0
      ? 'Phone offline — showing the last synced contacts.'
      : 'Phone offline — type a number to send when it reconnects.'
    : !contactsGranted
      ? 'Contact suggestions are off — allow contact access on your phone, or type a number.'
      : contactsLoading && contacts.length === 0
        ? 'Loading contacts…'
        : contacts.length === 0
          ? 'No contacts on your phone yet.'
          : to.trim().length >= 2 && suggestions.length === 0
            ? 'No contacts match — you can still type a number.'
            : ''

  useEffect(() => {
    if (suggestIndex >= suggestions.length) setSuggestIndex(-1)
  }, [suggestions.length, suggestIndex])

  useEffect(() => {
    if (!showSuggestions) return
    const close = (event: PointerEvent): void => {
      if (!pickerRef.current?.contains(event.target as Node)) setSuggestOpen(false)
    }
    document.addEventListener('pointerdown', close)
    return () => document.removeEventListener('pointerdown', close)
  }, [showSuggestions])

  useEffect(() => {
    if (suggestIndex >= 0) optionRefs.current[suggestIndex]?.scrollIntoView({ block: 'nearest' })
  }, [suggestIndex])

  useEffect(() => {
    if (composing) toRef.current?.focus()
  }, [composing])

  useEffect(() => {
    if (!prefill || prefill.nonce === lastPrefill.current) return
    lastPrefill.current = prefill.nonce
    onClose()
    setComposing(true)
    setTo(prefill.address)
    setNewBody('')
    setComposeError('')
    setSuggestOpen(false)
    setSuggestIndex(-1)
    setNewSimId(defaultSimId)
    setTimeout(() => bodyRef.current?.focus(), 0)
    onPrefillUsed()
  }, [prefill, defaultSimId, onClose, onPrefillUsed])

  const chooseSuggestion = (row: Suggestion): void => {
    setTo(row.number)
    setSuggestOpen(false)
    setSuggestIndex(-1)
    setTimeout(() => bodyRef.current?.focus(), 0)
  }

  const changeTo = (value: string): void => {
    setTo(value)
    setSuggestIndex(-1)
    setSuggestOpen(value.trim().length > 0)
  }

  const toKeyDown = (event: ReactKeyboardEvent<HTMLInputElement>): void => {
    if (event.key === 'Escape') {
      if (!showSuggestions) return
      event.preventDefault()
      setSuggestOpen(false)
      setSuggestIndex(-1)
      return
    }
    if (event.key === 'Tab') {
      setSuggestOpen(false)
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      if (suggestions.length === 0) return
      event.preventDefault()
      setSuggestOpen(true)
      setSuggestIndex((current) => {
        const size = suggestions.length
        if (event.key === 'ArrowDown') return current < 0 ? 0 : (current + 1) % size
        return current <= 0 ? size - 1 : current - 1
      })
      return
    }
    if (event.key === 'Enter' && showSuggestions) {
      event.preventDefault()
      chooseSuggestion(suggestions[suggestIndex >= 0 ? suggestIndex : 0])
    }
  }

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
      setSuggestOpen(false)
      setSuggestIndex(-1)
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
              setSuggestOpen(false)
              setSuggestIndex(-1)
              onRequestContacts()
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
          <div className="sms-picker" ref={pickerRef}>
            <input
              id="sms-to"
              ref={toRef}
              value={to}
              disabled={composeSending}
              placeholder="Name or phone number"
              role="combobox"
              aria-expanded={showSuggestions}
              aria-controls="sms-to-suggestions"
              aria-autocomplete="list"
              aria-activedescendant={
                showSuggestions && suggestIndex >= 0 ? `sms-to-option-${suggestIndex}` : undefined
              }
              aria-describedby="sms-to-hint"
              onChange={(event) => changeTo(event.target.value.slice(0, 64))}
              onFocus={() => {
                if (to.trim() && suggestions.length > 0) setSuggestOpen(true)
              }}
              onKeyDown={toKeyDown}
            />
            {showSuggestions && (
              <ul
                className="sms-suggest-list"
                id="sms-to-suggestions"
                role="listbox"
                aria-label="Contact suggestions"
              >
                {suggestions.map((row, index) => (
                  <li
                    key={row.key}
                    id={`sms-to-option-${index}`}
                    role="option"
                    aria-selected={index === suggestIndex}
                    ref={(element) => {
                      optionRefs.current[index] = element
                    }}
                    className="sms-suggest-option"
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => chooseSuggestion(row)}
                  >
                    <span className="sms-avatar">{initials(row.name)}</span>
                    <span className="sms-suggest-text">
                      <span className="sms-suggest-name">{row.name}</span>
                      <span className="sms-suggest-number">{row.number}</span>
                    </span>
                    <span className="contact-label">{row.label}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
          {recipient && !showSuggestions && (
            <p className="sms-recipient">
              <span className="sms-avatar">{initials(recipient.name)}</span>
              <span>
                {recipient.name} · {recipient.label}
              </span>
            </p>
          )}
          <p
            className={hint ? 'sms-to-hint' : 'sms-to-hint sms-to-hint-empty'}
            id="sms-to-hint"
            aria-live="polite"
          >
            {hint}
          </p>
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
            ref={bodyRef}
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

import { useEffect, useMemo, useState } from 'react'
import type { PhoneContact, SmsThread } from '@shared/protocol'
import { Icon } from './Icon'
import { initials, normalizeNumber } from '../lib/phone'

interface Props {
  contacts: PhoneContact[]
  threads: SmsThread[]
  granted: boolean
  loading: boolean
  threadsLoading: boolean
  connected: boolean
  dialerReady: boolean
  onRefresh: () => void
  onCall: (number: string) => void
  onMessage: (threadId: string, address: string) => void
}

const MAX_ROWS = 200

export function ContactsPanel({
  contacts,
  threads,
  granted,
  loading,
  threadsLoading,
  connected,
  dialerReady,
  onRefresh,
  onCall,
  onMessage
}: Props): JSX.Element {
  const [query, setQuery] = useState('')
  const [notice, setNotice] = useState('')
  const [slow, setSlow] = useState(false)

  useEffect(() => {
    setNotice('')
  }, [contacts])

  useEffect(() => {
    if (!loading) {
      setSlow(false)
      return
    }
    const timer = setTimeout(() => setSlow(true), 8000)
    return () => clearTimeout(timer)
  }, [loading])

  const threadByNumber = useMemo(() => {
    const map = new Map<string, SmsThread>()
    for (const thread of threads) {
      const key = normalizeNumber(thread.address)
      if (key && !map.has(key)) map.set(key, thread)
    }
    return map
  }, [threads])

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase()
    if (!needle) return contacts
    const digits = needle.replace(/\D/g, '')
    return contacts.filter((contact) => {
      if (contact.name.toLowerCase().includes(needle)) return true
      if (!digits) return false
      return contact.numbers.some((number) => number.number.replace(/\D/g, '').includes(digits))
    })
  }, [contacts, query])

  const visible = filtered.slice(0, MAX_ROWS)

  const message = (number: string): void => {
    const thread = threadByNumber.get(normalizeNumber(number) ?? '')
    if (!thread) {
      setNotice(
        threadsLoading
          ? 'Messages are still loading — try again in a moment.'
          : `No SMS thread yet for ${number} — start one on the phone.`
      )
      return
    }
    setNotice('')
    onMessage(thread.threadId, thread.address)
  }

  return (
    <div className="contacts">
      <div className="files-head">
        <h2>Contacts</h2>
        <button className="link-btn" onClick={onRefresh} disabled={!connected}>
          {loading ? (
            'Loading…'
          ) : (
            <>
              <Icon name="refresh" size={18} /> Refresh
            </>
          )}
        </button>
      </div>

      {!connected ? (
        <div className="empty">
          <div className="empty-art"><Icon name="contacts" size={34} /></div>
          <h2>Phone not connected</h2>
          <p className="muted">Connect your phone on the same Wi-Fi to browse its contacts.</p>
        </div>
      ) : !granted && !loading ? (
        <div className="empty">
          <div className="empty-art"><Icon name="contacts" size={34} /></div>
          <h2>Contacts are off</h2>
          <p className="muted">
            Open LinkPhone on your phone and allow contact access. The list stays on this PC and the
            encrypted local link.
          </p>
        </div>
      ) : loading && contacts.length === 0 ? (
        <div className="empty">
          <div className="empty-art"><Icon name="contacts" size={34} /></div>
          <h2>Loading contacts…</h2>
          <p className="muted">
            {slow
              ? 'The phone did not answer. Update LinkPhone on Android if this keeps happening.'
              : 'Waiting for the phone to send its contact list.'}
          </p>
        </div>
      ) : contacts.length === 0 ? (
        <div className="empty">
          <div className="empty-art"><Icon name="contacts" size={34} /></div>
          <h2>No contacts yet</h2>
          <p className="muted">Add contacts on your phone, then refresh.</p>
        </div>
      ) : (
        <>
          <div className="contacts-search">
            <Icon name="search" size={18} />
            <input
              value={query}
              placeholder="Search name or number"
              onChange={(event) => setQuery(event.target.value.slice(0, 60))}
            />
          </div>

          {slow && (
            <p className="muted contacts-note">
              The phone did not answer. Update LinkPhone on Android if this keeps happening.
            </p>
          )}
          {notice && <div className="contacts-notice">{notice}</div>}
          {!dialerReady && (
            <div className="call-warning">
              Set LinkPhone as the default phone app on your phone to call from here.
            </div>
          )}

          <ul className="contact-list">
            {visible.map((contact) => (
              <li key={contact.id} className="contact-row">
                <div className="sms-avatar">{initials(contact.name)}</div>
                <div className="contact-body">
                  <div className="contact-name">{contact.name}</div>
                  {contact.numbers.map((number) => {
                    const thread = threadByNumber.get(normalizeNumber(number.number) ?? '')
                    return (
                      <div key={number.number} className="contact-number">
                        <div className="contact-number-text">
                          <span>{number.number}</span>
                          <span className="muted contact-label">{number.label}</span>
                        </div>
                        <div className="contact-actions">
                          <button
                            className="icon-btn"
                            title={dialerReady ? `Call ${number.number}` : 'Calling needs the default phone app'}
                            disabled={!dialerReady}
                            onClick={() => onCall(number.number)}
                          >
                            <Icon name="call" size={18} />
                          </button>
                          <button
                            className="icon-btn"
                            title={thread ? 'Open SMS thread' : 'No SMS thread yet'}
                            onClick={() => message(number.number)}
                          >
                            <Icon name="chat" size={18} />
                          </button>
                        </div>
                      </div>
                    )
                  })}
                </div>
              </li>
            ))}
          </ul>

          {filtered.length > MAX_ROWS && (
            <p className="muted contacts-note">
              Showing {MAX_ROWS} of {filtered.length} matches — refine your search.
            </p>
          )}
        </>
      )}
    </div>
  )
}

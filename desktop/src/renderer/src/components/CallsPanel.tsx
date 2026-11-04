import { useEffect, useState } from 'react'
import type {
  CallAccountInfo,
  CallLogEntry,
  PhoneCallInfo,
  PhoneContact,
  SmsThread
} from '@shared/protocol'
import { Icon } from './Icon'
import { ContactsPanel } from './ContactsPanel'
import { callDuration, relativeTime } from '../lib/phone'

interface Props {
  calls: PhoneCallInfo[]
  outgoingReady: boolean
  controlReady: boolean
  callPlaced: { number: string; at: number } | null
  speaker: boolean
  error: string
  connected: boolean
  history: CallLogEntry[]
  historyGranted: boolean
  historyLoading: boolean
  contacts: PhoneContact[]
  threads: SmsThread[]
  contactsGranted: boolean
  contactsLoading: boolean
  smsLoading: boolean
  callAccounts: CallAccountInfo[]
  onPlace: (number: string, accountId?: string) => void
  onAnswer: (callId: string) => void
  onReject: (callId: string) => void
  onDisconnect: (callId: string) => void
  onSpeaker: (enabled: boolean) => void
  onRefreshHistory: () => void
  onRefreshContacts: () => void
  onCall: (number: string, accountId?: string) => void
  onMessage: (threadId: string, address: string) => void
  onDismissPlaced: () => void
}

const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#']

export function CallsPanel({
  calls,
  outgoingReady,
  controlReady,
  callPlaced,
  speaker,
  error,
  connected,
  history,
  historyGranted,
  historyLoading,
  contacts,
  threads,
  contactsGranted,
  contactsLoading,
  smsLoading,
  callAccounts,
  onPlace,
  onAnswer,
  onReject,
  onDisconnect,
  onSpeaker,
  onRefreshHistory,
  onRefreshContacts,
  onCall,
  onMessage,
  onDismissPlaced
}: Props): JSX.Element {
  const [number, setNumber] = useState('')
  const [accountId, setAccountId] = useState('')
  const [sideView, setSideView] = useState<'recent' | 'contacts'>('recent')
  const [now, setNow] = useState(Date.now())

  useEffect(() => {
    const fallback = callAccounts.find((account) => account.isDefault)?.id
      ?? callAccounts[0]?.id
      ?? ''
    setAccountId((current) =>
      current && callAccounts.some((account) => account.id === current) ? current : fallback
    )
  }, [callAccounts])

  useEffect(() => {
    if (!calls.some((call) => call.phase === 'active')) return
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [calls])

  const place = (): void => {
    const value = number.trim()
    if (!value) return
    onPlace(value, accountId || undefined)
  }

  return (
    <div className="calls-panel">
      <div className="call-audio-note">
        <strong>Call audio stays on your phone</strong>
        <span>Windows controls the call only. Use the phone's speaker below when call control is enabled.</span>
      </div>

      {!outgoingReady && (
        <div className="call-warning">
          Allow call access on your phone to dial from the PC.
        </div>
      )}

      {calls.length > 0 && !controlReady && (
        <div className="call-warning">
          Allow call access on your phone to answer, decline, hang up or use the speaker.
        </div>
      )}

      {callPlaced && calls.length === 0 && (
        <div className="call-placed">
          <Icon name="call" size={18} />
          <div className="call-note-body">
            <strong>Call sent to your phone</strong>
            <span>{callPlaced.number} · finish or cancel it on the phone.</span>
          </div>
          <button
            className="icon-btn"
            type="button"
            title="Dismiss"
            onClick={onDismissPlaced}
          >
            <Icon name="close" size={16} />
          </button>
        </div>
      )}

      {error && <div className="call-error">{error}</div>}

      <div className="calls-grid">
        <div className="calls-left">
          {calls.length > 0 ? (
            <div className="call-stack">
              {calls.map((call) => (
                <CallCard
                  key={call.id}
                  call={call}
                  now={now}
                  speaker={speaker}
                  controlReady={controlReady}
                  onAnswer={onAnswer}
                  onReject={onReject}
                  onDisconnect={onDisconnect}
                  onSpeaker={onSpeaker}
                />
              ))}
            </div>
          ) : (
            <div className="dialer-card">
              {callAccounts.length > 1 && (
                <div className="sim-row">
                  <label htmlFor="call-account">Call using</label>
                  <select
                    id="call-account"
                    className="sim-select"
                    value={accountId}
                    onChange={(event) => setAccountId(event.target.value)}
                  >
                    <option value="">Phone default</option>
                    {callAccounts.map((account) => (
                      <option key={account.id} value={account.id}>
                        {account.label}
                        {account.isDefault ? ' (default)' : ''}
                      </option>
                    ))}
                  </select>
                </div>
              )}
              <label htmlFor="dial-number">Phone number</label>
              <div className="dial-input-row">
                <input
                  id="dial-number"
                  value={number}
                  placeholder="Enter a number"
                  onChange={(event) => setNumber(event.target.value.slice(0, 64))}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter') place()
                  }}
                />
                <button
                  className="icon-btn dial-backspace"
                  title="Delete last digit"
                  onClick={() => setNumber((value) => value.slice(0, -1))}
                >
                  <Icon name="backspace" />
                </button>
              </div>
              <div className="dial-pad">
                {KEYS.map((key) => (
                  <button key={key} onClick={() => setNumber((value) => `${value}${key}`.slice(0, 64))}>
                    {key}
                  </button>
                ))}
              </div>
              <button className="call-primary" disabled={!outgoingReady || !number.trim()} onClick={place}>
                <Icon name="call" size={18} /> Call
              </button>
            </div>
          )}
        </div>

        <div className="calls-side">
          <div className="calls-side-switch" role="tablist" aria-label="Recent calls or contacts">
            <button
              type="button"
              role="tab"
              aria-selected={sideView === 'recent'}
              className={sideView === 'recent' ? 'active' : ''}
              onClick={() => setSideView('recent')}
            >
              Recent
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={sideView === 'contacts'}
              className={sideView === 'contacts' ? 'active' : ''}
              onClick={() => setSideView('contacts')}
            >
              Contacts
            </button>
          </div>

          {sideView === 'recent' ? (
            <div className="recent-calls">
              <div className="files-head">
                <h2>Recent calls</h2>
                <button
                  className="link-btn"
                  onClick={onRefreshHistory}
                  disabled={!connected}
                >
                  {historyLoading ? (
                    'Loading…'
                  ) : (
                    <>
                      <Icon name="refresh" size={18} /> Refresh
                    </>
                  )}
                </button>
              </div>

              {!connected ? (
                <p className="muted recent-empty">Connect your phone to see recent calls.</p>
              ) : !historyGranted && !historyLoading ? (
                <p className="muted recent-empty">
                  Call history is off. Allow Call history in LinkPhone on your phone.
                </p>
              ) : historyLoading && history.length === 0 ? (
                <p className="muted recent-empty">Loading recent calls…</p>
              ) : history.length === 0 ? (
                <p className="muted recent-empty">No recent calls.</p>
              ) : (
                <ul className="recent-list">
                  {history.map((entry) => {
                    const missed =
                      entry.type === 'missed' || entry.type === 'rejected' || entry.type === 'blocked'
                    return (
                      <li key={`${entry.id}-${entry.date}`} className="recent-row">
                        <div className={`recent-icon ${missed ? 'recent-missed' : ''}`}>
                          <Icon name={missed ? 'call_end' : 'call'} size={18} />
                        </div>
                        <div className="recent-body">
                          <div className="recent-top">
                            <span className="recent-name">{entry.name || entry.number || 'Unknown'}</span>
                            <span className="muted recent-time">{relativeTime(entry.date)}</span>
                          </div>
                          <div className="recent-meta">
                            <span>{logLabel(entry.type)}</span>
                            {entry.duration > 0 && !missed && (
                              <span> · {callDuration(entry.duration)}</span>
                            )}
                            {entry.name && entry.number && (
                              <span className="muted"> · {entry.number}</span>
                            )}
                            {entry.simLabel && (
                              <span className="muted"> · {entry.simLabel}</span>
                            )}
                          </div>
                        </div>
                        <button
                          className="icon-btn"
                          title={outgoingReady ? `Call ${entry.number}` : 'Calling needs phone access'}
                          disabled={!outgoingReady || !entry.number}
                          onClick={() => onCall(entry.number, accountId || undefined)}
                        >
                          <Icon name="call" size={18} />
                        </button>
                      </li>
                    )
                  })}
                </ul>
              )}
            </div>
          ) : (
            <ContactsPanel
              contacts={contacts}
              threads={threads}
              granted={contactsGranted}
              loading={contactsLoading}
              threadsLoading={smsLoading}
              connected={connected}
              dialerReady={outgoingReady}
              onRefresh={onRefreshContacts}
              onCall={(contactNumber) => onCall(contactNumber, accountId || undefined)}
              onMessage={onMessage}
            />
          )}
        </div>
      </div>
    </div>
  )
}

function CallCard({
  call,
  now,
  speaker,
  controlReady,
  onAnswer,
  onReject,
  onDisconnect,
  onSpeaker
}: {
  call: PhoneCallInfo
  now: number
  speaker: boolean
  controlReady: boolean
  onAnswer: (callId: string) => void
  onReject: (callId: string) => void
  onDisconnect: (callId: string) => void
  onSpeaker: (enabled: boolean) => void
}): JSX.Element {
  const ringing = call.phase === 'ringing'
  const ended = call.phase === 'disconnected'
  const title = call.displayName || call.number || 'Unknown caller'
  const subtitle = call.displayName && call.number ? call.number : phaseLabel(call.phase)
  const duration = call.phase === 'active' ? elapsed(Math.max(0, now - call.startedAt)) : ''

  return (
    <div className={`active-call ${ringing ? 'incoming-call' : ''}`}>
      <div className="caller-avatar">{title.slice(0, 2).toUpperCase()}</div>
      <div className="caller-name">{title}</div>
      <div className="caller-detail">
        {subtitle}
        {duration ? ` · ${duration}` : ''}
        {call.simLabel ? ` · ${call.simLabel}` : ''}
      </div>

      {ringing ? (
        <div className="call-actions">
          <button
            className="call-decline"
            disabled={!controlReady}
            title={controlReady ? undefined : 'Allow call access on your phone'}
            onClick={() => onReject(call.id)}
          >
            <Icon name="call_end" size={18} /> Decline
          </button>
          <button
            className="call-answer"
            disabled={!controlReady}
            title={controlReady ? undefined : 'Allow call access on your phone'}
            onClick={() => onAnswer(call.id)}
          >
            <Icon name="call" size={18} /> Answer
          </button>
        </div>
      ) : (
        <div className="call-actions">
          <button
            className={`call-speaker ${speaker ? 'call-speaker-on' : ''}`}
            disabled={ended || !controlReady}
            title={controlReady ? undefined : 'Allow call access on your phone'}
            onClick={() => onSpeaker(!speaker)}
          >
            <Icon name={speaker ? 'volume_off' : 'volume_up'} size={18} />
            {speaker ? 'Speaker on' : 'Phone speaker'}
          </button>
          <button
            className="call-decline"
            disabled={ended || !controlReady}
            title={controlReady ? undefined : 'Allow call access on your phone'}
            onClick={() => onDisconnect(call.id)}
          >
            <Icon name="call_end" size={18} /> Hang up
          </button>
        </div>
      )}
    </div>
  )
}

function logLabel(type: CallLogEntry['type']): string {
  switch (type) {
    case 'incoming':
      return 'Incoming'
    case 'outgoing':
      return 'Outgoing'
    case 'missed':
      return 'Missed'
    case 'rejected':
      return 'Declined'
    case 'blocked':
      return 'Blocked'
    case 'voicemail':
      return 'Voicemail'
    default:
      return 'Call'
  }
}

function phaseLabel(phase: PhoneCallInfo['phase']): string {
  switch (phase) {
    case 'ringing':
      return 'Incoming call'
    case 'dialing':
      return 'Dialing…'
    case 'connecting':
      return 'Connecting…'
    case 'active':
      return 'Connected'
    case 'held':
      return 'On hold'
    case 'disconnecting':
      return 'Ending…'
    case 'disconnected':
      return 'Call ended'
    default:
      return 'Call'
  }
}

function elapsed(milliseconds: number): string {
  const seconds = Math.floor(milliseconds / 1000)
  const minutes = Math.floor(seconds / 60)
  return `${minutes}:${String(seconds % 60).padStart(2, '0')}`
}

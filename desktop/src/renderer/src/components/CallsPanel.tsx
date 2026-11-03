import { useEffect, useState } from 'react'
import type { CallLogEntry, PhoneCallInfo, PhoneContact, SmsThread } from '@shared/protocol'
import { Icon } from './Icon'
import { ContactsPanel } from './ContactsPanel'
import { callDuration, relativeTime } from '../lib/phone'

interface Props {
  calls: PhoneCallInfo[]
  dialerReady: boolean
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
  onPlace: (number: string) => void
  onAnswer: (callId: string) => void
  onReject: (callId: string) => void
  onDisconnect: (callId: string) => void
  onSpeaker: (enabled: boolean) => void
  onRefreshHistory: () => void
  onRefreshContacts: () => void
  onCall: (number: string) => void
  onMessage: (threadId: string, address: string) => void
}

const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#']

export function CallsPanel({
  calls,
  dialerReady,
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
  onPlace,
  onAnswer,
  onReject,
  onDisconnect,
  onSpeaker,
  onRefreshHistory,
  onRefreshContacts,
  onCall,
  onMessage
}: Props): JSX.Element {
  const [number, setNumber] = useState('')
  const [now, setNow] = useState(Date.now())

  useEffect(() => {
    if (!calls.some((call) => call.phase === 'active')) return
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [calls])

  const place = (): void => {
    const value = number.trim()
    if (!value) return
    onPlace(value)
  }

  return (
    <div className="calls-panel">
      <div className="call-audio-note">
        <strong>Call audio stays on your phone</strong>
        <span>Windows controls the call only. Use Phone speaker below to hear it hands-free.</span>
      </div>

      {!dialerReady && (
        <div className="call-warning">
          On your phone, open LinkPhone and set it as the default phone app before using call
          controls.
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
                  onAnswer={onAnswer}
                  onReject={onReject}
                  onDisconnect={onDisconnect}
                  onSpeaker={onSpeaker}
                />
              ))}
            </div>
          ) : (
            <div className="dialer-card">
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
              <button className="call-primary" disabled={!dialerReady || !number.trim()} onClick={place}>
                <Icon name="call" size={18} /> Call
              </button>
            </div>
          )}

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
                        </div>
                      </div>
                      <button
                        className="icon-btn"
                        title={dialerReady ? `Call ${entry.number}` : 'Calling needs the default phone app'}
                        disabled={!dialerReady || !entry.number}
                        onClick={() => onCall(entry.number)}
                      >
                        <Icon name="call" size={18} />
                      </button>
                    </li>
                  )
                })}
              </ul>
            )}
          </div>
        </div>
        <div className="calls-contacts">
          <ContactsPanel
            contacts={contacts}
            threads={threads}
            granted={contactsGranted}
            loading={contactsLoading}
            threadsLoading={smsLoading}
            connected={connected}
            dialerReady={dialerReady}
            onRefresh={onRefreshContacts}
            onCall={onCall}
            onMessage={onMessage}
          />
        </div>
      </div>
    </div>
  )
}

function CallCard({
  call,
  now,
  speaker,
  onAnswer,
  onReject,
  onDisconnect,
  onSpeaker
}: {
  call: PhoneCallInfo
  now: number
  speaker: boolean
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
      </div>

      {ringing ? (
        <div className="call-actions">
          <button className="call-decline" onClick={() => onReject(call.id)}>
            <Icon name="call_end" size={18} /> Decline
          </button>
          <button className="call-answer" onClick={() => onAnswer(call.id)}>
            <Icon name="call" size={18} /> Answer
          </button>
        </div>
      ) : (
        <div className="call-actions">
          <button
            className={`call-speaker ${speaker ? 'call-speaker-on' : ''}`}
            disabled={ended}
            onClick={() => onSpeaker(!speaker)}
          >
            <Icon name={speaker ? 'volume_off' : 'volume_up'} size={18} />
            {speaker ? 'Speaker on' : 'Phone speaker'}
          </button>
          <button className="call-decline" disabled={ended} onClick={() => onDisconnect(call.id)}>
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

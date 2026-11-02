import { useEffect, useState } from 'react'
import type { PhoneCallInfo } from '@shared/protocol'

interface Props {
  calls: PhoneCallInfo[]
  dialerReady: boolean
  speaker: boolean
  error: string
  onPlace: (number: string) => void
  onAnswer: (callId: string) => void
  onReject: (callId: string) => void
  onDisconnect: (callId: string) => void
  onSpeaker: (enabled: boolean) => void
}

const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#']

export function CallsPanel({
  calls,
  dialerReady,
  speaker,
  error,
  onPlace,
  onAnswer,
  onReject,
  onDisconnect,
  onSpeaker
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
              ⌫
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
            Call
          </button>
        </div>
      )}
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
            Decline
          </button>
          <button className="call-answer" onClick={() => onAnswer(call.id)}>
            Answer
          </button>
        </div>
      ) : (
        <div className="call-actions">
          <button
            className={`call-speaker ${speaker ? 'call-speaker-on' : ''}`}
            disabled={ended}
            onClick={() => onSpeaker(!speaker)}
          >
            {speaker ? 'Speaker on' : 'Phone speaker'}
          </button>
          <button className="call-decline" disabled={ended} onClick={() => onDisconnect(call.id)}>
            Hang up
          </button>
        </div>
      )}
    </div>
  )
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

import { useEffect, useMemo, useRef, useState } from 'react'
import type { ActionResult } from '@shared/api'
import type { MutedApp, NotificationItem } from '@shared/protocol'
import { Icon } from './Icon'

interface Props {
  notifications: NotificationItem[]
  muted: MutedApp[]
  onDismiss: (key: string) => void
  onReply: (
    key: string,
    actionId: string,
    text: string,
    label?: string
  ) => Promise<ActionResult>
  onAction: (key: string, actionId: string, label?: string) => Promise<ActionResult>
  onMuteApp: (pkg: string, app: string) => void
  onUnmuteApp: (pkg: string, app: string) => void
  onClearApp: (pkg: string, app: string) => void
  onLoadHistory: () => Promise<NotificationItem[]>
  onClearHistory: () => Promise<void>
  onClearAll: () => Promise<void>
  connected: boolean
  pausedUntil: number
  onPause: (until: number | null) => void
  media: {
    title: string
    artist: string
    playing: boolean
    app: string
    positionMs: number
    durationMs: number
    volume: number
    volumeMax: number
    artId: string
  } | null
  mediaArt: { id: string; mime: string; data: string } | null
  onMediaAction: (
    action: 'play' | 'pause' | 'next' | 'previous' | 'seek' | 'volume',
    value?: number
  ) => void
}

type ViewMode = 'newest' | 'app'

function replyErrorText(error?: string): string {
  switch (error) {
    case 'gone':
      return 'Notification closed — reply from the app instead.'
    case 'no-peer':
      return 'Phone disconnected — reply was not sent.'
    case 'unavailable':
      return 'Notification access is off on the phone.'
    case 'action-changed':
      return 'The notification changed — try again.'
    case 'too-long':
      return 'Reply is too long (2000 characters).'
    case 'blank':
      return 'Type a reply first.'
    case 'timeout':
      return 'No response from the phone — try again.'
    default:
      return 'Reply was not sent — try again.'
  }
}

function ago(when: number): string {
  const seconds = Math.max(0, Math.round((Date.now() - when) / 1000))
  if (seconds < 60) return 'now'
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes}m`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours}h`
  return `${Math.round(hours / 24)}d`
}

export function NotificationsPanel({
  notifications,
  muted,
  onDismiss,
  onReply,
  onAction,
  onMuteApp,
  onUnmuteApp,
  onClearApp,
  onLoadHistory,
  onClearHistory,
  onClearAll,
  connected,
  pausedUntil,
  onPause,
  media,
  mediaArt,
  onMediaAction
}: Props): JSX.Element {
  const [replying, setReplying] = useState<string | null>(null)
  const [draft, setDraft] = useState('')
  const [replyStatus, setReplyStatus] = useState<{
    key: string
    kind: 'sending' | 'sent' | 'error'
    message?: string
  } | null>(null)
  const [query, setQuery] = useState('')
  const [view, setView] = useState<ViewMode>('newest')
  const [mode, setMode] = useState<'live' | 'history'>('live')
  const [history, setHistory] = useState<NotificationItem[]>([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [historyError, setHistoryError] = useState(false)
  const [confirmClearHistory, setConfirmClearHistory] = useState(false)
  const [confirmClear, setConfirmClear] = useState(false)
  const [pauseTick, setPauseTick] = useState(0)
  const paused = pausedUntil > Date.now()
  const pausedLabel = new Date(pausedUntil).toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit'
  })
  const minutesLeft = Math.max(0, Math.ceil((pausedUntil - Date.now()) / 60000))

  useEffect(() => {
    if (!paused) return
    const timer = setInterval(() => setPauseTick((value) => value + 1), 1000)
    return () => clearInterval(timer)
  }, [paused])
  void pauseTick

  const untilTomorrow = (): void => {
    const date = new Date()
    date.setDate(date.getDate() + 1)
    date.setHours(8, 0, 0, 0)
    onPause(date.getTime())
  }

  const [dragPosition, setDragPosition] = useState<number | null>(null)
  const [localVolume, setLocalVolume] = useState<number | null>(null)
  const [mediaNow, setMediaNow] = useState(Date.now())
  const volumeTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const positionAnchor = useRef({ position: 0, at: Date.now() })
  const duration = media?.durationMs ?? 0
  const shownPosition =
    dragPosition ??
    (media
      ? media.playing && duration > 0
        ? Math.min(
            duration,
            positionAnchor.current.position +
              Math.max(0, mediaNow - positionAnchor.current.at)
          )
        : media.positionMs
      : 0)
  const shownVolume = localVolume ?? media?.volume ?? 0
  const artUrl = mediaArt ? `data:${mediaArt.mime};base64,${mediaArt.data}` : ''
  const clock = (value: number): string => {
    const total = Math.max(0, Math.round(value / 1000))
    return `${Math.floor(total / 60)}:${(total % 60).toString().padStart(2, '0')}`
  }

  useEffect(() => {
    setDragPosition(null)
    setLocalVolume(null)
  }, [media?.title, media?.durationMs])

  useEffect(() => {
    positionAnchor.current = {
      position: media?.positionMs ?? 0,
      at: Date.now()
    }
    setMediaNow(Date.now())
  }, [media?.title, media?.durationMs, media?.positionMs, media?.playing])

  useEffect(() => {
    setLocalVolume(null)
  }, [media?.volume])

  useEffect(() => {
    if (!media?.playing || dragPosition !== null) return
    const timer = setInterval(() => setMediaNow(Date.now()), 500)
    return () => clearInterval(timer)
  }, [media?.playing, dragPosition])

  useEffect(
    () => () => {
      if (volumeTimer.current) clearTimeout(volumeTimer.current)
    },
    []
  )

  const commitSeek = (): void => {
    if (dragPosition === null) return
    onMediaAction('seek', Math.round(dragPosition))
    setDragPosition(null)
    setMediaNow(Date.now())
  }

  const changeVolume = (value: number): void => {
    setLocalVolume(value)
    if (volumeTimer.current) clearTimeout(volumeTimer.current)
    volumeTimer.current = setTimeout(() => onMediaAction('volume', value), 120)
  }

  const loadHistory = (): void => {
    setHistoryLoading(true)
    setHistoryError(false)
    void onLoadHistory()
      .then((items) => setHistory(items))
      .catch(() => {
        setHistory([])
        setHistoryError(true)
      })
      .finally(() => setHistoryLoading(false))
  }

  useEffect(() => {
    setQuery('')
    setConfirmClear(false)
    setConfirmClearHistory(false)
    if (mode === 'history') loadHistory()
  }, [mode])

  const source = mode === 'history' ? history : notifications

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase()
    if (!needle) return source
    return source.filter((item) =>
      `${item.app} ${item.title} ${item.text}`.toLowerCase().includes(needle)
    )
  }, [source, query])

  const groups = useMemo(() => {
    const map = new Map<
      string,
      { key: string; pkg: string; app: string; items: NotificationItem[]; latest: number }
    >()
    for (const item of filtered) {
      const key = item.pkg || `app:${item.app.toLowerCase()}`
      const group: {
        key: string
        pkg: string
        app: string
        items: NotificationItem[]
        latest: number
      } = map.get(key) ?? {
        key,
        pkg: item.pkg,
        app: item.app,
        items: [],
        latest: 0
      }
      group.items.push(item)
      group.latest = Math.max(group.latest, item.when)
      if (!group.app) group.app = item.app
      map.set(key, group)
    }
    return [...map.values()].sort(
      (left, right) => right.latest - left.latest || left.app.localeCompare(right.app)
    )
  }, [filtered])

  const submitReply = async (item: NotificationItem): Promise<void> => {
    const action = item.actions.find((candidate) => candidate.reply)
    const body = draft.trim()
    if (!action || body.length === 0 || replyStatus?.kind === 'sending') return
    setReplyStatus({ key: item.key, kind: 'sending' })
    const result = await onReply(item.key, action.id, body, action.label)
    if (result.ok) {
      setReplyStatus({ key: item.key, kind: 'sent' })
      setDraft('')
      setReplying(null)
      window.setTimeout(() => {
        setReplyStatus((current) =>
          current?.key === item.key && current.kind === 'sent' ? null : current
        )
      }, 2500)
      return
    }
    setReplyStatus({
      key: item.key,
      kind: 'error',
      message: replyErrorText(result.error)
    })
  }

  const renderCard = (item: NotificationItem): JSX.Element => (
    <NotificationCard
      key={item.key}
      item={item}
      replying={replying === item.key}
      draft={replying === item.key ? draft : ''}
      onDraft={setDraft}
      onStartReply={() => {
        setReplying(item.key)
        setDraft('')
        setReplyStatus(null)
      }}
      onCancelReply={() => setReplying(null)}
      onSubmitReply={() => void submitReply(item)}
      status={replyStatus?.key === item.key ? replyStatus : null}
      sending={replyStatus?.key === item.key && replyStatus.kind === 'sending'}
      onDismiss={() => {
        if (replying === item.key) setReplying(null)
        onDismiss(item.key)
      }}
      onAction={(actionId, label) => void onAction(item.key, actionId, label)}
    />
  )

  return (
    <div className="notif-center">
      {mode === 'live' && media && (
        <div className="media-wrap">
          <div className="media-card">
            <div className="media-icon">
              {artUrl ? (
                <img src={artUrl} alt={`Album art for ${media.title}`} />
              ) : (
                <Icon name="volume_up" size={22} />
              )}
            </div>
            <div className="media-body">
              <strong title={media.title}>{media.title}</strong>
              <span>{[media.artist, media.app].filter(Boolean).join(' · ')}</span>
            </div>
            <div className="media-actions">
              <button
                type="button"
                className="icon-btn"
                title="Previous"
                aria-label="Previous track"
                disabled={!connected}
                onClick={() => onMediaAction('previous')}
              >
                <Icon name="skip_previous" size={20} />
              </button>
              <button
                type="button"
                className="icon-btn"
                title={media.playing ? 'Pause' : 'Play'}
                aria-label={media.playing ? 'Pause' : 'Play'}
                disabled={!connected}
                onClick={() => onMediaAction(media.playing ? 'pause' : 'play')}
              >
                <Icon name={media.playing ? 'pause' : 'play_arrow'} size={22} />
              </button>
              <button
                type="button"
                className="icon-btn"
                title="Next"
                aria-label="Next track"
                disabled={!connected}
                onClick={() => onMediaAction('next')}
              >
                <Icon name="skip_next" size={20} />
              </button>
            </div>
          </div>
          <div className="media-seek">
            {duration > 0 ? (
              <>
                <span>{clock(shownPosition)}</span>
                <input
                  type="range"
                  min={0}
                  max={duration}
                  step={1000}
                  value={Math.min(duration, shownPosition)}
                  disabled={!connected}
                  aria-label="Seek"
                  onChange={(event) => setDragPosition(Number(event.target.value))}
                  onPointerUp={commitSeek}
                  onKeyUp={commitSeek}
                  onBlur={commitSeek}
                />
                <span>{clock(duration)}</span>
              </>
            ) : (
              <>
                <span className="muted">Live</span>
                <span className="muted">{clock(shownPosition)}</span>
              </>
            )}
          </div>
          {media.volumeMax > 0 && (
            <div className="media-volume">
              <Icon name="volume_up" size={16} />
              <input
                type="range"
                min={0}
                max={media.volumeMax}
                value={Math.min(media.volumeMax, shownVolume)}
                disabled={!connected}
                aria-label="Phone volume"
                onChange={(event) => changeVolume(Number(event.target.value))}
              />
            </div>
          )}
        </div>
      )}

      {paused && (
        <div className="notif-paused">
          <span>
            Notifications paused until {pausedLabel} ({minutesLeft} min)
          </span>
          <button type="button" className="link-btn" onClick={() => onPause(null)}>
            Resume
          </button>
        </div>
      )}

      <div className="notif-toolbar">
        <div className="notif-view" role="group" aria-label="Notification source">
          <button
            type="button"
            className={mode === 'live' ? 'notif-view-active' : ''}
            aria-pressed={mode === 'live'}
            onClick={() => setMode('live')}
          >
            Live
          </button>
          <button
            type="button"
            className={mode === 'history' ? 'notif-view-active' : ''}
            aria-pressed={mode === 'history'}
            onClick={() => setMode('history')}
          >
            History
          </button>
        </div>
        {mode === 'history' && (
          <div className="notif-history-actions">
            <button type="button" className="link-btn" onClick={loadHistory} disabled={historyLoading} aria-label="Refresh history">
              {historyLoading ? 'Loading…' : (<><Icon name="refresh" size={18} /> Refresh</>)}
            </button>
            {confirmClearHistory ? (
              <>
                <button
                  type="button"
                  className="link-btn"
                  onClick={() => {
                    void onClearHistory().then(() => {
                      setHistory([])
                      setHistoryError(false)
                      setConfirmClearHistory(false)
                    })
                  }}
                >
                  Confirm clear
                </button>
                <button
                  type="button"
                  className="link-btn"
                  onClick={() => setConfirmClearHistory(false)}
                >
                  Cancel
                </button>
              </>
            ) : (
              <button
                type="button"
                className="link-btn"
                onClick={() => setConfirmClearHistory(true)}
              >
                Clear history
              </button>
            )}
          </div>
        )}
        {mode === 'live' && notifications.length > 0 && (
          <div className="notif-history-actions">
            {confirmClear ? (
              <>
                <button
                  type="button"
                  className="link-btn"
                  onClick={() => {
                    void onClearAll()
                      .then(() => setConfirmClear(false))
                      .catch(() => setConfirmClear(false))
                  }}
                >
                  Confirm clear
                </button>
                <button
                  type="button"
                  className="link-btn"
                  onClick={() => setConfirmClear(false)}
                >
                  Cancel
                </button>
              </>
            ) : (
              <button
                type="button"
                className="link-btn"
                onClick={() => setConfirmClear(true)}
              >
                Clear all
              </button>
            )}
          </div>
        )}
      </div>

      {!paused && (
        <div className="notif-pause-actions">
          <span className="muted">Pause toasts</span>
          <button
            type="button"
            className="link-btn"
            onClick={() => onPause(Date.now() + 30 * 60 * 1000)}
          >
            30 min
          </button>
          <button
            type="button"
            className="link-btn"
            onClick={() => onPause(Date.now() + 60 * 60 * 1000)}
          >
            1 hour
          </button>
          <button type="button" className="link-btn" onClick={untilTomorrow}>
            Until tomorrow
          </button>
        </div>
      )}

      {(source.length > 0 || query.length > 0) && (
        <div className="notif-toolbar">
          <div className="notif-search">
            <Icon name="search" size={18} />
            <input
              value={query}
              placeholder={mode === 'history' ? 'Search history' : 'Search notifications'}
              aria-label={mode === 'history' ? 'Search history' : 'Search notifications'}
              onChange={(event) => setQuery(event.target.value.slice(0, 80))}
            />
          </div>
          {mode === 'live' && (
            <div className="notif-view" role="group" aria-label="Notification view">
              <button
                type="button"
                className={view === 'newest' ? 'notif-view-active' : ''}
                aria-pressed={view === 'newest'}
                onClick={() => setView('newest')}
              >
                Newest
              </button>
              <button
                type="button"
                className={view === 'app' ? 'notif-view-active' : ''}
                aria-pressed={view === 'app'}
                onClick={() => setView('app')}
              >
                By app
              </button>
            </div>
          )}
        </div>
      )}

      {mode === 'live' && muted.length > 0 && (
        <div className="notif-muted-row">
          <span className="notif-muted-label">Muted</span>
          {muted.map((entry) => (
            <button
              key={entry.pkg || entry.app}
              type="button"
              className="notif-chip"
              title={`Unmute ${entry.app}`}
              aria-label={`Unmute ${entry.app}`}
              onClick={() => onUnmuteApp(entry.pkg, entry.app)}
            >
              <Icon name="close" size={12} /> {entry.app}
            </button>
          ))}
        </div>
      )}

      {mode === 'history' ? (
        historyLoading && history.length === 0 ? (
          <div className="empty">
            <div className="empty-art"><Icon name="notifications" size={34} /></div>
            <h2>Loading history…</h2>
          </div>
        ) : historyError && history.length === 0 ? (
          <div className="empty">
            <div className="empty-art"><Icon name="warning" size={34} /></div>
            <h2>Couldn’t load history</h2>
            <p className="muted">LinkPhone could not read the local history file.</p>
            <button type="button" className="link-btn" onClick={loadHistory}>
              <Icon name="refresh" size={18} /> Retry
            </button>
          </div>
        ) : history.length === 0 ? (
          <div className="empty">
            <div className="empty-art"><Icon name="notifications" size={34} /></div>
            <h2>No history yet</h2>
            <p className="muted">Notifications you receive will be kept here, even after they are dismissed.</p>
          </div>
        ) : filtered.length === 0 ? (
          <div className="empty">
            <div className="empty-art"><Icon name="search" size={34} /></div>
            <h2>No history matches</h2>
            <p className="muted">Try a different app name, title or message.</p>
          </div>
        ) : (
          <ul className="notif-list">
            {filtered.map((item) => (
              <HistoryRow key={`${item.deviceId}:${item.key}`} item={item} />
            ))}
          </ul>
        )
      ) : notifications.length === 0 ? (
        !connected ? (
          <div className="empty">
            <div className="empty-art"><Icon name="smartphone" size={34} /></div>
            <h2>Phone not connected</h2>
            <p className="muted">Connect your phone to see notifications.</p>
          </div>
        ) : muted.length > 0 ? (
          <div className="empty">
            <div className="empty-art"><Icon name="notifications" size={34} /></div>
            <h2>No notifications from unmuted apps</h2>
            <p className="muted">Unmute an app above to see its notifications again.</p>
          </div>
        ) : (
          <div className="empty">
            <div className="empty-art"><Icon name="notifications" size={34} /></div>
            <h2>No notifications yet</h2>
            <p className="muted">
              Notifications from your phone appear here, including ongoing and grouped alerts.
            </p>
          </div>
        )
      ) : filtered.length === 0 ? (
        <div className="empty">
          <div className="empty-art"><Icon name="search" size={34} /></div>
          <h2>No notifications match</h2>
          <p className="muted">Try a different app name, title or message.</p>
        </div>
      ) : view === 'newest' ? (
        <ul className="notif-list">{filtered.map(renderCard)}</ul>
      ) : (
        <div className="notif-groups">
          {groups.map((group) => (
            <section className="notif-group" key={group.key}>
              <div className="notif-group-head">
                <div className="notif-group-title">
                  <NotifAvatar app={group.app} icon={group.items[0]?.icon} />
                  <span className="notif-group-name">{group.app || group.pkg}</span>
                  <span className="badge">{group.items.length}</span>
                </div>
                <div className="notif-group-actions">
                  <button
                    type="button"
                    className="link-btn"
                    title={`Clear all ${group.app} notifications`}
                    onClick={() => onClearApp(group.pkg, group.app)}
                  >
                    Clear
                  </button>
                  <button
                    type="button"
                    className="link-btn"
                    title={`Mute and clear ${group.app} notifications`}
                    onClick={() => onMuteApp(group.pkg, group.app)}
                  >
                    Mute
                  </button>
                </div>
              </div>
              <ul className="notif-list">{group.items.map(renderCard)}</ul>
            </section>
          ))}
        </div>
      )}
    </div>
  )
}

function NotifAvatar({ app, icon }: { app: string; icon?: string }): JSX.Element {
  if (icon) {
    return (
      <img
        className="notif-avatar notif-avatar-img"
        src={icon}
        alt=""
        aria-hidden="true"
      />
    )
  }
  return <div className="notif-avatar">{app.slice(0, 2).toUpperCase()}</div>
}

function HistoryRow({ item }: { item: NotificationItem }): JSX.Element {
  return (
    <li className="notif notif-history-row">
      <NotifAvatar app={item.app} icon={item.icon} />
      <div className="notif-body">
        <div className="notif-top">
          <span className="notif-app">{item.app}</span>
          <span className="muted notif-time">{ago(item.when)}</span>
        </div>
        {item.title && <div className="notif-title">{item.title}</div>}
        {item.text && <div className="notif-text">{item.text}</div>}
      </div>
    </li>
  )
}

function NotificationCard({
  item,
  replying,
  draft,
  onDraft,
  onStartReply,
  onCancelReply,
  onSubmitReply,
  onDismiss,
  onAction,
  status,
  sending
}: {
  item: NotificationItem
  replying: boolean
  draft: string
  onDraft: (value: string) => void
  onStartReply: () => void
  onCancelReply: () => void
  onSubmitReply: () => void
  onDismiss: () => void
  onAction: (actionId: string, label?: string) => void
  status: { kind: 'sending' | 'sent' | 'error'; message?: string } | null
  sending: boolean
}): JSX.Element {
  const canOpen = item.actions.some((action) => action.id === 'content')
  const buttons = item.actions
    .filter(
      (action) => !action.reply && action.id !== 'content' && action.id !== 'reply'
    )
    .slice(0, 2)
  const open = (): void => onAction('content')
  const progressMax = item.progressMax ?? 0
  const indeterminate = item.indeterminate === true
  const hasProgress = indeterminate || progressMax > 0
  const pct = hasProgress && !indeterminate
    ? Math.max(0, Math.min(100, Math.round(((item.progress ?? 0) / progressMax) * 100)))
    : null
  const live = Boolean(item.ongoing && hasProgress && (indeterminate || (pct ?? 0) < 100))

  return (
    <li className="notif">
      <NotifAvatar app={item.app} icon={item.icon} />
      <div
        className={`notif-body ${canOpen ? 'notif-body-clickable' : ''}`}
        role={canOpen ? 'button' : undefined}
        tabIndex={canOpen ? 0 : undefined}
        title={canOpen ? 'Open on phone' : undefined}
        aria-label={canOpen ? `Open ${item.app} on phone` : undefined}
        onClick={canOpen ? open : undefined}
        onKeyDown={
          canOpen
            ? (event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault()
                  open()
                }
              }
            : undefined
        }
      >
        <div className="notif-top">
          <span className="notif-app">{item.app}</span>
          <span className="muted notif-time">{ago(item.when)}</span>
        </div>
        {item.title && <div className="notif-title">{item.title}</div>}
        {item.text && <div className="notif-text">{item.text}</div>}

        {item.ongoing && !hasProgress && (
          <div className="notif-ongoing"><span className="notif-live">Ongoing</span></div>
        )}

        {hasProgress && (
          <div className="notif-progress-row">
            <div
              className={`notif-progress ${indeterminate ? 'notif-progress-indeterminate' : ''}`}
              role="progressbar"
              aria-valuemin={0}
              aria-valuemax={indeterminate ? undefined : progressMax}
              aria-valuenow={indeterminate ? undefined : pct ?? 0}
              aria-valuetext={indeterminate ? 'In progress' : `${pct ?? 0}%`}
            >
              <div
                className="notif-progress-fill"
                style={indeterminate ? undefined : { width: `${pct ?? 0}%` }}
              />
            </div>
            {!indeterminate && <span className="notif-progress-pct">{pct ?? 0}%</span>}
            {live && <span className="notif-live">Live</span>}
          </div>
        )}

        {replying ? (
          <div
            className="reply-row"
            onClick={(event) => event.stopPropagation()}
            onKeyDown={(event) => event.stopPropagation()}
          >
            <input
              autoFocus
              value={draft}
              placeholder="Reply…"
              maxLength={2000}
              disabled={sending}
              onChange={(event) => onDraft(event.target.value)}
              onClick={(event) => event.stopPropagation()}
              onKeyDown={(event) => {
                event.stopPropagation()
                if (event.key === 'Enter' && !sending) onSubmitReply()
                if (event.key === 'Escape') onCancelReply()
              }}
            />
            <button
              type="button"
              className="btn btn-small"
              disabled={sending}
              onClick={(event) => {
                event.stopPropagation()
                onSubmitReply()
              }}
              onKeyDown={(event) => event.stopPropagation()}
            >
              <Icon name="send" size={16} /> {sending ? 'Sending…' : 'Send'}
            </button>
          </div>
        ) : (
          (item.canReply || item.actions.some((action) => action.reply)) && (
            <button
              type="button"
              className="link-btn"
              onClick={(event) => {
                event.stopPropagation()
                onStartReply()
              }}
              onKeyDown={(event) => event.stopPropagation()}
            >
              Reply
            </button>
          )
        )}

        {status?.kind === 'error' && (
          <p className="reply-error" role="alert">
            {status.message}
          </p>
        )}
        {status?.kind === 'sent' && (
          <p className="reply-sent" role="status">
            Sent
          </p>
        )}

        {buttons.length > 0 && (
          <div
            className="notif-actions"
            onClick={(event) => event.stopPropagation()}
            onKeyDown={(event) => event.stopPropagation()}
          >
            {buttons.map((action) => (
              <button
                key={action.id}
                type="button"
                className="notif-action"
                title={action.label}
                onClick={(event) => {
                  event.stopPropagation()
                  onAction(action.id, action.label)
                }}
              >
                {action.label}
              </button>
            ))}
          </div>
        )}
      </div>
      <button
        type="button"
        className="icon-btn"
        title="Dismiss"
        aria-label={`Dismiss ${item.app || 'notification'}`}
        onClick={onDismiss}
      >
        <Icon name="close" />
      </button>
    </li>
  )
}

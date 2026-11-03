import { useMemo, useState } from 'react'
import type { MutedApp, NotificationItem } from '@shared/protocol'
import { Icon } from './Icon'

interface Props {
  notifications: NotificationItem[]
  muted: MutedApp[]
  onDismiss: (key: string) => void
  onReply: (key: string, actionId: string, text: string) => void
  onAction: (key: string, actionId: string) => void
  onMuteApp: (pkg: string, app: string) => void
  onUnmuteApp: (pkg: string, app: string) => void
  onClearApp: (pkg: string, app: string) => void
}

type ViewMode = 'newest' | 'app'

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
  onClearApp
}: Props): JSX.Element {
  const [replying, setReplying] = useState<string | null>(null)
  const [draft, setDraft] = useState('')
  const [query, setQuery] = useState('')
  const [view, setView] = useState<ViewMode>('newest')

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase()
    if (!needle) return notifications
    return notifications.filter((item) =>
      `${item.app} ${item.title} ${item.text}`.toLowerCase().includes(needle)
    )
  }, [notifications, query])

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

  const submitReply = (item: NotificationItem): void => {
    const action = item.actions.find((candidate) => candidate.reply)
    if (!action || draft.trim().length === 0) return
    onReply(item.key, action.id, draft)
    setDraft('')
    setReplying(null)
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
      }}
      onCancelReply={() => setReplying(null)}
      onSubmitReply={() => submitReply(item)}
      onDismiss={() => {
        if (replying === item.key) setReplying(null)
        onDismiss(item.key)
      }}
      onAction={(actionId) => onAction(item.key, actionId)}
    />
  )

  if (notifications.length === 0 && muted.length === 0) {
    return (
      <div className="empty">
        <div className="empty-art"><Icon name="notifications" size={34} /></div>
        <h2>No notifications yet</h2>
        <p className="muted">
          Once your phone connects and notification access is granted, everything that lands on it
          shows up here.
        </p>
      </div>
    )
  }

  return (
    <div className="notif-center">
      {notifications.length > 0 && (
        <div className="notif-toolbar">
          <div className="notif-search">
            <Icon name="search" size={18} />
            <input
              value={query}
              placeholder="Search notifications"
              aria-label="Search notifications"
              onChange={(event) => setQuery(event.target.value.slice(0, 80))}
            />
          </div>
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
        </div>
      )}

      {muted.length > 0 && (
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

      {notifications.length === 0 ? (
        <div className="empty">
          <div className="empty-art"><Icon name="notifications" size={34} /></div>
          <h2>No notifications from unmuted apps</h2>
          <p className="muted">Unmute an app above to see its notifications again.</p>
        </div>
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
                  <div className="notif-avatar">{group.app.slice(0, 2).toUpperCase()}</div>
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

function NotificationCard({
  item,
  replying,
  draft,
  onDraft,
  onStartReply,
  onCancelReply,
  onSubmitReply,
  onDismiss,
  onAction
}: {
  item: NotificationItem
  replying: boolean
  draft: string
  onDraft: (value: string) => void
  onStartReply: () => void
  onCancelReply: () => void
  onSubmitReply: () => void
  onDismiss: () => void
  onAction: (actionId: string) => void
}): JSX.Element {
  const canOpen = item.actions.some((action) => action.id === 'content')
  const buttons = item.actions
    .filter((action) => !action.reply && action.id !== 'content')
    .slice(0, 3)
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
      <div className="notif-avatar">{item.app.slice(0, 2).toUpperCase()}</div>
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
              onChange={(event) => onDraft(event.target.value)}
              onClick={(event) => event.stopPropagation()}
              onKeyDown={(event) => {
                event.stopPropagation()
                if (event.key === 'Enter') onSubmitReply()
                if (event.key === 'Escape') onCancelReply()
              }}
            />
            <button
              type="button"
              className="btn btn-small"
              onClick={(event) => {
                event.stopPropagation()
                onSubmitReply()
              }}
              onKeyDown={(event) => event.stopPropagation()}
            >
              <Icon name="send" size={16} /> Send
            </button>
          </div>
        ) : (
          item.canReply && (
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
                  onAction(action.id)
                }}
              >
                {action.label}
              </button>
            ))}
          </div>
        )}
      </div>
      <button type="button" className="icon-btn" title="Dismiss" onClick={onDismiss}>
        <Icon name="close" />
      </button>
    </li>
  )
}

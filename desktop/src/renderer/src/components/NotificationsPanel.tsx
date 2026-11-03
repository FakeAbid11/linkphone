import { useState } from 'react'
import type { NotificationItem } from '@shared/protocol'
import { Icon } from './Icon'

interface Props {
  notifications: NotificationItem[]
  onDismiss: (key: string) => void
  onReply: (key: string, actionId: string, text: string) => void
  onAction: (key: string, actionId: string) => void
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
  onDismiss,
  onReply,
  onAction
}: Props): JSX.Element {
  const [replying, setReplying] = useState<string | null>(null)
  const [draft, setDraft] = useState('')

  if (notifications.length === 0) {
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

  const submit = (item: NotificationItem): void => {
    const action = item.actions.find((a) => a.reply)
    if (!action || draft.trim().length === 0) return
    onReply(item.key, action.id, draft)
    setDraft('')
    setReplying(null)
  }

  return (
    <ul className="notif-list">
      {notifications.map((item) => {
        const canOpen = item.actions.some((action) => action.id === 'content')
        const buttons = item.actions
          .filter((action) => !action.reply && action.id !== 'content')
          .slice(0, 3)
        const open = (): void => onAction(item.key, 'content')

        return (
          <li className="notif" key={item.key}>
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

              {replying === item.key ? (
                <div
                  className="reply-row"
                  onClick={(event) => event.stopPropagation()}
                  onKeyDown={(event) => event.stopPropagation()}
                >
                  <input
                    autoFocus
                    value={draft}
                    placeholder="Reply…"
                    onChange={(e) => setDraft(e.target.value)}
                    onClick={(event) => event.stopPropagation()}
                    onKeyDown={(e) => {
                      e.stopPropagation()
                      if (e.key === 'Enter') submit(item)
                      if (e.key === 'Escape') setReplying(null)
                    }}
                  />
                  <button
                    className="btn btn-small"
                    onClick={(event) => {
                      event.stopPropagation()
                      submit(item)
                    }}
                    onKeyDown={(event) => event.stopPropagation()}
                  >
                    <Icon name="send" size={16} /> Send
                  </button>
                </div>
              ) : (
                item.canReply && (
                  <button
                    className="link-btn"
                    onClick={(event) => {
                      event.stopPropagation()
                      setReplying(item.key)
                      setDraft('')
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
                      className="notif-action"
                      title={action.label}
                      onClick={(event) => {
                        event.stopPropagation()
                        onAction(item.key, action.id)
                      }}
                    >
                      {action.label}
                    </button>
                  ))}
                </div>
              )}
            </div>
            <button className="icon-btn" title="Dismiss" onClick={() => onDismiss(item.key)}>
              <Icon name="close" />
            </button>
          </li>
        )
      })}
    </ul>
  )
}

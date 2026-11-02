import { useState } from 'react'
import type { NotificationItem } from '@shared/protocol'

interface Props {
  notifications: NotificationItem[]
  onDismiss: (key: string) => void
  onReply: (key: string, actionId: string, text: string) => void
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

export function NotificationsPanel({ notifications, onDismiss, onReply }: Props): JSX.Element {
  const [replying, setReplying] = useState<string | null>(null)
  const [draft, setDraft] = useState('')

  if (notifications.length === 0) {
    return (
      <div className="empty">
        <div className="empty-art">🔔</div>
        <h2>No notifications yet</h2>
        <p className="muted">
          Once your phone connects and notification access is granted, everything that lands on it
          shows up here.
        </p>
      </div>
    )
  }

  const submit = (item: NotificationItem): void => {
    const action = item.actions.find((a) => a.reply) ?? item.actions[0]
    if (!action || draft.trim().length === 0) return
    onReply(item.key, action.id, draft)
    setDraft('')
    setReplying(null)
  }

  return (
    <ul className="notif-list">
      {notifications.map((item) => (
        <li className="notif" key={item.key}>
          <div className="notif-avatar">{item.app.slice(0, 2).toUpperCase()}</div>
          <div className="notif-body">
            <div className="notif-top">
              <span className="notif-app">{item.app}</span>
              <span className="muted notif-time">{ago(item.when)}</span>
            </div>
            {item.title && <div className="notif-title">{item.title}</div>}
            {item.text && <div className="notif-text">{item.text}</div>}

            {replying === item.key ? (
              <div className="reply-row">
                <input
                  autoFocus
                  value={draft}
                  placeholder="Reply…"
                  onChange={(e) => setDraft(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') submit(item)
                    if (e.key === 'Escape') setReplying(null)
                  }}
                />
                <button className="btn btn-small" onClick={() => submit(item)}>
                  Send
                </button>
              </div>
            ) : (
              item.canReply && (
                <button
                  className="link-btn"
                  onClick={() => {
                    setReplying(item.key)
                    setDraft('')
                  }}
                >
                  Reply
                </button>
              )
            )}
          </div>
          <button className="icon-btn" title="Dismiss" onClick={() => onDismiss(item.key)}>
            ×
          </button>
        </li>
      ))}
    </ul>
  )
}

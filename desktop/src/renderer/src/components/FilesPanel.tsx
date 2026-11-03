import type { TransferItem } from '@shared/protocol'
import { Icon } from './Icon'

interface Props {
  transfers: TransferItem[]
  downloadsDir: string
  onSend: () => void
  onOpenFolder: () => void
}

export function bytes(value: number): string {
  if (value < 1024) return `${value} B`
  const units = ['KB', 'MB', 'GB']
  let size = value / 1024
  let unit = 0
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024
    unit += 1
  }
  return `${size.toFixed(size >= 10 ? 0 : 1)} ${units[unit]}`
}

export function FilesPanel({ transfers, downloadsDir, onSend, onOpenFolder }: Props): JSX.Element {
  return (
    <div className="files">
      <div className="dropzone" onClick={onSend}>
        <div className="empty-art"><Icon name="upload" size={30} /></div>
        <div>
          <strong>Send a file to your phone</strong>
          <p className="muted">Click to choose files — they land in your phone's Downloads.</p>
        </div>
      </div>

      <div className="files-head">
        <h2>Transfers</h2>
        <button className="link-btn" onClick={onOpenFolder}>
          Open {downloadsDir}
        </button>
      </div>

      {transfers.length === 0 ? (
        <p className="muted">Nothing transferred yet.</p>
      ) : (
        <ul className="transfer-list">
          {transfers.map((t) => {
            const pct = t.size > 0 ? Math.min(100, Math.round((t.received / t.size) * 100)) : 0
            return (
              <li className="transfer" key={t.id}>
                <div className="transfer-top">
                  <span className="transfer-name" title={t.name}>
                    {t.direction === 'in' ? '↓' : '↑'} {t.name}
                  </span>
                  <span className={`pill pill-${t.status}`}>{t.status}</span>
                </div>
                <div className="progress">
                  <div className="progress-fill" style={{ width: `${pct}%` }} />
                </div>
                <div className="transfer-meta muted">
                  {bytes(t.received)} / {bytes(t.size)}
                  {t.error ? ` · ${t.error}` : ''}
                </div>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}

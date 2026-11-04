import type { TransferItem } from '@shared/protocol'
import { Icon } from './Icon'

interface Props {
  transfers: TransferItem[]
  downloadsDir: string
  connected: boolean
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

export function FilesPanel({
  transfers,
  downloadsDir,
  connected,
  onSend,
  onOpenFolder
}: Props): JSX.Element {
  return (
    <div className="files">
      <button
        type="button"
        className="dropzone"
        onClick={onSend}
        disabled={!connected}
        aria-label="Choose files to send to your phone"
        title={connected ? 'Choose files to send' : 'Connect your phone to send files'}
      >
        <div className="empty-art"><Icon name="upload" size={30} /></div>
        <div>
          <strong>{connected ? 'Send a file to your phone' : 'Connect your phone to send files'}</strong>
          <p className="muted">
            {connected
              ? "Click to choose files — they land in your phone's Downloads."
              : 'LinkPhone needs an active connection before files can be sent.'}
          </p>
        </div>
      </button>

      <div className="files-head">
        <h2>Transfers</h2>
        <button className="link-btn" onClick={onOpenFolder} title={downloadsDir}>
          Open downloads folder
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

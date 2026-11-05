import type { StorageEntry, TransferItem } from '@shared/protocol'
import { Icon } from './Icon'

interface Props {
  transfers: TransferItem[]
  downloadsDir: string
  connected: boolean
  storageFiles: StorageEntry[]
  storageLoading: boolean
  storageError: string
  storageLimited: boolean
  storageGranted: boolean
  onSend: () => void
  onOpenFolder: () => void
  onRefreshStorage: () => void
  onPull: (entry: StorageEntry) => void
  onRetry: (id: string) => void
  onCancel: (id: string) => void
}

function statusLabel(item: TransferItem): string {
  switch (item.status) {
    case 'pending':
      return 'Waiting'
    case 'active':
      return item.direction === 'out' ? 'Sending' : 'Receiving'
    case 'interrupted':
      return 'Interrupted'
    case 'declined':
      return 'Declined'
    case 'canceled':
      return 'Canceled'
    case 'done':
      return 'Done'
    default:
      return 'Failed'
  }
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
  storageFiles,
  storageLoading,
  storageError,
  storageLimited,
  storageGranted,
  onSend,
  onOpenFolder,
  onRefreshStorage,
  onPull,
  onRetry,
  onCancel
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
                  <span className={`pill pill-${t.status}`}>{statusLabel(t)}</span>
                  {(t.status === 'pending' || t.status === 'active') && (
                    <button className="link-btn" onClick={() => onCancel(t.id)}>
                      Cancel
                    </button>
                  )}
                  {t.direction === 'out' &&
                    connected &&
                    (t.status === 'failed' || t.status === 'interrupted') && (
                      <button className="link-btn" onClick={() => onRetry(t.id)}>
                        Retry
                      </button>
                    )}
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

      <div className="files-head storage-head">
        <h2>On your phone</h2>
        <button className="link-btn" onClick={onRefreshStorage} disabled={storageLoading}>
          {storageLoading ? (
            'Reading…'
          ) : (
            <>
              <Icon name="refresh" size={18} /> Refresh
            </>
          )}
        </button>
      </div>
      <p className="muted storage-note">
        {storageLimited
          ? 'Android 13+ shows only files LinkPhone created. Share a file from the phone for anything else.'
          : 'Files LinkPhone can access in your phone Downloads.'}
      </p>
      {storageError ? (
        <p className="muted">{storageError}</p>
      ) : !storageGranted ? (
        <p className="muted">Allow storage access on the phone to list downloads.</p>
      ) : storageFiles.length === 0 ? (
        <p className="muted">No phone downloads found.</p>
      ) : (
        <ul className="storage-list">
          {storageFiles.map((entry) => (
            <li className="storage-row" key={entry.id}>
              <div className="storage-icon"><Icon name="description" size={20} /></div>
              <div className="storage-body">
                <div className="storage-name" title={entry.name}>{entry.name}</div>
                <div className="muted storage-meta">
                  {bytes(entry.size)}
                  {entry.modified ? ` · ${new Date(entry.modified).toLocaleDateString()}` : ''}
                </div>
              </div>
              <button className="link-btn" onClick={() => onPull(entry)}>
                <Icon name="download" size={18} /> Download
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

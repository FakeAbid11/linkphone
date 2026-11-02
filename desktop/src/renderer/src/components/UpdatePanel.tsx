import type { DesktopUpdateState } from '@shared/protocol'

interface Props {
  update: DesktopUpdateState
  onCheck: () => void
  onDownload: () => void
  onInstall: () => void
}

export function UpdatePanel({ update, onCheck, onDownload, onInstall }: Props): JSX.Element {
  const action = (): { label: string; run: () => void; disabled?: boolean } => {
    switch (update.status) {
      case 'available':
        return { label: `Download v${update.availableVersion ?? ''}`, run: onDownload }
      case 'downloading':
        return { label: `Downloading ${Math.round(update.progress ?? 0)}%`, run: () => {}, disabled: true }
      case 'ready':
        return { label: 'Restart & install', run: onInstall }
      case 'checking':
        return { label: 'Checking…', run: () => {}, disabled: true }
      default:
        return { label: 'Check for updates', run: onCheck }
    }
  }

  const next = action()
  const statusText = (() => {
    switch (update.status) {
      case 'available':
        return `LinkPhone v${update.availableVersion} is available.`
      case 'downloading':
        return 'Downloading the signed Windows installer from GitHub Releases.'
      case 'ready':
        return `v${update.availableVersion ?? 'Next'} is ready to install.`
      case 'up-to-date':
        return 'You have the latest version.'
      case 'error':
        return update.message || 'The update check failed.'
      case 'checking':
        return 'Checking GitHub Releases…'
      default:
        return 'LinkPhone checks GitHub Releases automatically every six hours.'
    }
  })()

  return (
    <div className="settings-panel">
      <div className="settings-card">
        <div className="settings-card-head">
          <div>
            <h2>Desktop updates</h2>
            <p className={update.status === 'error' ? 'update-error' : 'muted'}>{statusText}</p>
          </div>
          <span className="version-pill">v{update.currentVersion}</span>
        </div>

        {update.status === 'downloading' && (
          <div className="progress update-progress">
            <div
              className="progress-fill"
              style={{ width: `${Math.max(0, Math.min(100, update.progress ?? 0))}%` }}
            />
          </div>
        )}

        <button className="btn" onClick={next.run} disabled={next.disabled}>
          {next.label}
        </button>
      </div>

      <div className="settings-note">
        <strong>Privacy</strong>
        <p className="muted">
          Update checks contact only this project's public GitHub Releases page. They send no phone
          data, account information or telemetry.
        </p>
      </div>
    </div>
  )
}

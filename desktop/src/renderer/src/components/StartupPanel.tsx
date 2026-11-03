interface Props {
  supported: boolean
  enabled: boolean
  onToggle: (enabled: boolean) => void
}

export function StartupPanel({ supported, enabled, onToggle }: Props): JSX.Element {
  return (
    <div className="settings-card">
      <div className="settings-card-head">
        <div>
          <h2>Start with Windows</h2>
          <p className="muted">
            Launch LinkPhone hidden in the tray when you sign in. It appears in Windows Settings → Apps
            → Startup, and either place can switch it off.
          </p>
          {!supported && (
            <p className="muted">Only available in the installed Windows build.</p>
          )}
        </div>
        <button
          className={`switch ${enabled ? 'switch-on' : ''}`}
          onClick={() => onToggle(!enabled)}
          disabled={!supported}
          aria-pressed={enabled}
          aria-label="Start with Windows"
        >
          <span />
        </button>
      </div>
    </div>
  )
}

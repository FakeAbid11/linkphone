import type { DeviceInfo } from '@shared/protocol'

interface Props {
  devices: DeviceInfo[]
}

export function DevicePanel({ devices }: Props): JSX.Element {
  if (devices.length === 0) {
    return (
      <div className="device-card device-empty">
        <div className="device-icon">📱</div>
        <div>
          <div className="device-name">No phone paired</div>
          <div className="device-meta">Install the LinkPhone app and enter the code below</div>
        </div>
      </div>
    )
  }

  return (
    <div className="device-list">
      {devices.map((device) => (
        <div className="device-card" key={device.deviceId}>
          <div className="device-icon">📱</div>
          <div className="device-body">
            <div className="device-name">
              {device.name}
              <span className={`dot ${device.connected ? 'dot-on' : 'dot-off'}`} />
            </div>
            <div className="device-meta">
              {device.connected ? 'Connected' : 'Offline'}
              {device.address ? ` · ${device.address}` : ''}
            </div>
          </div>
          {device.paired && (
            <button
              className="icon-btn"
              title="Forget this phone"
              onClick={() => void window.linkphone.forgetDevice(device.deviceId)}
            >
              ×
            </button>
          )}
        </div>
      ))}
    </div>
  )
}

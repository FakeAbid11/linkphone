import { useEffect, useState } from 'react'
import type { DeviceInfo } from '@shared/protocol'
import { Icon } from './Icon'

interface Props {
  devices: DeviceInfo[]
}

export function DevicePanel({ devices }: Props): JSX.Element {
  const [confirmId, setConfirmId] = useState<string | null>(null)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editName, setEditName] = useState('')
  const [pending, setPending] = useState<{ id: string; name: string } | null>(null)
  const [editErrorId, setEditErrorId] = useState<string | null>(null)

  useEffect(() => {
    if (!pending) return
    const device = devices.find((candidate) => candidate.deviceId === pending.id)
    if (device && device.name === pending.name) {
      setPending(null)
      setEditErrorId(null)
    }
  }, [devices, pending])

  useEffect(() => {
    if (!pending) return
    const timer = setTimeout(() => {
      setPending(null)
      setEditErrorId(pending.id)
    }, 3000)
    return () => clearTimeout(timer)
  }, [pending])

  const startEdit = (device: DeviceInfo): void => {
    setEditingId(device.deviceId)
    setEditName(device.name)
    setEditErrorId(null)
  }

  const saveName = (device: DeviceInfo): void => {
    const name = editName.trim().slice(0, 32)
    if (!name) return
    void window.linkphone.renameDevice(device.deviceId, name)
    setPending({ id: device.deviceId, name })
    setEditingId(null)
  }

  if (devices.length === 0) {
    return (
      <div className="device-card device-empty">
        <div className="device-icon"><Icon name="smartphone" /></div>
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
          <div className="device-icon"><Icon name="smartphone" /></div>
          <div className="device-body">
            <div className="device-name">
              {editingId === device.deviceId ? (
                <div className="device-rename">
                  <input
                    autoFocus
                    value={editName}
                    maxLength={32}
                    aria-label="Phone name"
                    onChange={(event) => setEditName(event.target.value)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') saveName(device)
                      if (event.key === 'Escape') setEditingId(null)
                    }}
                  />
                  <button type="button" className="link-btn" onClick={() => saveName(device)}>
                    Save
                  </button>
                  <button
                    type="button"
                    className="link-btn"
                    onClick={() => setEditingId(null)}
                  >
                    Cancel
                  </button>
                </div>
              ) : (
                <>
                  {device.name}
                  <span className={`dot ${device.connected ? 'dot-on' : 'dot-off'}`} />
                  {device.connected && device.paired && (
                    <button
                      type="button"
                      className="icon-btn device-edit"
                      title="Rename phone"
                      aria-label={`Rename ${device.name}`}
                      onClick={() => startEdit(device)}
                    >
                      <Icon name="edit" size={16} />
                    </button>
                  )}
                </>
              )}
            </div>
            {pending?.id === device.deviceId && (
              <div className="device-meta">Saving name…</div>
            )}
            {editErrorId === device.deviceId && (
              <div className="device-meta">
                Update the LinkPhone app to rename from your PC.
              </div>
            )}
            <div className="device-meta">
              {device.connected ? 'Connected' : 'Offline'}
              {device.address ? ` · ${device.address}` : ''}
            </div>
            {device.connected && device.batteryLevel !== undefined && (
              <div className={`device-battery ${device.charging ? 'device-battery-charging' : ''}`}>
                <span className="battery-shell" aria-hidden="true">
                  <span
                    className="battery-fill"
                    style={{ width: `${Math.max(3, device.batteryLevel)}%` }}
                  />
                </span>
                <span>
                  {device.batteryLevel}%{device.charging ? ' · Charging' : ''}
                </span>
              </div>
            )}
          </div>
          {device.connected && (
            <button
              className="icon-btn"
              title={device.ringing ? 'Stop ringing' : 'Ring phone'}
              aria-label={
                device.ringing ? `Stop ringing ${device.name}` : `Ring ${device.name}`
              }
              onClick={() =>
                void (device.ringing
                  ? window.linkphone.stopRingPhone(device.deviceId)
                  : window.linkphone.ringPhone(device.deviceId))
              }
            >
              <Icon name={device.ringing ? 'stop' : 'volume_up'} />
            </button>
          )}
          {device.paired && confirmId !== device.deviceId && (
            <button
              className="icon-btn"
              title="Forget this phone"
              aria-label={`Forget ${device.name}`}
              onClick={() => setConfirmId(device.deviceId)}
            >
              <Icon name="close" />
            </button>
          )}
          {device.paired && confirmId === device.deviceId && (
            <div className="device-forget-confirm">
              <span>Forget this phone?</span>
              <button
                className="link-btn"
                type="button"
                onClick={() => {
                  void window.linkphone.forgetDevice(device.deviceId)
                  setConfirmId(null)
                }}
              >
                Forget
              </button>
              <button
                className="link-btn"
                type="button"
                onClick={() => setConfirmId(null)}
              >
                Cancel
              </button>
            </div>
          )}
        </div>
      ))}
    </div>
  )
}

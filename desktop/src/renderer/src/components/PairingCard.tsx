import { useEffect, useState } from 'react'
import QRCode from 'qrcode'
import { Icon } from './Icon'

interface Props {
  code: string
  serverId: string
  serverName: string
  port: number
  addresses: string[]
  addresses6: string[]
  onNewCode: () => void
}

function grouped(code: string): string {
  if (code.length !== 8) return code
  return `${code.slice(0, 4)} ${code.slice(4)}`
}

export function pairPayload(input: {
  code: string
  port: number
  addresses: string[]
  addresses6: string[]
  serverName: string
  serverId: string
}): string {
  const query = [
    `c=${encodeURIComponent(input.code)}`,
    `p=${input.port}`,
    `a=${encodeURIComponent(input.addresses.join(','))}`,
    ...(input.addresses6.length > 0
      ? [`a6=${encodeURIComponent(input.addresses6.join(','))}`]
      : []),
    `n=${encodeURIComponent(input.serverName)}`,
    `i=${encodeURIComponent(input.serverId)}`,
    `v=1`
  ].join('&')
  return `linkphone://pair?${query}`
}

export function PairingCard({
  code,
  serverId,
  serverName,
  port,
  addresses,
  addresses6,
  onNewCode
}: Props): JSX.Element {
  const [copied, setCopied] = useState(false)
  const [showQr, setShowQr] = useState(false)
  const [qr, setQr] = useState('')

  const addressKey = addresses.join(',')
  const address6Key = addresses6.join(',')

  useEffect(() => {
    if (!showQr) return
    let alive = true
    const text = pairPayload({
      code,
      port,
      addresses: addressKey ? addressKey.split(',') : [],
      addresses6: address6Key ? address6Key.split(',') : [],
      serverName,
      serverId
    })
    QRCode.toDataURL(text, {
      margin: 1,
      width: 240,
      errorCorrectionLevel: 'M',
      color: { dark: '#000000', light: '#ffffff' }
    })
      .then((url) => {
        if (alive) setQr(url)
      })
      .catch(() => {
        if (alive) setQr('')
      })
    return () => {
      alive = false
    }
  }, [showQr, code, port, addressKey, address6Key, serverName, serverId])

  const toggleQr = (): void => {
    const next = !showQr
    setShowQr(next)
    if (next) void window.linkphone.refreshAddresses()
  }

  const copy = (): void => {
    void window.linkphone.copyToClipboard(code)
    setCopied(true)
    setTimeout(() => setCopied(false), 1400)
  }

  return (
    <div className="pairing">
      <div className="pairing-label">Pairing code</div>
      <button className="pairing-code" onClick={copy} title="Copy pairing code">
        {grouped(code)}
      </button>
      <div className="pairing-hint">{copied ? 'Copied' : 'Type this on your phone'}</div>

      <button className="link-btn qr-toggle" onClick={toggleQr}>
        <Icon name="qr_code_2" size={18} />
        {showQr ? 'Hide QR code' : 'Scan QR code instead'}
      </button>

      {showQr && (
        <div className="qr-wrap">
          {qr ? (
            <img src={qr} alt="Pairing QR code" width={168} height={168} />
          ) : (
            <div className="qr-loading">Generating…</div>
          )}
          <div className="pairing-hint">LinkPhone app → Scan QR</div>
        </div>
      )}

      <div className="pairing-net">
        <div className="net-row">
          <span className="muted">This PC</span>
          <span>{serverName}</span>
        </div>
        <div className="net-row">
          <span className="muted">Port</span>
          <span>{port}</span>
        </div>
        <div className="net-row">
          <span className="muted">Address</span>
          <span>{addresses[0] ?? '—'}</span>
        </div>
      </div>

      <button className="link-btn" onClick={onNewCode}>
        <Icon name="refresh" size={18} /> Generate a new code
      </button>
    </div>
  )
}

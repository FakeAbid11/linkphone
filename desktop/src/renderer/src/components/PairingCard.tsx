import { useState } from 'react'

interface Props {
  code: string
  serverName: string
  port: number
  addresses: string[]
  onNewCode: () => void
}

function grouped(code: string): string {
  if (code.length !== 8) return code
  return `${code.slice(0, 4)} ${code.slice(4)}`
}

export function PairingCard({ code, serverName, port, addresses, onNewCode }: Props): JSX.Element {
  const [copied, setCopied] = useState(false)

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
        Generate a new code
      </button>
    </div>
  )
}

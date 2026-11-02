import os from 'node:os'
import { Bonjour } from 'bonjour-service'
import { PROTO_VERSION, SERVICE_TYPE } from '../shared/protocol'

export interface Advertisement {
  stop: () => void
}

interface BonjourService {
  on: (event: string, cb: (err: Error) => void) => void
  stop: (cb?: () => void) => void
}

export function localAddresses(): string[] {
  const out: string[] = []
  for (const list of Object.values(os.networkInterfaces())) {
    for (const iface of list ?? []) {
      if (iface.family === 'IPv4' && !iface.internal) out.push(iface.address)
    }
  }
  return out
}

/**
 * Advertises _linkphone._tcp via mDNS so the Android app can find this PC
 * without the user typing an IP.
 */
export function advertise(input: {
  serverId: string
  serverName: string
  port: number
  onError?: (err: Error) => void
}): Advertisement {
  const bonjour = new Bonjour()
  let service: BonjourService | null = null

  try {
    const published = bonjour.publish({
      name: `LinkPhone-${input.serverId.slice(0, 8)}`,
      type: SERVICE_TYPE,
      port: input.port,
      txt: {
        id: input.serverId,
        name: input.serverName,
        ver: String(PROTO_VERSION)
      }
    }) as unknown as BonjourService

    published.on('error', (err: Error) => input.onError?.(err))
    service = published
  } catch (err) {
    input.onError?.(err as Error)
  }

  return {
    stop: () => {
      try {
        service?.stop()
      } catch {
        /* ignore */
      }
      try {
        bonjour.destroy()
      } catch {
        /* ignore */
      }
    }
  }
}

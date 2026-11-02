import os from 'node:os'
import { Bonjour } from 'bonjour-service'
import { PROTO_VERSION, SERVICE_TYPE } from '../shared/protocol'

export interface Advertisement {
  stop: () => void
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
  let service: { stop: (cb?: () => void) => void; on: (event: string, cb: (err: Error) => void) => void } | null = null

  try {
    service = bonjour.publish({
      name: `LinkPhone-${input.serverId.slice(0, 8)}`,
      type: SERVICE_TYPE,
      port: input.port,
      txt: {
        id: input.serverId,
        name: input.serverName,
        ver: String(PROTO_VERSION)
      }
    }) as unknown as typeof service
    service?.on('error', (err: Error) => input.onError?.(err))
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

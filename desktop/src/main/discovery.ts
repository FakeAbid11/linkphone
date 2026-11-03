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

function privateV4Rank(address: string): number {
  if (address.startsWith('192.168.')) return 0
  if (address.startsWith('10.')) return 1
  if (address.startsWith('172.')) {
    const second = Number(address.split('.')[1])
    if (second >= 16 && second <= 31) return 2
  }
  return 9
}

/**
 * Link-local IPv6 addresses for interfaces that also hold a private IPv4
 * address. Many routers isolate IPv4 clients but still forward link-local
 * IPv6, so the phone can pair over fe80:: when ws://<ipv4> is unreachable.
 */
export function localLinkLocalIPv6(): string[] {
  const groups = Object.values(os.networkInterfaces())
    .map((list) => list ?? [])
    .filter((list) =>
      list.some(
        (iface) => iface.family === 'IPv4' && !iface.internal && privateV4Rank(iface.address) < 9
      )
    )
    .sort((a, b) => {
      const rank = (list: typeof a): number =>
        Math.min(
          ...list
            .filter((iface) => iface.family === 'IPv4' && !iface.internal)
            .map((iface) => privateV4Rank(iface.address)),
          9
        )
      return rank(a) - rank(b)
    })

  const out: string[] = []
  for (const list of groups) {
    for (const iface of list) {
      if (iface.family !== 'IPv6' || iface.internal) continue
      const address = iface.address.split('%')[0]
      if (/^fe80:/i.test(address)) out.push(address)
    }
  }
  return [...new Set(out)]
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

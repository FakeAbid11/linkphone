import type { LinkPhoneApi } from '../../shared/api'

declare global {
  interface Window {
    linkphone: LinkPhoneApi
  }
}

export {}

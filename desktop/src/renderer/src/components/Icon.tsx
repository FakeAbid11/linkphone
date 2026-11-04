const GLYPHS = {
  arrow_back: '\ue5c4',
  arrow_forward: '\ue5c8',
  backspace: '\ue14a',
  battery_charging_full: '\ue1a3',
  battery_full: '\ue1a5',
  call: '\uf0d4',
  call_end: '\uf0bc',
  chat: '\ue0c9',
  check: '\ue668',
  check_circle: '\uf0be',
  chevron_right: '\ue5cc',
  close: '\ue5cd',
  cloud_download: '\ue2c0',
  contacts: '\ue0ba',
  content_copy: '\ue14d',
  content_paste: '\ue14f',
  delete_sweep: '\ue16c',
  description: '\ue873',
  download: '\uf090',
  error: '\uf8b6',
  expand_more: '\ue5cf',
  folder: '\ue2c7',
  image: '\ue3f4',
  info: '\ue88e',
  install_desktop: '\ueb71',
  link: '\ue250',
  link_off: '\ue16f',
  more_vert: '\ue5d4',
  notifications: '\ue7f5',
  pause: '\ue034',
  play_arrow: '\ue037',
  skip_next: '\ue044',
  skip_previous: '\ue045',
  photo_library: '\ue413',
  qr_code_2: '\ue00a',
  qr_code_scanner: '\uf206',
  radio_button_checked: '\ue837',
  radio_button_unchecked: '\ue836',
  refresh: '\ue5d5',
  restart_alt: '\uf053',
  search: '\uef7a',
  send: '\ue163',
  settings: '\ue8b8',
  smartphone: '\ue7ba',
  sms: '\ue625',
  stop: '\ue047',
  sync: '\ue627',
  system_update: '\uf2cd',
  upload: '\uf09b',
  volume_off: '\ue04f',
  volume_up: '\ue050',
  warning: '\uf083',
  wifi: '\ue63e'
} as const

export type IconName = keyof typeof GLYPHS

export function Icon({
  name,
  size = 20,
  className = ''
}: {
  name: IconName
  size?: number
  className?: string
}): JSX.Element {
  return (
    <span
      className={`material-symbols-rounded ${className}`}
      style={{ fontSize: `${size}px` }}
      aria-hidden="true"
    >
      {GLYPHS[name]}
    </span>
  )
}

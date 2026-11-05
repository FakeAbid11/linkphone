const NAME_LIMIT = 32

export function sanitizeDeviceName(value: string): string {
  return value
    .replace(/[\u0000-\u001F\u007F\u200B-\u200D\uFEFF]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, NAME_LIMIT)
}

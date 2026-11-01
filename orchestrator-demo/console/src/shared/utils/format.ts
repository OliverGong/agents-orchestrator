/** Shared display formatters (ported from the legacy console app.js). */

/** Time-of-day with milliseconds for intra-run stage ordering. */
export function fmtTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return (
    d.toLocaleTimeString('zh-CN', { hour12: false }) +
    '.' +
    String(d.getMilliseconds()).padStart(3, '0')
  )
}

/** Full date-time for durable history rows (instances, logs, LM metrics). */
export function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return d.toLocaleDateString('zh-CN') + ' ' + d.toLocaleTimeString('zh-CN', { hour12: false })
}

/** Compact number: one decimal for large averages, integers unchanged. */
export function fmtNum(v: number | null | undefined): string {
  if (v == null) return '—'
  const n = Number(v)
  return n >= 1000 ? n.toFixed(0) : Number.isInteger(n) ? String(v) : n.toFixed(1)
}

/** Pretty-print JSON, falling back to the raw string when unparseable. */
export function prettyJson(text: string | null | undefined): string {
  if (!text) return ''
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

export function tryParseJson<T = unknown>(text: string | null | undefined): T | string | null {
  if (!text) return null
  try {
    return JSON.parse(text) as T
  } catch {
    return text
  }
}

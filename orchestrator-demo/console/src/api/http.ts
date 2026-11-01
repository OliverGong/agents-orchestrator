/**
 * Typed HTTP client — same-origin against the Spring Boot app (dev: vite
 * proxy, prod: nginx reverse proxy). Structured backend errors
 * ({error, message}) are surfaced as ApiError so views can render them.
 */

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

async function parseError(res: Response): Promise<ApiError> {
  try {
    const err = (await res.json()) as { error?: string; message?: string }
    return new ApiError(res.status, err?.message || `${err?.error || 'HTTP'} ${res.status}`)
  } catch {
    return new ApiError(res.status, `HTTP ${res.status}`)
  }
}

async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  const res = await fetch(url, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) throw await parseError(res)
  return (await res.json()) as T
}

export const http = {
  get: <T>(url: string) => request<T>('GET', url),
  post: <T>(url: string, body: unknown = {}) => request<T>('POST', url, body),
  put: <T>(url: string, body: unknown) => request<T>('PUT', url, body),
  del: <T>(url: string) => request<T>('DELETE', url),
}

/** Build a query string from entries whose value is a non-empty string. */
export function qs(params: Record<string, string | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value) search.set(key, value)
  }
  const s = search.toString()
  return s ? `?${s}` : ''
}

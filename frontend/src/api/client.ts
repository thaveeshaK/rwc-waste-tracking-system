export class ApiError extends Error {
  readonly status: number
  constructor(message: string, status: number) {
    super(message)
    this.status = status
  }
}

async function readResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const problem = await response.json().catch(() => null) as { detail?: string } | null
    throw new ApiError(problem?.detail ?? `Request failed (${response.status}).`, response.status)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if ((init.method ?? 'GET') !== 'GET') {
    const csrf = await readResponse<{ headerName: string; token: string }>(
      await fetch('/api/auth/csrf', { credentials: 'same-origin', cache: 'no-store' }),
    )
    headers.set(csrf.headerName, csrf.token)
  }
  return readResponse<T>(await fetch(path, { ...init, headers, credentials: 'same-origin', cache: 'no-store' }))
}

export function jsonRequest<T>(path: string, method: string, body: unknown): Promise<T> {
  return request<T>(path, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'The request could not be completed. Please try again.'
}

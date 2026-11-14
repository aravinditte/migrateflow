// Thin API client for the MigrateFlow backend.
// Session auth is cookie-based; state-changing calls echo the XSRF-TOKEN
// cookie in the X-XSRF-TOKEN header (Spring Security SPA CSRF pattern).

export interface ApiError extends Error {
  status: number;
  code: string;
}

function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return match ? decodeURIComponent(match[1]) : null;
}

export async function ensureCsrf(): Promise<void> {
  await fetch('/api/v1/auth/csrf', { credentials: 'include' });
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase();
  const headers = new Headers(init.headers);
  if (!['GET', 'HEAD'].includes(method)) {
    if (!readCookie('XSRF-TOKEN')) await ensureCsrf();
    const token = readCookie('XSRF-TOKEN');
    if (token) headers.set('X-XSRF-TOKEN', token);
  }
  if (init.body && typeof init.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const response = await fetch(path, { ...init, headers, credentials: 'include' });
  if (!response.ok) {
    let code = 'ERROR';
    let detail = response.statusText;
    try {
      const problem = (await response.json()) as { code?: string; detail?: string; title?: string };
      code = problem.code ?? code;
      detail = problem.detail ?? problem.title ?? detail;
    } catch {
      /* non-JSON error body */
    }
    const error = new Error(detail) as ApiError;
    error.status = response.status;
    error.code = code;
    throw error;
  }
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown, headers?: Record<string, string>) =>
    request<T>(path, {
      method: 'POST',
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
  put: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) }),
  patch: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'PATCH', body: body === undefined ? undefined : JSON.stringify(body) }),
  postForm: <T>(path: string, form: FormData) => request<T>(path, { method: 'POST', body: form }),
};

export interface SessionUser {
  id: string;
  email: string;
  displayName: string;
  role: string;
  projects: { id: string; name: string; status: string }[];
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
}

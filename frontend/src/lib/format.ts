export function formatYen(amount: number): string {
  return '¥' + amount.toLocaleString('en-US');
}

export function shortHash(hash: string | null | undefined): string {
  if (!hash) return '';
  return hash.length > 12 ? hash.slice(0, 12) : hash;
}

export function formatInstant(iso: string | null | undefined): string {
  if (!iso) return '';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toISOString().replace('T', ' ').slice(0, 19) + ' UTC';
}

export class TimeoutError extends Error {
  constructor(message = 'Polling timed out before completion') {
    super(message);
    this.name = 'TimeoutError';
  }
}

/** Polls an async getter until the predicate holds or attempts run out. */
export async function pollUntil<T>(
  fn: () => Promise<T>,
  done: (value: T) => boolean,
  attempts = 60,
  delayMs = 1000,
  sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)),
): Promise<T> {
  let last = await fn();
  for (let i = 1; i < attempts && !done(last); i++) {
    await sleep(delayMs);
    last = await fn();
  }
  if (!done(last)) {
    throw new TimeoutError(`Operation did not complete within ${attempts} attempts`);
  }
  return last;
}

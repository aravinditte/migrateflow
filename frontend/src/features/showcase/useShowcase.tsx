import { useEffect, useState } from 'react';

export type Load<T> =
  | { state: 'loading' }
  | { state: 'error'; message: string }
  | { state: 'ready'; data: T };

/** Loads one public showcase section; re-runs when `deps` change. */
export function useShowcase<T>(load: () => Promise<T>, deps: unknown[]): Load<T> {
  const [value, setValue] = useState<Load<T>>({ state: 'loading' });
  useEffect(() => {
    let cancelled = false;
    setValue({ state: 'loading' });
    load()
      .then((data) => { if (!cancelled) setValue({ state: 'ready', data }); })
      .catch((e) => { if (!cancelled) setValue({ state: 'error', message: e instanceof Error ? e.message : 'unavailable' }); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return value;
}

export function SectionState<T>({ load, children }: { load: Load<T>; children: (data: T) => React.ReactNode }) {
  if (load.state === 'loading') return <p className="muted">Loading live evidence…</p>;
  if (load.state === 'error') {
    return <p className="error">Live evidence temporarily unavailable ({load.message}). No cached or placeholder results are shown.</p>;
  }
  return <>{children(load.data)}</>;
}

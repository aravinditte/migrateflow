import { describe, expect, it } from 'vitest';
import { formatYen, pollUntil, shortHash } from './format';

describe('format helpers', () => {
  it('formats yen with grouping', () => {
    expect(formatYen(21998650)).toBe('¥21,998,650');
    expect(formatYen(0)).toBe('¥0');
  });

  it('shortens hashes to fingerprints', () => {
    expect(shortHash('abcdef0123456789')).toBe('abcdef012345');
    expect(shortHash('abc')).toBe('abc');
    expect(shortHash(null)).toBe('');
  });

  it('pollUntil stops when the predicate holds', async () => {
    let calls = 0;
    const result = await pollUntil(
      async () => ++calls,
      (v) => v >= 3,
      10,
      0,
      async () => undefined,
    );
    expect(result).toBe(3);
    expect(calls).toBe(3);
  });
});

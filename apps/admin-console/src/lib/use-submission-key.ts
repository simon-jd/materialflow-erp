import { useCallback, useRef } from "react";

/**
 * One Idempotency-Key per submission attempt. Re-submitting identical values (after a timeout or a
 * 5xx) reuses the key, so the server replays the first result instead of creating a duplicate; any
 * change to the values starts a new attempt with a new key.
 */
export function useSubmissionKey() {
  const attempt = useRef<{ fingerprint: string; key: string } | null>(null);

  const keyFor = useCallback((payload: unknown): string => {
    const fingerprint = JSON.stringify(payload);
    if (attempt.current?.fingerprint !== fingerprint) {
      attempt.current = { fingerprint, key: crypto.randomUUID() };
    }
    return attempt.current.key;
  }, []);

  const reset = useCallback(() => {
    attempt.current = null;
  }, []);

  return { keyFor, reset };
}

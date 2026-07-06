// Tracks backend reachability passively from the requests the app already
// makes — no polling. A resolved request marks reachable; a non-abort
// connection failure marks unreachable; it flips back on the next success.

export type ServerStatus = "online" | "offline";

let reachable = true;
const listeners = new Set<(s: ServerStatus) => void>();

function emit() {
  const s = currentServerStatus();
  for (const fn of listeners) fn(s);
}

export function currentServerStatus(): ServerStatus {
  return reachable ? "online" : "offline";
}

export function markServerReachable() {
  if (!reachable) {
    reachable = true;
    emit();
  }
}

export function markServerUnreachable() {
  if (reachable) {
    reachable = false;
    emit();
  }
}

export function subscribeServerStatus(fn: (s: ServerStatus) => void): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

let probing = false;

// One-shot reachability check for a discrete event (e.g. regaining network).
// Concurrent calls collapse into one in-flight probe.
export async function probeServer(): Promise<void> {
  if (probing) return;
  probing = true;
  try {
    await fetch("/api/locales", { method: "GET", cache: "no-store" });
    markServerReachable();
  } catch {
    markServerUnreachable();
  } finally {
    probing = false;
  }
}

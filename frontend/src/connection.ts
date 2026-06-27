// Tracks whether the backend is reachable, derived passively from the
// outcome of the requests the app already makes — no polling. A request that
// resolves marks the server reachable; a request that fails to connect (the
// fetch promise rejects with something other than an abort) marks it
// unreachable. The status flips back to reachable on the next successful call.

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

// One-shot reachability check. Not polling — call it on a discrete event (e.g.
// the browser regaining network) to re-verify the server without waiting for
// the user's next action. Concurrent calls collapse into one in-flight probe.
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

import { useEffect, useState } from "react";
import {
  type ServerStatus,
  currentServerStatus,
  probeServer,
  subscribeServerStatus,
} from "./connection";

export interface ConnectionState {
  // Browser network status (navigator.onLine + online/offline events).
  online: boolean;
  // Backend reachability, inferred from real requests (see connection.ts).
  server: ServerStatus;
}

// Combines two no-polling signals: the browser's online/offline events and the
// pass/fail outcome of requests the app already makes.
export function useConnectionStatus(): ConnectionState {
  const [online, setOnline] = useState(() => navigator.onLine);
  const [server, setServer] = useState<ServerStatus>(currentServerStatus);

  useEffect(() => {
    const goOnline = () => {
      setOnline(true);
      // Network is back — re-verify the server once rather than wait for the next action.
      void probeServer();
    };
    const goOffline = () => setOnline(false);
    window.addEventListener("online", goOnline);
    window.addEventListener("offline", goOffline);
    return () => {
      window.removeEventListener("online", goOnline);
      window.removeEventListener("offline", goOffline);
    };
  }, []);

  useEffect(() => subscribeServerStatus(setServer), []);

  return { online, server };
}

import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, eventSource } from "./api";
import type { Machine, Snapshot } from "./types";

export interface FleetState {
  snapshots: Snapshot[];
  at: number;
  connected: boolean;
  refresh: () => Promise<void>;
  loading: boolean;
}

/** Live fleet overview: initial fetch + SSE updates (reconnects automatically). */
export function useFleet(): FleetState {
  const [snapshots, setSnapshots] = useState<Snapshot[]>([]);
  const [at, setAt] = useState(0);
  const [connected, setConnected] = useState(false);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let es: EventSource | null = null;
    let stopped = false;
    let retry = 1000;
    const connect = () => {
      if (stopped) return;
      es = eventSource("/api/fleet/stream");
      es.addEventListener("overview", (e) => {
        const d = JSON.parse((e as MessageEvent).data) as { machines: Snapshot[]; at: number };
        setSnapshots(d.machines);
        setAt(d.at);
        setLoading(false);
        setConnected(true);
        retry = 1000;
      });
      es.addEventListener("machine", (e) => {
        const snap = JSON.parse((e as MessageEvent).data) as Snapshot;
        setSnapshots((prev) => {
          const i = prev.findIndex((s) => s.machine.id === snap.machine.id);
          if (i < 0) return [...prev, snap];
          const next = prev.slice();
          next[i] = snap;
          return next;
        });
        setAt(Date.now());
      });
      es.onerror = () => {
        setConnected(false);
        es?.close();
        setTimeout(connect, retry);
        retry = Math.min(retry * 2, 15000);
      };
    };
    connect();
    return () => {
      stopped = true;
      es?.close();
    };
  }, []);

  const refresh = async () => {
    const d = await api<{ machines: Snapshot[]; at: number }>("POST", "/api/fleet/refresh");
    setSnapshots(d.machines);
    setAt(d.at);
  };
  return useMemo(() => ({ snapshots, at, connected, refresh, loading }), [snapshots, at, connected, loading]);
}

export function useMachines() {
  return useQuery({ queryKey: ["machines"], queryFn: async () => (await api<{ machines: Machine[] }>("GET", "/api/machines")).machines });
}

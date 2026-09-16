import type { DashboardStatus, SystemStats, UpdateCheck } from "@fleet/hermes-client";
import type { MachineRepo, Machine } from "./machines.js";

export interface MachineSnapshot {
  machine: Machine;
  online: boolean;
  checkedAt: number;
  latencyMs?: number;
  version?: string;
  dashboard?: { ok: boolean; error?: string; status?: DashboardStatus; stats?: SystemStats; authRequired?: boolean };
  api?: { ok: boolean; error?: string; version?: string; status?: string; activeRuns?: number };
  update?: UpdateCheck & { checkedAt: number };
  alerts: { level: "warn" | "error"; code: string; message: string }[];
}

type Listener = (snap: MachineSnapshot) => void;

/**
 * Periodically probes every registered machine and keeps the latest snapshot in memory.
 * Update checks are cached longer than liveness (they hit GitHub on the Hermes side).
 */
export class FleetPoller {
  private snapshots = new Map<string, MachineSnapshot>();
  private listeners = new Set<Listener>();
  private timer: NodeJS.Timeout | null = null;
  private inFlight = new Set<string>();
  private updateCache = new Map<string, UpdateCheck & { checkedAt: number }>();
  private fastUntil = 0;

  constructor(
    private readonly repo: MachineRepo,
    private readonly intervalMs: number,
    private readonly updateIntervalMs = 6 * 3600_000,
  ) {}

  start(): void {
    if (this.timer) return;
    const loop = async () => {
      await this.pollAll();
      const interval = Date.now() < this.fastUntil ? Math.min(this.intervalMs, 10_000) : this.intervalMs;
      this.timer = setTimeout(loop, interval);
    };
    void loop();
  }

  stop(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }

  /** UI is open: poll faster for the next 60s (called on every overview request / stream connect). */
  touch(): void {
    this.fastUntil = Date.now() + 60_000;
  }

  subscribe(fn: Listener): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  all(): MachineSnapshot[] {
    const machines = this.repo.list();
    return machines.map((m) => this.snapshots.get(m.id) ?? emptySnapshot(m));
  }

  get(id: string): MachineSnapshot | null {
    return this.snapshots.get(id) ?? null;
  }

  async pollAll(): Promise<void> {
    const machines = this.repo.list();
    const live = new Set(machines.map((m) => m.id));
    for (const id of this.snapshots.keys()) if (!live.has(id)) this.snapshots.delete(id);
    await Promise.all(machines.map((m) => this.poll(m.id)));
  }

  async poll(id: string, opts: { forceUpdateCheck?: boolean } = {}): Promise<MachineSnapshot | null> {
    if (this.inFlight.has(id)) return this.snapshots.get(id) ?? null;
    this.inFlight.add(id);
    try {
      const c = this.repo.clients(id);
      if (!c) return null;
      const snap = emptySnapshot(c.machine);
      const t0 = Date.now();
      const [d, a] = await Promise.all([
        c.dashboard
          ? (async () => {
              try {
                const status = await c.dashboard!.status();
                let stats: SystemStats | undefined;
                let error: string | undefined;
                try {
                  stats = await c.dashboard!.systemStats();
                } catch (e) {
                  error = errMsg(e);
                }
                return { ok: !error, error, status, stats, authRequired: status.auth_required };
              } catch (e) {
                return { ok: false, error: errMsg(e) };
              }
            })()
          : Promise.resolve(undefined),
        c.api
          ? (async () => {
              try {
                await c.api!.health();
                try {
                  const det = await c.api!.healthDetailed();
                  const readiness = det.readiness as { active_api_runs?: number } | undefined;
                  return { ok: true, version: det.version, status: det.status, activeRuns: readiness?.active_api_runs };
                } catch (e) {
                  return { ok: false, error: errMsg(e) };
                }
              } catch (e) {
                return { ok: false, error: errMsg(e) };
              }
            })()
          : Promise.resolve(undefined),
      ]);
      snap.latencyMs = Date.now() - t0;
      snap.dashboard = d;
      snap.api = a;
      snap.online = Boolean(d?.status) || Boolean(a?.ok);
      snap.version = d?.status?.version ?? a?.version;

      // Update check: cached per machine, refreshed every updateIntervalMs (or forced).
      if (c.dashboard && d?.ok) {
        const cached = this.updateCache.get(id);
        if (opts.forceUpdateCheck || !cached || Date.now() - cached.checkedAt > this.updateIntervalMs) {
          try {
            const u = await c.dashboard.updateCheck(Boolean(opts.forceUpdateCheck));
            this.updateCache.set(id, { ...u, checkedAt: Date.now() });
          } catch {
            // keep old value
          }
        }
        snap.update = this.updateCache.get(id);
      }
      snap.alerts = deriveAlerts(snap);
      this.snapshots.set(id, snap);
      for (const l of this.listeners) l(snap);
      return snap;
    } finally {
      this.inFlight.delete(id);
    }
  }

  invalidateUpdate(id: string): void {
    this.updateCache.delete(id);
  }
}

function emptySnapshot(machine: Machine): MachineSnapshot {
  return { machine, online: false, checkedAt: Date.now(), alerts: [] };
}

function errMsg(e: unknown): string {
  if (e instanceof Error) {
    const cause = (e as Error & { cause?: { code?: string } }).cause;
    if (cause?.code) return cause.code;
    return e.message;
  }
  return String(e);
}

function deriveAlerts(s: MachineSnapshot): MachineSnapshot["alerts"] {
  const out: MachineSnapshot["alerts"] = [];
  if (!s.online) {
    out.push({ level: "error", code: "offline", message: "到達できません" });
    return out;
  }
  if (s.dashboard && !s.dashboard.ok) out.push({ level: "warn", code: "dashboard_auth", message: `ダッシュボード: ${s.dashboard.error ?? "認証エラー"}` });
  if (s.api && !s.api.ok) out.push({ level: "warn", code: "api_auth", message: `API サーバー: ${s.api.error ?? "認証エラー"}` });
  const st = s.dashboard?.status;
  if (st && st.gateway_running === false) out.push({ level: "warn", code: "gateway_stopped", message: `ゲートウェイ停止中 (${st.gateway_state ?? "stopped"})` });
  const disk = s.dashboard?.stats?.disk?.percent;
  if (typeof disk === "number" && disk >= 90) out.push({ level: "warn", code: "disk", message: `ディスク使用率 ${disk}%` });
  const mem = s.dashboard?.stats?.memory?.percent;
  if (typeof mem === "number" && mem >= 92) out.push({ level: "warn", code: "memory", message: `メモリ使用率 ${mem}%` });
  if (s.update?.update_available) out.push({ level: "warn", code: "update", message: `更新あり (${s.update.behind ?? "?"} commits behind)` });
  if (st?.overall === "degraded") out.push({ level: "warn", code: "degraded", message: "コンポーネントが degraded" });
  return out;
}

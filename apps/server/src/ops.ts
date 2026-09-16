import { randomUUID } from "node:crypto";
import type { DashboardClient } from "@fleet/hermes-client";
import type { MachineRepo } from "./machines.js";
import type { AuditLog } from "./audit.js";
import type { FleetPoller } from "./fleet.js";

export type OpsKind = "gateway.start" | "gateway.stop" | "gateway.restart" | "update" | "doctor" | "security-audit" | "backup";

export interface OpsMachineState {
  machineId: string;
  machineName: string;
  status: "pending" | "running" | "ok" | "failed" | "skipped";
  message: string;
  lines: string[];
  startedAt?: number;
  finishedAt?: number;
}

export interface OpsJob {
  id: string;
  kind: OpsKind;
  canary: boolean;
  status: "running" | "ok" | "failed" | "partial";
  machines: OpsMachineState[];
  createdAt: number;
  finishedAt?: number;
}

type Listener = (job: OpsJob) => void;

const ACTION_NAME: Record<OpsKind, string | null> = {
  "gateway.start": null,
  "gateway.stop": null,
  "gateway.restart": "gateway-restart",
  update: "hermes-update",
  doctor: "doctor",
  "security-audit": "security-audit",
  backup: "backup",
};

/**
 * Batch operations across machines. `update` runs canary-first by default: one machine must
 * finish successfully before the rest start. Progress is pushed to subscribers.
 */
export class OpsManager {
  private jobs = new Map<string, OpsJob>();
  private listeners = new Map<string, Set<Listener>>();

  constructor(
    private readonly repo: MachineRepo,
    private readonly audit: AuditLog,
    private readonly poller: FleetPoller,
  ) {}

  list(): OpsJob[] {
    return [...this.jobs.values()].sort((a, b) => b.createdAt - a.createdAt).slice(0, 50);
  }

  get(id: string): OpsJob | null {
    return this.jobs.get(id) ?? null;
  }

  subscribe(id: string, fn: Listener): () => void {
    let set = this.listeners.get(id);
    if (!set) this.listeners.set(id, (set = new Set()));
    set.add(fn);
    return () => set!.delete(fn);
  }

  start(kind: OpsKind, machineIds: string[], opts: { canary?: boolean } = {}): OpsJob {
    const machines: OpsMachineState[] = [];
    for (const id of machineIds) {
      const m = this.repo.get(id);
      if (m) machines.push({ machineId: id, machineName: m.name, status: "pending", message: "", lines: [] });
    }
    if (machines.length === 0) throw new Error("no valid machines");
    const job: OpsJob = { id: randomUUID(), kind, canary: opts.canary ?? kind === "update", status: "running", machines, createdAt: Date.now() };
    this.jobs.set(job.id, job);
    this.audit.record(`ops.${kind}`, { detail: { jobId: job.id, machines: machines.map((m) => m.machineName), canary: job.canary } });
    void this.run(job);
    return job;
  }

  private emit(job: OpsJob): void {
    for (const l of this.listeners.get(job.id) ?? []) l(job);
  }

  private async run(job: OpsJob): Promise<void> {
    const targets = job.machines;
    if (job.canary && targets.length > 1) {
      const [first, ...rest] = targets;
      await this.runOne(job, first);
      if (first.status !== "ok") {
        for (const m of rest) {
          m.status = "skipped";
          m.message = `カナリア (${first.machineName}) が失敗したため中止`;
        }
      } else {
        await Promise.all(rest.map((m) => this.runOne(job, m)));
      }
    } else {
      await Promise.all(targets.map((m) => this.runOne(job, m)));
    }
    const ok = targets.filter((m) => m.status === "ok").length;
    job.status = ok === targets.length ? "ok" : ok === 0 ? "failed" : "partial";
    job.finishedAt = Date.now();
    this.audit.record(`ops.${job.kind}.done`, { detail: { jobId: job.id, status: job.status, results: targets.map((m) => `${m.machineName}:${m.status}`) }, ok: job.status !== "failed" });
    this.emit(job);
    setTimeout(() => this.listeners.delete(job.id), 60_000).unref();
  }

  private async runOne(job: OpsJob, m: OpsMachineState): Promise<void> {
    m.status = "running";
    m.startedAt = Date.now();
    this.emit(job);
    const c = this.repo.clients(m.machineId);
    if (!c?.dashboard) {
      m.status = "failed";
      m.message = "ダッシュボード URL が未設定";
      m.finishedAt = Date.now();
      this.emit(job);
      return;
    }
    try {
      switch (job.kind) {
        case "gateway.start":
          await c.dashboard.gatewayStart();
          await this.waitForGateway(c.dashboard, true, 60_000);
          m.message = "起動しました";
          break;
        case "gateway.stop":
          await c.dashboard.gatewayStop();
          await this.waitForGateway(c.dashboard, false, 60_000);
          m.message = "停止しました";
          break;
        case "gateway.restart": {
          await c.dashboard.gatewayRestart();
          await this.waitForAction(c.dashboard, "gateway-restart", m, job, 120_000);
          await this.waitForGateway(c.dashboard, true, 90_000);
          m.message = "再起動が完了しました";
          break;
        }
        case "update": {
          const started = await c.dashboard.updateApply();
          if (started.ok === false) throw new Error(started.detail ?? started.error ?? "update refused");
          const status = await this.waitForAction(c.dashboard, "hermes-update", m, job, 15 * 60_000);
          const receiptOutcome = status.receipt?.outcome;
          if (receiptOutcome && receiptOutcome !== "success") throw new Error(`update outcome: ${receiptOutcome}`);
          if (status.exit_code !== null && status.exit_code !== 0) throw new Error(`update exited with ${status.exit_code}`);
          this.poller.invalidateUpdate(m.machineId);
          // The dashboard restarts itself after an update: wait until /api/status answers again.
          await this.waitForGateway(c.dashboard, true, 120_000);
          const snap = await this.poller.poll(m.machineId, { forceUpdateCheck: true });
          m.message = `更新完了 (${status.receipt?.post_version ?? snap?.version ?? "?"})`;
          break;
        }
        case "doctor":
        case "security-audit":
        case "backup": {
          await c.dashboard.runOp(job.kind);
          const status = await this.waitForAction(c.dashboard, job.kind, m, job, 10 * 60_000);
          if (status.exit_code !== null && status.exit_code !== 0) throw new Error(`${job.kind} exited with ${status.exit_code}`);
          m.message = `${job.kind} 完了`;
          break;
        }
      }
      m.status = "ok";
    } catch (e) {
      m.status = "failed";
      m.message = e instanceof Error ? e.message : String(e);
    } finally {
      m.finishedAt = Date.now();
      this.emit(job);
      void this.poller.poll(m.machineId);
    }
  }

  private async waitForAction(dash: DashboardClient, name: string, m: OpsMachineState, job: OpsJob, timeoutMs: number) {
    const deadline = Date.now() + timeoutMs;
    let last = await dash.actionStatus(name, 100).catch(() => null);
    while (Date.now() < deadline) {
      if (last) {
        m.lines = last.lines.slice(-60);
        this.emit(job);
        if (!last.running && (last.exit_code !== null || last.lines.length > 0)) return last;
      }
      await sleep(1500);
      try {
        last = await dash.actionStatus(name, 100);
      } catch {
        // dashboard may restart itself mid-action (update); keep waiting
      }
    }
    throw new Error(`${name} がタイムアウトしました`);
  }

  private async waitForGateway(dash: DashboardClient, wantRunning: boolean, timeoutMs: number): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    let lastErr = "";
    while (Date.now() < deadline) {
      try {
        const st = await dash.status();
        if (Boolean(st.gateway_running) === wantRunning) return;
      } catch (e) {
        lastErr = e instanceof Error ? e.message : String(e);
      }
      await sleep(1500);
    }
    throw new Error(`ゲートウェイが ${wantRunning ? "起動" : "停止"} 状態になりません${lastErr ? ` (${lastErr})` : ""}`);
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

import { randomUUID } from "node:crypto";
import type { DatabaseSync } from "node:sqlite";
import { RUN_TERMINAL_STATUSES, type RunEvent } from "@fleet/hermes-client";
import type { MachineRepo } from "./machines.js";
import type { AuditLog } from "./audit.js";

export interface FleetRun {
  id: string;
  batchId: string;
  machineId: string;
  machineName: string;
  /** Hermes profile the run was sent to (null = the machine's default profile). */
  profile: string | null;
  remoteRunId: string | null;
  prompt: string;
  status: string;
  output: string;
  error: string;
  sessionId: string | null;
  createdAt: number;
  updatedAt: number;
  finishedAt: number | null;
}

export interface RunStreamEvent {
  runId: string;
  machineId: string;
  event: RunEvent | { event: "fleet.status"; status: string; error?: string; output?: string };
}

type Listener = (ev: RunStreamEvent) => void;

export interface RunTarget {
  machineId: string;
  /** Named Hermes profile; omitted/"default" = the machine's default profile. */
  profile?: string | null;
}

/**
 * Fans a prompt out to N machines via /v1/runs, relays their SSE events to console subscribers,
 * and persists the final output per run so batches can be reviewed later.
 */
export class RunManager {
  private listeners = new Map<string, Set<Listener>>(); // batchId -> listeners
  private buffers = new Map<string, RunStreamEvent[]>(); // batchId -> replay buffer (deltas/tool events)
  private aborts = new Map<string, AbortController>(); // runId -> abort
  private live = new Map<string, { output: string }>();

  constructor(
    private readonly db: DatabaseSync,
    private readonly repo: MachineRepo,
    private readonly audit: AuditLog,
  ) {}

  createBatch(targets: RunTarget[], prompt: string, opts: { model?: string; sessionByMachine?: Record<string, string>; label?: string } = {}): { batchId: string; runs: FleetRun[] } {
    const batchId = randomUUID();
    const now = Date.now();
    this.db.prepare("INSERT INTO batches (id, kind, label, created_at) VALUES (?, 'prompt', ?, ?)").run(batchId, opts.label ?? prompt.slice(0, 80), now);
    const runs: FleetRun[] = [];
    for (const t of targets) {
      const c = this.repo.clients(t.machineId);
      if (!c) continue;
      const id = randomUUID();
      const profile = t.profile && t.profile !== "default" ? t.profile : null;
      const sessionId = opts.sessionByMachine?.[t.machineId] ?? null;
      this.db
        .prepare("INSERT INTO runs (id, batch_id, machine_id, machine_name, profile, remote_run_id, prompt, status, session_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, NULL, ?, 'queued', ?, ?, ?)")
        .run(id, batchId, t.machineId, c.machine.name, profile, prompt, sessionId, now, now);
      const run = this.get(id)!;
      runs.push(run);
      void this.execute(run, opts.model);
    }
    this.audit.record("prompt.batch", { detail: { batchId, machines: runs.map((r) => (r.profile ? `${r.machineName}/${r.profile}` : r.machineName)), prompt: prompt.slice(0, 200) } });
    return { batchId, runs };
  }

  private async execute(run: FleetRun, model?: string): Promise<void> {
    const api = this.repo.profileApi(run.machineId, run.profile);
    if (!api) {
      this.finish(run.id, "failed", { error: run.profile ? `プロファイル ${run.profile} の API キーが未設定です（マシン詳細 › プロファイルで設定）` : "API サーバーが設定されていません" });
      return;
    }
    const abort = new AbortController();
    this.aborts.set(run.id, abort);
    this.live.set(run.id, { output: "" });
    try {
      const accepted = await api.createRun(
        { input: run.prompt, ...(model ? { model } : {}), ...(run.sessionId ? { session_id: run.sessionId } : {}) },
        { idempotencyKey: `fleet-${run.id}`, sessionId: run.sessionId ?? undefined },
      );
      this.db.prepare("UPDATE runs SET remote_run_id = ?, status = ?, updated_at = ? WHERE id = ?").run(accepted.run_id, "running", Date.now(), run.id);
      this.emit(run.batchId, { runId: run.id, machineId: run.machineId, event: { event: "fleet.status", status: "running" } });
      let terminal = false;
      for await (const ev of api.runEvents(accepted.run_id, abort.signal)) {
        const buf = this.live.get(run.id);
        if (ev.event === "message.delta" && typeof ev.delta === "string" && buf) buf.output += ev.delta;
        this.emit(run.batchId, { runId: run.id, machineId: run.machineId, event: ev });
        if (ev.event.startsWith("run.")) {
          const status = ev.event.slice(4);
          if (RUN_TERMINAL_STATUSES.has(status)) {
            terminal = true;
            this.finish(run.id, status, { output: typeof ev.output === "string" ? ev.output : buf?.output ?? "", error: typeof ev.error === "string" ? ev.error : "" , sessionId: typeof ev.session_id === "string" ? ev.session_id : undefined });
          }
        }
      }
      if (!terminal) {
        // Stream closed without a terminal event: ask the run status once.
        try {
          const st = await api.getRun(accepted.run_id);
          const status = RUN_TERMINAL_STATUSES.has(st.status) ? st.status : abort.signal.aborted ? "cancelled" : "failed";
          this.finish(run.id, status, { output: st.output ?? this.live.get(run.id)?.output ?? "", error: st.error ?? (status === "failed" ? "stream ended without terminal event" : ""), sessionId: st.session_id });
        } catch (e) {
          this.finish(run.id, abort.signal.aborted ? "cancelled" : "failed", { output: this.live.get(run.id)?.output ?? "", error: abort.signal.aborted ? "" : String(e) });
        }
      }
    } catch (e) {
      if (abort.signal.aborted) this.finish(run.id, "cancelled", { output: this.live.get(run.id)?.output ?? "" });
      else this.finish(run.id, "failed", { error: e instanceof Error ? e.message : String(e), output: this.live.get(run.id)?.output ?? "" });
    } finally {
      this.aborts.delete(run.id);
      this.live.delete(run.id);
    }
  }

  private finish(runId: string, status: string, fields: { output?: string; error?: string; sessionId?: string }): void {
    const now = Date.now();
    this.db
      .prepare("UPDATE runs SET status = ?, output = ?, error = ?, session_id = COALESCE(?, session_id), updated_at = ?, finished_at = ? WHERE id = ?")
      .run(status, fields.output ?? "", fields.error ?? "", fields.sessionId ?? null, now, now, runId);
    const run = this.get(runId);
    if (run) this.emit(run.batchId, { runId, machineId: run.machineId, event: { event: "fleet.status", status, error: fields.error, output: fields.output } });
  }

  async stop(runId: string): Promise<boolean> {
    const run = this.get(runId);
    if (!run) return false;
    const api = this.repo.profileApi(run.machineId, run.profile);
    if (run.remoteRunId && api) {
      try {
        await api.stopRun(run.remoteRunId);
      } catch {
        // fall through: abort local stream anyway
      }
    }
    this.aborts.get(runId)?.abort();
    this.audit.record("run.stop", { machineId: run.machineId, machineName: run.machineName, detail: { runId } });
    return true;
  }

  async approve(runId: string, choice: "once" | "session" | "always" | "deny", requestId?: string): Promise<unknown> {
    const run = this.get(runId);
    if (!run || !run.remoteRunId) throw new Error("run not found");
    const api = this.repo.profileApi(run.machineId, run.profile);
    if (!api) throw new Error("api client missing");
    this.audit.record("run.approve", { machineId: run.machineId, machineName: run.machineName, detail: { runId, choice } });
    return api.approveRun(run.remoteRunId, choice, requestId);
  }

  get(id: string): FleetRun | null {
    const r = this.db.prepare("SELECT * FROM runs WHERE id = ?").get(id) as Record<string, unknown> | undefined;
    return r ? toRun(r) : null;
  }

  batch(batchId: string): FleetRun[] {
    const rows = this.db.prepare("SELECT * FROM runs WHERE batch_id = ? ORDER BY created_at, machine_name").all(batchId) as unknown as Record<string, unknown>[];
    return rows.map(toRun);
  }

  recentBatches(limit = 30): { id: string; label: string; createdAt: number; runs: FleetRun[] }[] {
    const rows = this.db.prepare("SELECT * FROM batches WHERE kind = 'prompt' ORDER BY created_at DESC LIMIT ?").all(limit) as unknown as { id: string; label: string; created_at: number }[];
    return rows.map((b) => ({ id: b.id, label: b.label, createdAt: b.created_at, runs: this.batch(b.id) }));
  }

  subscribe(batchId: string, fn: Listener): () => void {
    let set = this.listeners.get(batchId);
    if (!set) this.listeners.set(batchId, (set = new Set()));
    set.add(fn);
    for (const ev of this.buffers.get(batchId) ?? []) fn(ev);
    return () => {
      set!.delete(fn);
    };
  }

  private emit(batchId: string, ev: RunStreamEvent): void {
    let buf = this.buffers.get(batchId);
    if (!buf) this.buffers.set(batchId, (buf = []));
    buf.push(ev);
    if (buf.length > 5000) buf.splice(0, buf.length - 5000);
    for (const l of this.listeners.get(batchId) ?? []) l(ev);
    // Drop replay buffer some time after the whole batch is terminal.
    if (ev.event.event === "fleet.status" && RUN_TERMINAL_STATUSES.has((ev.event as { status: string }).status)) {
      const allDone = this.batch(batchId).every((r) => RUN_TERMINAL_STATUSES.has(r.status));
      if (allDone) setTimeout(() => this.buffers.delete(batchId), 10 * 60_000).unref();
    }
  }
}

function toRun(r: Record<string, unknown>): FleetRun {
  return {
    id: r.id as string,
    batchId: r.batch_id as string,
    machineId: r.machine_id as string,
    machineName: r.machine_name as string,
    profile: (r.profile as string | null) ?? null,
    remoteRunId: (r.remote_run_id as string | null) ?? null,
    prompt: r.prompt as string,
    status: r.status as string,
    output: (r.output as string) ?? "",
    error: (r.error as string) ?? "",
    sessionId: (r.session_id as string | null) ?? null,
    createdAt: r.created_at as number,
    updatedAt: r.updated_at as number,
    finishedAt: (r.finished_at as number | null) ?? null,
  };
}

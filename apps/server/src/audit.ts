import type { DatabaseSync } from "node:sqlite";

export interface AuditEntry {
  id: number;
  at: number;
  actor: string;
  action: string;
  machineId: string | null;
  machineName: string | null;
  detail: string;
  ok: boolean;
}

export class AuditLog {
  constructor(private readonly db: DatabaseSync) {}

  record(action: string, opts: { machineId?: string; machineName?: string; detail?: unknown; ok?: boolean; actor?: string } = {}): void {
    const detail = typeof opts.detail === "string" ? opts.detail : opts.detail === undefined ? "" : JSON.stringify(opts.detail);
    this.db
      .prepare("INSERT INTO audit (at, actor, action, machine_id, machine_name, detail, ok) VALUES (?, ?, ?, ?, ?, ?, ?)")
      .run(Date.now(), opts.actor ?? "admin", action, opts.machineId ?? null, opts.machineName ?? null, detail.slice(0, 4000), opts.ok === false ? 0 : 1);
  }

  list(limit = 100, offset = 0): AuditEntry[] {
    const rows = this.db.prepare("SELECT * FROM audit ORDER BY id DESC LIMIT ? OFFSET ?").all(limit, offset) as unknown as {
      id: number; at: number; actor: string; action: string; machine_id: string | null; machine_name: string | null; detail: string; ok: number;
    }[];
    return rows.map((r) => ({ id: r.id, at: r.at, actor: r.actor, action: r.action, machineId: r.machine_id, machineName: r.machine_name, detail: r.detail, ok: r.ok === 1 }));
  }
}

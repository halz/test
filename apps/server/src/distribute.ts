import type { MachineRepo } from "./machines.js";
import type { AuditLog } from "./audit.js";

export interface ConfigChange {
  path: string; // dotted, e.g. "approvals.unattended_mode"
  value: unknown;
}

export interface EnvChange {
  key: string;
  value: string | null; // null = delete
}

export interface PreviewRow {
  machineId: string;
  machineName: string;
  ok: boolean;
  error?: string;
  config: { path: string; current: unknown; next: unknown; changed: boolean }[];
  env: { key: string; currentSet: boolean; currentMasked: string | null; next: string | null; changed: boolean }[];
}

export interface ApplyRow {
  machineId: string;
  machineName: string;
  ok: boolean;
  error?: string;
  applied: string[];
}

export function getPath(obj: unknown, path: string): unknown {
  let cur: unknown = obj;
  for (const part of path.split(".")) {
    if (cur === null || typeof cur !== "object") return undefined;
    cur = (cur as Record<string, unknown>)[part];
  }
  return cur;
}

export function setPath(obj: Record<string, unknown>, path: string, value: unknown): Record<string, unknown> {
  const parts = path.split(".");
  let cur = obj;
  for (let i = 0; i < parts.length - 1; i++) {
    const p = parts[i];
    if (cur[p] === null || typeof cur[p] !== "object" || Array.isArray(cur[p])) cur[p] = {};
    cur = cur[p] as Record<string, unknown>;
  }
  cur[parts[parts.length - 1]] = value;
  return obj;
}

/** Parse a value typed in the UI: JSON if it parses, else the raw string. */
export function coerceValue(raw: string): unknown {
  const t = raw.trim();
  if (t === "") return "";
  try {
    return JSON.parse(t);
  } catch {
    return raw;
  }
}

function same(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

export class Distributor {
  constructor(
    private readonly repo: MachineRepo,
    private readonly audit: AuditLog,
  ) {}

  async preview(machineIds: string[], config: ConfigChange[], env: EnvChange[]): Promise<PreviewRow[]> {
    return Promise.all(
      machineIds.map(async (id): Promise<PreviewRow> => {
        const c = this.repo.clients(id);
        if (!c) return { machineId: id, machineName: id, ok: false, error: "not found", config: [], env: [] };
        const row: PreviewRow = { machineId: id, machineName: c.machine.name, ok: true, config: [], env: [] };
        if (!c.dashboard) return { ...row, ok: false, error: "ダッシュボード未設定" };
        try {
          if (config.length) {
            const current = await c.dashboard.config();
            row.config = config.map((ch) => {
              const cur = getPath(current, ch.path);
              return { path: ch.path, current: cur, next: ch.value, changed: !same(cur, ch.value) };
            });
          }
          if (env.length) {
            const envRes = (await c.dashboard.env()) as { vars?: { name: string; set?: boolean; value?: string | null }[] } | Record<string, unknown>;
            const vars = Array.isArray((envRes as { vars?: unknown }).vars) ? ((envRes as { vars: { name: string; set?: boolean; value?: string | null }[] }).vars) : [];
            row.env = env.map((ch) => {
              const v = vars.find((x) => x.name === ch.key);
              const currentSet = Boolean(v?.set);
              return { key: ch.key, currentSet, currentMasked: v?.value ?? null, next: ch.value === null ? null : mask(ch.value), changed: ch.value === null ? currentSet : true };
            });
          }
        } catch (e) {
          row.ok = false;
          row.error = e instanceof Error ? e.message : String(e);
        }
        return row;
      }),
    );
  }

  async apply(machineIds: string[], config: ConfigChange[], env: EnvChange[]): Promise<ApplyRow[]> {
    const rows = await Promise.all(
      machineIds.map(async (id): Promise<ApplyRow> => {
        const c = this.repo.clients(id);
        if (!c) return { machineId: id, machineName: id, ok: false, error: "not found", applied: [] };
        const row: ApplyRow = { machineId: id, machineName: c.machine.name, ok: true, applied: [] };
        if (!c.dashboard) return { ...row, ok: false, error: "ダッシュボード未設定" };
        try {
          if (config.length) {
            const partial: Record<string, unknown> = {};
            for (const ch of config) setPath(partial, ch.path, ch.value);
            await c.dashboard.putConfig(partial);
            row.applied.push(...config.map((ch) => `config:${ch.path}`));
          }
          for (const ch of env) {
            if (ch.value === null) await c.dashboard.deleteEnv(ch.key);
            else await c.dashboard.putEnv(ch.key, ch.value);
            row.applied.push(`env:${ch.key}`);
          }
        } catch (e) {
          row.ok = false;
          row.error = e instanceof Error ? e.message : String(e);
        }
        this.audit.record("distribute.apply", { machineId: id, machineName: c.machine.name, ok: row.ok, detail: { config: config.map((x) => x.path), env: env.map((x) => x.key), applied: row.applied, error: row.error } });
        return row;
      }),
    );
    return rows;
  }
}

function mask(v: string): string {
  if (v.length <= 4) return "***";
  return `${v.slice(0, 3)}***`;
}

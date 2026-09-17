import { randomUUID } from "node:crypto";
import type { DatabaseSync } from "node:sqlite";
import { ApiServerClient, DashboardClient } from "@fleet/hermes-client";
import { decrypt, encrypt } from "./crypto.js";

export interface MachineInput {
  name: string;
  os?: string;
  tags?: string[];
  dashboardUrl?: string;
  dashboardAuthKind?: "none" | "basic";
  dashboardUsername?: string;
  dashboardPassword?: string; // write-only
  apiUrl?: string;
  apiKey?: string; // write-only
  notes?: string;
  sortOrder?: number;
}

export interface Machine {
  id: string;
  name: string;
  os: string;
  tags: string[];
  dashboardUrl: string;
  dashboardAuthKind: "none" | "basic";
  dashboardUsername: string;
  hasDashboardPassword: boolean;
  apiUrl: string;
  hasApiKey: boolean;
  notes: string;
  sortOrder: number;
  createdAt: number;
  updatedAt: number;
}

interface Row {
  id: string;
  name: string;
  os: string;
  tags: string;
  dashboard_url: string;
  dashboard_auth_kind: string;
  dashboard_username: string;
  dashboard_password_enc: string;
  api_url: string;
  api_key_enc: string;
  notes: string;
  sort_order: number;
  created_at: number;
  updated_at: number;
}

/** Per-profile API access on a machine (a named Hermes profile has its own API_SERVER_KEY). */
export interface ProfileKey {
  profile: string;
  /** Explicit API URL (a profile running its own gateway); empty = `<machine apiUrl>/p/<profile>` (multiplexed). */
  apiUrl: string;
  hasKey: boolean;
  updatedAt: number;
}

interface ProfileRow {
  machine_id: string;
  profile: string;
  api_url: string;
  api_key_enc: string;
  updated_at: number;
}

export class MachineRepo {
  private clientCache = new Map<string, { updatedAt: number; dashboard: DashboardClient | null; api: ApiServerClient | null }>();
  private profileClientCache = new Map<string, { updatedAt: number; api: ApiServerClient }>();

  constructor(
    private readonly db: DatabaseSync,
    private readonly key: Buffer,
    private readonly timeoutMs: number,
    /** Custom fetch (the Vercel demo routes *.demo hosts to in-process mocks). */
    private readonly fetchImpl?: typeof fetch,
  ) {}

  list(): Machine[] {
    const rows = this.db.prepare("SELECT * FROM machines ORDER BY sort_order, name").all() as unknown as Row[];
    return rows.map(toMachine);
  }

  get(id: string): Machine | null {
    const row = this.db.prepare("SELECT * FROM machines WHERE id = ?").get(id) as unknown as Row | undefined;
    return row ? toMachine(row) : null;
  }

  create(input: MachineInput): Machine {
    validate(input, true);
    const now = Date.now();
    const id = randomUUID();
    this.db
      .prepare(
        `INSERT INTO machines (id, name, os, tags, dashboard_url, dashboard_auth_kind, dashboard_username, dashboard_password_enc, api_url, api_key_enc, notes, sort_order, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        id,
        input.name.trim(),
        input.os ?? "unknown",
        JSON.stringify(input.tags ?? []),
        normalizeUrl(input.dashboardUrl),
        input.dashboardAuthKind ?? "none",
        input.dashboardUsername ?? "",
        encrypt(this.key, input.dashboardPassword ?? ""),
        normalizeUrl(input.apiUrl),
        encrypt(this.key, input.apiKey ?? ""),
        input.notes ?? "",
        input.sortOrder ?? 0,
        now,
        now,
      );
    return this.get(id)!;
  }

  update(id: string, input: Partial<MachineInput>): Machine | null {
    const row = this.db.prepare("SELECT * FROM machines WHERE id = ?").get(id) as unknown as Row | undefined;
    if (!row) return null;
    validate({ ...toInput(row), ...input } as MachineInput, false);
    const next = {
      name: input.name?.trim() ?? row.name,
      os: input.os ?? row.os,
      tags: JSON.stringify(input.tags ?? JSON.parse(row.tags)),
      dashboard_url: input.dashboardUrl !== undefined ? normalizeUrl(input.dashboardUrl) : row.dashboard_url,
      dashboard_auth_kind: input.dashboardAuthKind ?? row.dashboard_auth_kind,
      dashboard_username: input.dashboardUsername ?? row.dashboard_username,
      dashboard_password_enc: input.dashboardPassword !== undefined && input.dashboardPassword !== "" ? encrypt(this.key, input.dashboardPassword) : row.dashboard_password_enc,
      api_url: input.apiUrl !== undefined ? normalizeUrl(input.apiUrl) : row.api_url,
      api_key_enc: input.apiKey !== undefined && input.apiKey !== "" ? encrypt(this.key, input.apiKey) : row.api_key_enc,
      notes: input.notes ?? row.notes,
      sort_order: input.sortOrder ?? row.sort_order,
      updated_at: Date.now(),
    };
    this.db
      .prepare(
        `UPDATE machines SET name=?, os=?, tags=?, dashboard_url=?, dashboard_auth_kind=?, dashboard_username=?, dashboard_password_enc=?, api_url=?, api_key_enc=?, notes=?, sort_order=?, updated_at=? WHERE id=?`,
      )
      .run(next.name, next.os, next.tags, next.dashboard_url, next.dashboard_auth_kind, next.dashboard_username, next.dashboard_password_enc, next.api_url, next.api_key_enc, next.notes, next.sort_order, next.updated_at, id);
    this.clientCache.delete(id);
    return this.get(id);
  }

  delete(id: string): boolean {
    const r = this.db.prepare("DELETE FROM machines WHERE id = ?").run(id);
    this.db.prepare("DELETE FROM machine_profiles WHERE machine_id = ?").run(id);
    this.clientCache.delete(id);
    for (const k of [...this.profileClientCache.keys()]) if (k.startsWith(`${id}/`)) this.profileClientCache.delete(k);
    return r.changes > 0;
  }

  // ---- per-profile API keys ----

  listProfileKeys(machineId: string): ProfileKey[] {
    const rows = this.db.prepare("SELECT * FROM machine_profiles WHERE machine_id = ? ORDER BY profile").all(machineId) as unknown as ProfileRow[];
    return rows.map((r) => ({ profile: r.profile, apiUrl: r.api_url, hasKey: r.api_key_enc !== "", updatedAt: r.updated_at }));
  }

  /** Upsert; an omitted apiKey keeps the stored one, an omitted apiUrl keeps the stored one. */
  setProfileKey(machineId: string, profile: string, input: { apiUrl?: string; apiKey?: string }): ProfileKey {
    const cur = this.db.prepare("SELECT * FROM machine_profiles WHERE machine_id = ? AND profile = ?").get(machineId, profile) as unknown as ProfileRow | undefined;
    const apiUrl = input.apiUrl !== undefined ? (input.apiUrl ? normalizeUrl(input.apiUrl) : "") : cur?.api_url ?? "";
    const keyEnc = input.apiKey !== undefined ? (input.apiKey ? encrypt(this.key, input.apiKey) : "") : cur?.api_key_enc ?? "";
    const now = Date.now();
    this.db
      .prepare("INSERT INTO machine_profiles (machine_id, profile, api_url, api_key_enc, updated_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT(machine_id, profile) DO UPDATE SET api_url = excluded.api_url, api_key_enc = excluded.api_key_enc, updated_at = excluded.updated_at")
      .run(machineId, profile, apiUrl, keyEnc, now);
    this.profileClientCache.delete(`${machineId}/${profile}`);
    return { profile, apiUrl, hasKey: keyEnc !== "", updatedAt: now };
  }

  deleteProfileKey(machineId: string, profile: string): boolean {
    const r = this.db.prepare("DELETE FROM machine_profiles WHERE machine_id = ? AND profile = ?").run(machineId, profile);
    this.profileClientCache.delete(`${machineId}/${profile}`);
    return r.changes > 0;
  }

  /**
   * API client for a (machine, profile) target. The default profile is the machine's own API
   * server; a named profile uses its stored key and either its own URL or the multiplexed
   * `/p/<profile>` prefix on the machine's API URL. Null when nothing usable is configured.
   */
  profileApi(machineId: string, profile?: string | null): ApiServerClient | null {
    const cl = this.clients(machineId);
    if (!cl) return null;
    if (!profile || profile === "default") return cl.api;
    const row = this.db.prepare("SELECT * FROM machine_profiles WHERE machine_id = ? AND profile = ?").get(machineId, profile) as unknown as ProfileRow | undefined;
    if (!row || row.api_key_enc === "") return null;
    const baseUrl = row.api_url || (cl.machine.apiUrl ? `${cl.machine.apiUrl.replace(/\/+$/, "")}/p/${encodeURIComponent(profile)}` : "");
    if (!baseUrl) return null;
    const cacheKey = `${machineId}/${profile}`;
    const cached = this.profileClientCache.get(cacheKey);
    if (cached && cached.updatedAt === row.updated_at && cached.api.baseUrl === baseUrl) return cached.api;
    const api = new ApiServerClient({ baseUrl, apiKey: decrypt(this.key, row.api_key_enc), timeoutMs: this.timeoutMs, fetch: this.fetchImpl });
    this.profileClientCache.set(cacheKey, { updatedAt: row.updated_at, api });
    return api;
  }

  /** Build (and cache) clients; secrets are decrypted only here. */
  clients(id: string): { machine: Machine; dashboard: DashboardClient | null; api: ApiServerClient | null } | null {
    const row = this.db.prepare("SELECT * FROM machines WHERE id = ?").get(id) as unknown as Row | undefined;
    if (!row) return null;
    const machine = toMachine(row);
    const cached = this.clientCache.get(id);
    if (cached && cached.updatedAt === row.updated_at) return { machine, ...cached };
    const dashboard = row.dashboard_url
      ? new DashboardClient({
          baseUrl: row.dashboard_url,
          timeoutMs: this.timeoutMs,
          fetch: this.fetchImpl,
          auth:
            row.dashboard_auth_kind === "basic"
              ? { kind: "basic", username: row.dashboard_username, password: decrypt(this.key, row.dashboard_password_enc) }
              : { kind: "none" },
        })
      : null;
    const api = row.api_url ? new ApiServerClient({ baseUrl: row.api_url, apiKey: decrypt(this.key, row.api_key_enc), timeoutMs: this.timeoutMs, fetch: this.fetchImpl }) : null;
    const entry = { updatedAt: row.updated_at, dashboard, api };
    this.clientCache.set(id, entry);
    return { machine, ...entry };
  }

  /** Clients for a transient (unsaved) machine input, used by the connection test on the add form. */
  transientClients(input: MachineInput): { dashboard: DashboardClient | null; api: ApiServerClient | null } {
    const dashboard = input.dashboardUrl
      ? new DashboardClient({
          baseUrl: normalizeUrl(input.dashboardUrl),
          timeoutMs: this.timeoutMs,
          fetch: this.fetchImpl,
          auth: input.dashboardAuthKind === "basic" ? { kind: "basic", username: input.dashboardUsername ?? "", password: input.dashboardPassword ?? "" } : { kind: "none" },
        })
      : null;
    const api = input.apiUrl ? new ApiServerClient({ baseUrl: normalizeUrl(input.apiUrl), apiKey: input.apiKey ?? "", timeoutMs: this.timeoutMs, fetch: this.fetchImpl }) : null;
    return { dashboard, api };
  }
}

function toMachine(r: Row): Machine {
  return {
    id: r.id,
    name: r.name,
    os: r.os,
    tags: safeJson(r.tags, []),
    dashboardUrl: r.dashboard_url,
    dashboardAuthKind: r.dashboard_auth_kind === "basic" ? "basic" : "none",
    dashboardUsername: r.dashboard_username,
    hasDashboardPassword: r.dashboard_password_enc !== "",
    apiUrl: r.api_url,
    hasApiKey: r.api_key_enc !== "",
    notes: r.notes,
    sortOrder: r.sort_order,
    createdAt: r.created_at,
    updatedAt: r.updated_at,
  };
}

function toInput(r: Row): MachineInput {
  return { name: r.name, os: r.os, tags: safeJson(r.tags, []), dashboardUrl: r.dashboard_url, dashboardAuthKind: r.dashboard_auth_kind as "none" | "basic", dashboardUsername: r.dashboard_username, apiUrl: r.api_url, notes: r.notes, sortOrder: r.sort_order };
}

function safeJson<T>(s: string, fallback: T): T {
  try {
    return JSON.parse(s) as T;
  } catch {
    return fallback;
  }
}

export function normalizeUrl(u: string | undefined): string {
  if (!u) return "";
  let s = u.trim().replace(/\/+$/, "");
  if (s && !/^https?:\/\//i.test(s)) s = `http://${s}`;
  return s;
}

export class ValidationError extends Error {}

function validate(input: MachineInput, creating: boolean): void {
  if (!input.name || !input.name.trim()) throw new ValidationError("name is required");
  if (input.name.trim().length > 64) throw new ValidationError("name must be 64 characters or fewer");
  if (!input.dashboardUrl && !input.apiUrl) throw new ValidationError("at least one of dashboardUrl / apiUrl is required");
  for (const u of [input.dashboardUrl, input.apiUrl]) {
    if (!u) continue;
    try {
      new URL(normalizeUrl(u));
    } catch {
      throw new ValidationError(`invalid URL: ${u}`);
    }
  }
  if (input.dashboardAuthKind === "basic" && !input.dashboardUsername) throw new ValidationError("dashboardUsername is required for basic auth");
  void creating;
}

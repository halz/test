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

export interface Snapshot {
  machine: Machine;
  online: boolean;
  checkedAt: number;
  latencyMs?: number;
  version?: string;
  dashboard?: {
    ok: boolean;
    error?: string;
    authRequired?: boolean;
    status?: Record<string, unknown> & { gateway_running?: boolean; gateway_state?: string | null; active_sessions?: number; overall?: string; components?: Record<string, { status?: string }> };
    stats?: {
      hostname?: string; system?: string; platform?: string; arch?: string; cpu_count?: number; cpu_percent?: number; load_avg?: number[]; uptime_seconds?: number;
      memory?: { total: number; used: number; available: number; percent: number };
      disk?: { total: number; used: number; free: number; percent: number };
    };
  };
  api?: { ok: boolean; error?: string; version?: string; status?: string; activeRuns?: number };
  model?: { provider: string; model: string; checkedAt: number };
  /** Hermes profiles on the machine; hasKey = the console can send prompts to it. */
  profiles?: { name: string; hasKey: boolean }[];
  update?: { update_available: boolean; behind: number | null; current_version: string; can_apply: boolean; install_method: string; message?: string | null; checkedAt: number; commits?: { sha: string; summary: string }[] };
  alerts: { level: "warn" | "error"; code: string; message: string }[];
}

export type OpsKind = "gateway.start" | "gateway.stop" | "gateway.restart" | "update" | "doctor" | "security-audit" | "backup";

export interface OpsJob {
  id: string;
  kind: OpsKind;
  canary: boolean;
  status: "running" | "ok" | "failed" | "partial";
  machines: { machineId: string; machineName: string; status: "pending" | "running" | "ok" | "failed" | "skipped"; message: string; lines: string[]; startedAt?: number; finishedAt?: number }[];
  createdAt: number;
  finishedAt?: number;
}

export interface FleetRun {
  id: string;
  batchId: string;
  machineId: string;
  machineName: string;
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
  event: { event: string; [k: string]: unknown };
}

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

export const OPS_LABELS: Record<OpsKind, string> = {
  "gateway.start": "ゲートウェイ起動",
  "gateway.stop": "ゲートウェイ停止",
  "gateway.restart": "ゲートウェイ再起動",
  update: "Hermes アップデート",
  doctor: "doctor（診断）",
  "security-audit": "セキュリティ監査",
  backup: "バックアップ",
};

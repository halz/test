// Response shapes observed in hermes-agent sources (hermes_cli/web_routers/*, gateway/platforms/api_server*.py).
// Fields are optional where older/newer Hermes versions may omit them.

export interface DashboardStatus {
  version?: string;
  release_date?: string;
  gateway_running?: boolean;
  gateway_state?: string | null;
  gateway_platforms?: Record<string, unknown>;
  gateway_exit_reason?: string | null;
  gateway_updated_at?: string | number | null;
  active_agents?: number;
  gateway_busy?: boolean;
  gateway_drainable?: boolean;
  active_sessions?: number;
  auth_required?: boolean;
  auth_providers?: unknown;
  can_update_hermes?: boolean;
  overall?: "ok" | "degraded" | string;
  components?: Record<string, { status?: string; [k: string]: unknown }>;
  profiles?: unknown[];
  gateway_mode?: string;
  install_id?: string;
  memory_pressure?: unknown;
  disk_pressure?: unknown;
  [k: string]: unknown;
}

export interface SystemStats {
  system?: string;
  release?: string;
  platform?: string;
  arch?: string;
  hostname?: string;
  python_version?: string;
  hermes_version?: string;
  cpu_count?: number;
  cpu_percent?: number;
  load_avg?: number[];
  uptime_seconds?: number;
  memory?: { total: number; available: number; used: number; percent: number };
  disk?: { total: number; used: number; free: number; percent: number };
  process?: { pid: number; rss: number; create_time: number; num_threads: number };
  psutil?: boolean;
  [k: string]: unknown;
}

export interface UpdateCheck {
  install_method: string;
  current_version: string;
  behind: number | null;
  update_available: boolean;
  can_apply: boolean;
  update_command?: string;
  message?: string | null;
  commits?: { sha: string; summary: string; author?: string; at?: string }[];
}

export interface ActionStarted {
  ok: boolean;
  pid?: number;
  name: string;
  action_id?: string;
  already_running?: boolean;
  error?: string;
  detail?: string;
}

export interface ActionStatus {
  name: string;
  running: boolean;
  exit_code: number | null;
  pid: number | null;
  lines: string[];
  action_id?: string;
  receipt?: {
    outcome?: string;
    started_at?: string;
    finished_at?: string;
    pre_sha?: string;
    post_sha?: string;
    post_version?: string;
    fleet_states?: string[];
  };
}

export interface LogsResponse {
  file: string;
  lines: string[];
}

export interface SessionRow {
  id: string;
  title?: string | null;
  source?: string;
  started_at?: number;
  ended_at?: number | null;
  message_count?: number;
  is_active?: boolean;
  archived?: boolean;
  pinned?: boolean;
  profile?: string;
  model?: string;
  [k: string]: unknown;
}

export interface SessionsResponse {
  sessions: SessionRow[];
  total: number;
  limit?: number;
  offset?: number;
}

export interface CronJob {
  id: string;
  name?: string;
  prompt?: string;
  schedule?: string;
  enabled?: boolean;
  paused?: boolean;
  next_run?: string | number | null;
  last_run?: string | number | null;
  deliver?: string;
  profile?: string;
  [k: string]: unknown;
}

export interface AuthProviders {
  providers: { name: string; display_name: string; supports_password: boolean }[];
}

// --- API server (port 8642) ---

export interface ApiHealth {
  status: string;
  [k: string]: unknown;
}

export interface ApiHealthDetailed {
  status: string;
  readiness?: unknown;
  platform?: string;
  version?: string;
  gateway_state?: string | null;
  platforms?: Record<string, unknown>;
  active_agents?: number;
  gateway_busy?: boolean;
  gateway_drainable?: boolean;
  exit_reason?: string | null;
  updated_at?: string | null;
  pid?: number;
}

export interface Capabilities {
  features?: Record<string, unknown>;
  endpoints?: Record<string, { method: string; path: string } | [string, string]>;
  [k: string]: unknown;
}

export interface RunCreateBody {
  input: string | { role: string; content: string }[];
  session_id?: string;
  model?: string;
  provider?: string;
  instructions?: string;
  model_options?: Record<string, unknown>;
  [k: string]: unknown;
}

export interface RunAccepted {
  run_id: string;
  status: string;
  replayed?: boolean;
}

export type RunTerminalStatus = "completed" | "failed" | "cancelled" | "interrupted";

export interface RunStatus {
  object?: "hermes.run";
  run_id: string;
  status: "queued" | "started" | "running" | "waiting_for_approval" | RunTerminalStatus | string;
  created_at?: number;
  updated_at?: number;
  session_id?: string;
  model?: string;
  last_event?: string;
  output?: string;
  error?: string;
  usage?: unknown;
  approval?: RunEvent;
  [k: string]: unknown;
}

export interface RunEvent {
  event: string; // message.delta | tool.started | tool.completed | approval.request | run.completed | run.failed | run.cancelled | ...
  run_id: string;
  timestamp: number;
  delta?: string;
  output?: string;
  error?: string;
  tool_name?: string;
  preview?: string;
  choices?: string[];
  request_id?: string;
  command?: string;
  [k: string]: unknown;
}

export const RUN_TERMINAL_STATUSES: ReadonlySet<string> = new Set([
  "completed",
  "failed",
  "cancelled",
  "interrupted",
]);

import { HermesAuthError, HermesHttpError, joinUrl, readBodyText } from "./errors.js";
import type {
  ActionStarted,
  ActionStatus,
  AuthProviders,
  CronJob,
  DashboardStatus,
  LogsResponse,
  SessionsResponse,
  SystemStats,
  UpdateCheck,
} from "./types.js";

export type DashboardAuth =
  | { kind: "none" }
  | { kind: "basic"; username: string; password: string; provider?: string };

export interface DashboardClientOptions {
  baseUrl: string;
  auth: DashboardAuth;
  fetch?: typeof fetch;
  timeoutMs?: number;
  /** Optional cookie jar to restore a previous session (name -> value). */
  cookies?: Record<string, string>;
}

const SESSION_COOKIE_RE = /^(?:__Host-|__Secure-)?hermes_session_/;

/**
 * Client for the Hermes dashboard / `hermes serve` REST API (default port 9119).
 * Handles the username/password auth gate: logs in via POST /auth/password-login and
 * replays the session cookies; on a 401 it re-authenticates once and retries.
 */
export class DashboardClient {
  readonly baseUrl: string;
  private readonly auth: DashboardAuth;
  private readonly fetchImpl: typeof fetch;
  private readonly timeoutMs: number;
  private cookies: Map<string, string>;
  private loginPromise: Promise<void> | null = null;

  constructor(opts: DashboardClientOptions) {
    this.baseUrl = opts.baseUrl.replace(/\/+$/, "");
    this.auth = opts.auth;
    this.fetchImpl = opts.fetch ?? globalThis.fetch;
    this.timeoutMs = opts.timeoutMs ?? 15_000;
    this.cookies = new Map(Object.entries(opts.cookies ?? {}));
  }

  /** Exported cookie jar (so a caller can persist the session across restarts). */
  get cookieJar(): Record<string, string> {
    return Object.fromEntries(this.cookies);
  }

  // ---- public (no auth) ----

  status(profile?: string): Promise<DashboardStatus> {
    const q = profile ? `?profile=${encodeURIComponent(profile)}` : "";
    return this.request<DashboardStatus>("GET", `/api/status${q}`, undefined, { auth: false });
  }

  authProviders(): Promise<AuthProviders> {
    return this.request<AuthProviders>("GET", "/api/auth/providers", undefined, { auth: false });
  }

  // ---- auth ----

  async login(): Promise<void> {
    if (this.auth.kind !== "basic") return;
    if (this.loginPromise) return this.loginPromise;
    this.loginPromise = this.doLogin().finally(() => {
      this.loginPromise = null;
    });
    return this.loginPromise;
  }

  private async doLogin(): Promise<void> {
    if (this.auth.kind !== "basic") return;
    const url = joinUrl(this.baseUrl, "/auth/password-login");
    const res = await this.fetchImpl(url, {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify({
        provider: this.auth.provider ?? "basic",
        username: this.auth.username,
        password: this.auth.password,
        next: "",
      }),
      signal: AbortSignal.timeout(this.timeoutMs),
      redirect: "manual",
    });
    if (res.status === 401) throw new HermesAuthError("Invalid dashboard username or password");
    if (res.status === 404) throw new HermesAuthError("Dashboard has no password auth provider (set dashboard.basic_auth)");
    if (res.status === 429) throw new HermesAuthError("Dashboard login rate limited; retry shortly");
    if (!res.ok) throw new HermesHttpError(res.status, url, await readBodyText(res));
    this.absorbCookies(res);
    if (![...this.cookies.keys()].some((k) => SESSION_COOKIE_RE.test(k))) {
      throw new HermesAuthError("Login succeeded but no session cookie was returned");
    }
  }

  me(): Promise<{ user_id: string; provider: string; expires_at?: number }> {
    return this.request("GET", "/api/auth/me");
  }

  // ---- host / gateway ----

  systemStats(): Promise<SystemStats> {
    return this.request("GET", "/api/system/stats");
  }

  gatewayStart(profile?: string): Promise<ActionStarted> {
    return this.request("POST", withProfile("/api/gateway/start", profile), {});
  }

  gatewayStop(profile?: string): Promise<ActionStarted> {
    return this.request("POST", withProfile("/api/gateway/stop", profile), {});
  }

  gatewayRestart(profile?: string): Promise<ActionStarted> {
    return this.request("POST", withProfile("/api/gateway/restart", profile), {});
  }

  updateCheck(force = false): Promise<UpdateCheck> {
    return this.request("GET", `/api/hermes/update/check${force ? "?force=true" : ""}`);
  }

  updateApply(): Promise<ActionStarted> {
    return this.request("POST", "/api/hermes/update", {});
  }

  actionStatus(name: string, lines = 200): Promise<ActionStatus> {
    return this.request("GET", `/api/actions/${encodeURIComponent(name)}/status?lines=${lines}`);
  }

  runOp(name: "doctor" | "security-audit" | "backup"): Promise<ActionStarted> {
    return this.request("POST", `/api/ops/${name}`, {});
  }

  logs(params: { file?: string; lines?: number; level?: string; component?: string; search?: string } = {}): Promise<LogsResponse> {
    const q = new URLSearchParams();
    q.set("file", params.file ?? "agent");
    q.set("lines", String(params.lines ?? 100));
    if (params.level) q.set("level", params.level);
    if (params.component) q.set("component", params.component);
    if (params.search) q.set("search", params.search);
    return this.request("GET", `/api/logs?${q}`);
  }

  // ---- sessions ----

  sessions(params: { limit?: number; offset?: number; order?: "created" | "recent"; profile?: string; archived?: string } = {}): Promise<SessionsResponse> {
    const q = new URLSearchParams();
    q.set("limit", String(params.limit ?? 20));
    q.set("offset", String(params.offset ?? 0));
    q.set("order", params.order ?? "recent");
    if (params.archived) q.set("archived", params.archived);
    if (params.profile) q.set("profile", params.profile);
    return this.request("GET", `/api/sessions?${q}`);
  }

  sessionSearch(query: string, limit = 20): Promise<unknown> {
    return this.request("GET", `/api/sessions/search?q=${encodeURIComponent(query)}&limit=${limit}`);
  }

  sessionMessages(id: string, params: { limit?: number; offset?: number } = {}): Promise<unknown> {
    const q = new URLSearchParams();
    if (params.limit) q.set("limit", String(params.limit));
    if (params.offset) q.set("offset", String(params.offset));
    return this.request("GET", `/api/sessions/${encodeURIComponent(id)}/messages?${q}`);
  }

  // ---- cron ----

  cronJobs(profile = "all"): Promise<{ jobs?: CronJob[] } | CronJob[]> {
    return this.request("GET", `/api/cron/jobs?profile=${encodeURIComponent(profile)}`);
  }

  cronCreate(body: Record<string, unknown>, profile?: string): Promise<unknown> {
    return this.request("POST", withProfile("/api/cron/jobs", profile), body);
  }

  cronAction(jobId: string, action: "pause" | "resume" | "trigger", profile?: string): Promise<unknown> {
    return this.request("POST", withProfile(`/api/cron/jobs/${encodeURIComponent(jobId)}/${action}`, profile), {});
  }

  cronDelete(jobId: string, profile?: string): Promise<unknown> {
    return this.request("DELETE", withProfile(`/api/cron/jobs/${encodeURIComponent(jobId)}`, profile));
  }

  // ---- config / env ----

  config(profile?: string): Promise<Record<string, unknown>> {
    return this.request("GET", withProfile("/api/config", profile));
  }

  putConfig(body: Record<string, unknown>, profile?: string): Promise<unknown> {
    return this.request("PUT", withProfile("/api/config", profile), body);
  }

  env(profile?: string): Promise<unknown> {
    return this.request("GET", withProfile("/api/env", profile));
  }

  putEnv(body: Record<string, unknown>, profile?: string): Promise<unknown> {
    return this.request("PUT", withProfile("/api/env", profile), body);
  }

  // ---- generic ----

  async request<T>(
    method: string,
    path: string,
    body?: unknown,
    opts: { auth?: boolean; retry?: boolean } = {},
  ): Promise<T> {
    const needsAuth = opts.auth !== false && this.auth.kind !== "none";
    if (needsAuth && !this.hasSessionCookie()) await this.login();
    const url = joinUrl(this.baseUrl, path);
    const headers: Record<string, string> = { accept: "application/json" };
    if (body !== undefined) headers["content-type"] = "application/json";
    const cookie = this.cookieHeader();
    if (cookie) headers.cookie = cookie;
    const res = await this.fetchImpl(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(this.timeoutMs),
      redirect: "manual",
    });
    this.absorbCookies(res);
    const unauthenticated = res.status === 401 || res.status === 403 || (res.status >= 300 && res.status < 400 && needsAuth);
    if (unauthenticated && needsAuth && opts.retry !== false) {
      this.cookies.clear();
      await this.login();
      return this.request<T>(method, path, body, { ...opts, retry: false });
    }
    if (!res.ok) throw new HermesHttpError(res.status, url, await readBodyText(res));
    const text = await res.text();
    if (!text) return undefined as T;
    try {
      return JSON.parse(text) as T;
    } catch {
      throw new HermesHttpError(res.status, url, `non-JSON response: ${text.slice(0, 200)}`);
    }
  }

  private hasSessionCookie(): boolean {
    for (const k of this.cookies.keys()) if (SESSION_COOKIE_RE.test(k)) return true;
    return false;
  }

  private cookieHeader(): string {
    return [...this.cookies.entries()].map(([k, v]) => `${k}=${v}`).join("; ");
  }

  private absorbCookies(res: Response): void {
    const setCookies: string[] =
      typeof (res.headers as unknown as { getSetCookie?: () => string[] }).getSetCookie === "function"
        ? (res.headers as unknown as { getSetCookie: () => string[] }).getSetCookie()
        : (res.headers.get("set-cookie") ? [res.headers.get("set-cookie") as string] : []);
    for (const sc of setCookies) {
      const first = sc.split(";")[0] ?? "";
      const eq = first.indexOf("=");
      if (eq <= 0) continue;
      const name = first.slice(0, eq).trim();
      const value = first.slice(eq + 1).trim();
      const maxAge = /max-age=(-?\d+)/i.exec(sc);
      if ((maxAge && Number(maxAge[1]) <= 0) || value === "" || value === '""') this.cookies.delete(name);
      else this.cookies.set(name, value);
    }
  }
}

function withProfile(path: string, profile?: string): string {
  if (!profile) return path;
  return `${path}${path.includes("?") ? "&" : "?"}profile=${encodeURIComponent(profile)}`;
}

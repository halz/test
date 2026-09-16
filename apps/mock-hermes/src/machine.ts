import { createHmac, randomBytes } from "node:crypto";
import { Hono } from "hono";
import { serve, type ServerType } from "@hono/node-server";
import { streamSSE } from "hono/streaming";
import { getCookie, setCookie } from "hono/cookie";

export interface MockMachineOptions {
  name: string;
  os: "macOS" | "Windows" | "Linux";
  dashboardPort: number;
  apiPort: number;
  username?: string;
  password?: string;
  apiKey?: string;
  /** Simulate a non-loopback bind: dashboard requires login. Default true. */
  authRequired?: boolean;
  version?: string;
  updateBehind?: number;
  gatewayRunning?: boolean;
  host?: string;
}

interface ActionState {
  running: boolean;
  exit_code: number | null;
  pid: number;
  lines: string[];
  started: number;
  action_id?: string;
}

interface RunState {
  run_id: string;
  status: string;
  created_at: number;
  updated_at: number;
  session_id: string;
  model: string;
  output?: string;
  error?: string;
  last_event?: string;
  input: string;
  listeners: Set<(ev: Record<string, unknown> | null) => void>;
  events: Record<string, unknown>[];
  closed: boolean;
  pendingApproval?: { request_id: string };
  timer?: NodeJS.Timeout;
}

export interface MockMachine {
  opts: Required<Pick<MockMachineOptions, "name" | "os" | "dashboardPort" | "apiPort" | "username" | "password" | "apiKey" | "authRequired" | "host">>;
  version: string;
  gatewayRunning: boolean;
  gatewayState: string;
  updateBehind: number;
  actions: Map<string, ActionState>;
  runs: Map<string, RunState>;
  close(): Promise<void>;
}

const SECRET = randomBytes(32);

function sign(payload: Record<string, unknown>): string {
  const body = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const mac = createHmac("sha256", SECRET).update(body).digest("base64url");
  return `${body}.${mac}`;
}

function verify(token: string | undefined): Record<string, unknown> | null {
  if (!token) return null;
  const [body, mac] = token.split(".");
  if (!body || !mac) return null;
  const expected = createHmac("sha256", SECRET).update(body).digest("base64url");
  if (expected !== mac) return null;
  try {
    const payload = JSON.parse(Buffer.from(body, "base64url").toString());
    if (typeof payload.exp === "number" && payload.exp < Date.now() / 1000) return null;
    return payload;
  } catch {
    return null;
  }
}

function fakeLogLines(name: string, n: number): string[] {
  const out: string[] = [];
  const levels = ["INFO", "INFO", "INFO", "DEBUG", "WARNING", "INFO", "ERROR"];
  const comps = ["gateway", "agent", "cron", "api_server", "tools.terminal"];
  const now = Date.now();
  for (let i = n; i > 0; i--) {
    const ts = new Date(now - i * 7000).toISOString().replace("T", " ").slice(0, 19);
    const lvl = levels[(i * 3 + name.length) % levels.length];
    const comp = comps[(i * 3) % comps.length];
    out.push(`${ts} ${lvl} [${comp}] ${name}: simulated log line #${n - i + 1}`);
  }
  return out;
}

export interface MockMachineApps {
  machine: MockMachine;
  dash: Hono;
  api: Hono;
}

/** Build the two Hono apps for one machine without binding ports (used in-process by the Vercel demo). */
export function createMockMachine(o: MockMachineOptions): MockMachineApps {
  const opts = {
    name: o.name,
    os: o.os,
    dashboardPort: o.dashboardPort,
    apiPort: o.apiPort,
    username: o.username ?? "admin",
    password: o.password ?? "hermes",
    apiKey: o.apiKey ?? "mock-api-key",
    authRequired: o.authRequired ?? true,
    host: o.host ?? "127.0.0.1",
  };
  const bootTime = Date.now() / 1000 - 3600 * (3 + opts.name.length);
  const machine: MockMachine = {
    opts,
    version: o.version ?? "0.23.1",
    gatewayRunning: o.gatewayRunning ?? true,
    gatewayState: (o.gatewayRunning ?? true) ? "running" : "stopped",
    updateBehind: o.updateBehind ?? 0,
    actions: new Map(),
    runs: new Map(),
    close: async () => {},
  };

  const sessions = Array.from({ length: 12 }, (_, i) => ({
    id: `sess_${opts.name}_${i + 1}`,
    title: `${opts.name} session ${i + 1}`,
    source: i % 4 === 0 ? "cron" : i % 3 === 0 ? "api_server" : "desktop",
    started_at: Date.now() / 1000 - i * 5400,
    ended_at: i === 0 ? null : Date.now() / 1000 - i * 5400 + 1200,
    message_count: 4 + (i * 7) % 30,
    is_active: i === 0,
    archived: false,
    pinned: i === 2,
    profile: "default",
    model: "anthropic/claude-sonnet-5",
  }));

  const cronJobs: { id: string; name: string; prompt: string; schedule: string; enabled: boolean; paused: boolean; next_run: string | null; last_run: string | null; deliver: string; profile: string }[] = [
    { id: `cron_${opts.name}_daily`, name: "Daily summary", prompt: "Summarize yesterday", schedule: "0 9 * * *", enabled: true, paused: false, next_run: new Date(Date.now() + 3600e3).toISOString(), last_run: new Date(Date.now() - 82800e3).toISOString(), deliver: "local", profile: "default" },
    { id: `cron_${opts.name}_health`, name: "Disk check", prompt: "Check free disk", schedule: "*/30 * * * *", enabled: true, paused: opts.name.endsWith("3"), next_run: new Date(Date.now() + 900e3).toISOString(), last_run: new Date(Date.now() - 900e3).toISOString(), deliver: "local", profile: "default" },
  ];

  const startAction = (name: string, durationMs: number, lines: string[], onDone?: () => void, exit = 0): ActionState => {
    const st: ActionState = { running: true, exit_code: null, pid: 40000 + Math.floor(Math.random() * 1000), lines: [`=== ${name} started ===`], started: Date.now(), action_id: randomBytes(8).toString("hex") };
    machine.actions.set(name, st);
    const step = durationMs / (lines.length + 1);
    lines.forEach((l, i) => setTimeout(() => st.lines.push(l), step * (i + 1)));
    setTimeout(() => {
      st.running = false;
      st.exit_code = exit;
      st.lines.push(`=== ${name} completed ${st.action_id} ===`);
      onDone?.();
    }, durationMs);
    return st;
  };

  // ---------------- dashboard (9119) ----------------
  const dash = new Hono();

  const isAuthed = (c: { req: { header: (n: string) => string | undefined } }): boolean => {
    if (!opts.authRequired) return true;
    const cookie = c.req.header("cookie") ?? "";
    const m = /(?:^|;\s*)(?:__Host-|__Secure-)?hermes_session_at=([^;]+)/.exec(cookie);
    return verify(m?.[1]) !== null;
  };

  dash.get("/api/health", (c) => c.json({ status: "ok" }));
  dash.get("/api/status", (c) =>
    c.json({
      version: machine.version,
      release_date: "2026-09-01",
      can_update_hermes: true,
      gateway_running: machine.gatewayRunning,
      gateway_state: machine.gatewayState,
      gateway_platforms: machine.gatewayRunning ? { api_server: { status: "connected" }, telegram: { status: "connected" } } : {},
      gateway_exit_reason: machine.gatewayRunning ? null : "stopped by user",
      gateway_updated_at: new Date().toISOString(),
      gateway_shared_with: null,
      active_agents: machine.runs.size ? [...machine.runs.values()].filter((r) => r.status === "running").length : 0,
      gateway_busy: false,
      gateway_drainable: machine.gatewayRunning,
      restart_drain_timeout: 30,
      active_sessions: 1,
      auth_required: opts.authRequired,
      auth_providers: opts.authRequired ? ["basic"] : [],
      nous_session_valid: true,
      install_id: `inst_${opts.name}`,
      components: { gateway: { status: machine.gatewayRunning ? "ok" : "error" }, database: { status: "ok" }, model: { status: "ok" } },
      overall: machine.gatewayRunning ? "ok" : "degraded",
      profiles: ["default"],
      gateway_mode: "standalone",
      ...(opts.name.endsWith("4") ? { disk_pressure: { level: "warning", percent: 91 } } : {}),
    }),
  );
  dash.get("/api/auth/providers", (c) => c.json({ providers: [{ name: "basic", display_name: "Username & Password", supports_password: true }] }));
  dash.post("/auth/password-login", async (c) => {
    const body = await c.req.json().catch(() => ({}));
    if (body.provider !== "basic") return c.json({ detail: "Unknown provider" }, 404);
    if (body.username !== opts.username || body.password !== opts.password) return c.json({ detail: "Invalid credentials" }, 401);
    const at = sign({ sub: opts.username, kind: "access", exp: Math.floor(Date.now() / 1000) + 12 * 3600 });
    const rt = sign({ sub: opts.username, kind: "refresh", exp: Math.floor(Date.now() / 1000) + 30 * 86400 });
    setCookie(c, "hermes_session_at", at, { httpOnly: true, sameSite: "Lax", path: "/", maxAge: 12 * 3600 });
    setCookie(c, "hermes_session_rt", rt, { httpOnly: true, sameSite: "Lax", path: "/", maxAge: 30 * 86400 });
    setCookie(c, "hermes_session_provider", "basic", { httpOnly: true, sameSite: "Lax", path: "/", maxAge: 30 * 86400 });
    return c.json({ ok: true, next: "/" });
  });

  dash.use("/api/*", async (c, next) => {
    const path = new URL(c.req.url).pathname;
    if (["/api/status", "/api/health", "/api/auth/providers"].includes(path)) return next();
    if (!isAuthed(c)) return c.json({ error: "unauthenticated", detail: "Unauthorized" }, 401);
    return next();
  });

  dash.get("/api/auth/me", (c) => {
    const cookie = getCookie(c, "hermes_session_at");
    const p = verify(cookie);
    return c.json({ user_id: opts.username, provider: "basic", expires_at: p?.exp ?? null, email: null, display_name: opts.username, org_id: null });
  });
  dash.get("/api/system/stats", (c) => {
    const total = opts.os === "Windows" ? 32 * 2 ** 30 : 16 * 2 ** 30;
    const usedPct = 35 + ((Date.now() / 1000) % 40);
    const diskTotal = 512 * 2 ** 30;
    const diskPct = opts.name.endsWith("4") ? 91 : 40 + opts.name.length * 3;
    return c.json({
      system: opts.os === "macOS" ? "Darwin" : opts.os,
      platform: opts.os === "macOS" ? "macOS-15.6-arm64" : opts.os === "Windows" ? "Windows-11-10.0.26100" : "Linux-6.8",
      release: opts.os === "macOS" ? "24.6.0" : "11",
      arch: opts.os === "macOS" ? "arm64" : "AMD64",
      hostname: `${opts.name}.local`,
      python_version: "3.12.6",
      hermes_version: machine.version,
      cpu_count: opts.os === "Windows" ? 16 : 10,
      cpu_percent: Math.round((10 + Math.random() * 40) * 10) / 10,
      load_avg: [1.2, 1.0, 0.8],
      uptime_seconds: Math.floor(Date.now() / 1000 - bootTime),
      memory: { total, available: Math.floor(total * (1 - usedPct / 100)), used: Math.floor(total * usedPct / 100), percent: Math.round(usedPct * 10) / 10 },
      disk: { total: diskTotal, used: Math.floor(diskTotal * diskPct / 100), free: Math.floor(diskTotal * (1 - diskPct / 100)), percent: diskPct },
      process: { pid: 1234, rss: 180 * 2 ** 20, create_time: Math.floor(bootTime + 60), num_threads: 12 },
      psutil: true,
    });
  });

  dash.post("/api/gateway/start", (c) => {
    machine.gatewayState = "starting";
    setTimeout(() => { machine.gatewayRunning = true; machine.gatewayState = "running"; }, 1500);
    return c.json({ ok: true, pid: 5555, name: "gateway-start" });
  });
  dash.post("/api/gateway/stop", (c) => {
    machine.gatewayState = "stopping";
    setTimeout(() => { machine.gatewayRunning = false; machine.gatewayState = "stopped"; }, 800);
    return c.json({ ok: true, name: "gateway-stop" });
  });
  dash.post("/api/gateway/restart", (c) => {
    machine.gatewayState = "restarting";
    machine.gatewayRunning = false;
    const st = startAction("gateway-restart", 2500, ["Draining active agents...", "Stopping gateway...", "Starting gateway..."], () => {
      machine.gatewayRunning = true;
      machine.gatewayState = "running";
    });
    return c.json({ ok: true, pid: st.pid, name: "gateway-restart" });
  });
  dash.get("/api/hermes/update/check", (c) =>
    c.json({
      install_method: "git",
      current_version: machine.version,
      behind: machine.updateBehind,
      update_available: machine.updateBehind > 0,
      can_apply: true,
      update_command: "hermes update",
      message: null,
      commits: machine.updateBehind > 0 ? Array.from({ length: machine.updateBehind }, (_, i) => ({ sha: `abc${i}def`, summary: `Fix #${1000 + i}`, author: "nous", at: new Date(Date.now() - i * 86400e3).toISOString() })) : [],
    }),
  );
  dash.post("/api/hermes/update", (c) => {
    const existing = machine.actions.get("hermes-update");
    if (existing?.running) return c.json({ ok: true, pid: existing.pid, name: "hermes-update", already_running: true, action_id: existing.action_id });
    const st = startAction("hermes-update", 4000, ["Fetching origin...", "Updating dependencies...", "Rebuilding web UI...", "Restarting gateway..."], () => {
      if (machine.updateBehind > 0) {
        const parts = machine.version.split(".").map(Number);
        parts[2] = (parts[2] ?? 0) + 1;
        machine.version = parts.join(".");
        machine.updateBehind = 0;
      }
    });
    return c.json({ ok: true, pid: st.pid, name: "hermes-update", action_id: st.action_id });
  });
  dash.get("/api/actions/:name/status", (c) => {
    const name = c.req.param("name");
    const known = ["gateway-restart", "hermes-update", "doctor", "security-audit", "backup"];
    if (!known.includes(name)) return c.json({ detail: `Unknown action: ${name}` }, 404);
    const st = machine.actions.get(name);
    if (!st) return c.json({ name, running: false, exit_code: null, pid: null, lines: [] });
    const lines = Number(c.req.query("lines") ?? 200);
    const res: Record<string, unknown> = { name, running: st.running, exit_code: st.exit_code, pid: st.pid, lines: st.lines.slice(-lines) };
    if (name === "hermes-update" && !st.running) {
      res.action_id = st.action_id;
      res.receipt = { outcome: st.exit_code === 0 ? "success" : "failed", started_at: new Date(st.started).toISOString(), finished_at: new Date().toISOString(), post_version: machine.version, fleet_states: ["ok"] };
    }
    return c.json(res);
  });
  for (const op of ["doctor", "security-audit", "backup"] as const) {
    dash.post(`/api/ops/${op}`, (c) => {
      const st = startAction(op, 2000, [`Running ${op}...`, `${op}: 0 problems found`]);
      return c.json({ ok: true, pid: st.pid, name: op });
    });
  }
  dash.get("/api/logs", (c) => {
    const file = c.req.query("file") ?? "agent";
    const lines = Math.min(Number(c.req.query("lines") ?? 100), 500);
    const level = c.req.query("level");
    const search = c.req.query("search");
    let out = fakeLogLines(`${opts.name}/${file}`, 500);
    if (level && level.toUpperCase() !== "ALL") out = out.filter((l) => l.includes(` ${level.toUpperCase()} `));
    if (search) out = out.filter((l) => l.toLowerCase().includes(search.toLowerCase()));
    return c.json({ file, lines: out.slice(-lines) });
  });
  dash.get("/api/sessions", (c) => {
    const limit = Number(c.req.query("limit") ?? 20);
    const offset = Number(c.req.query("offset") ?? 0);
    return c.json({ sessions: sessions.slice(offset, offset + limit), total: sessions.length, limit, offset });
  });
  dash.get("/api/sessions/search", (c) => {
    const q = (c.req.query("q") ?? "").toLowerCase();
    const hits = sessions.filter((s) => s.title.toLowerCase().includes(q)).map((s) => ({ ...s, snippet: `...${q}...` }));
    return c.json({ results: hits, total: hits.length });
  });
  dash.get("/api/sessions/:id/messages", (c) => {
    const id = c.req.param("id");
    const msgs = Array.from({ length: 6 }, (_, i) => ({ role: i % 2 ? "assistant" : "user", content: `${id} message ${i + 1}`, timestamp: Date.now() / 1000 - (6 - i) * 60 }));
    return c.json({ session_id: id, messages: msgs, total: msgs.length });
  });
  dash.get("/api/cron/jobs", (c) => c.json({ jobs: cronJobs }));
  dash.post("/api/cron/jobs", async (c) => {
    const body = await c.req.json();
    const job = { id: `cron_${opts.name}_${randomBytes(3).toString("hex")}`, name: body.name ?? "job", prompt: body.prompt ?? "", schedule: body.schedule ?? "0 * * * *", enabled: true, paused: false, next_run: new Date(Date.now() + 3600e3).toISOString(), last_run: null, deliver: body.deliver ?? "local", profile: "default" };
    cronJobs.push(job);
    return c.json({ ok: true, job });
  });
  dash.post("/api/cron/jobs/:id/:action", (c) => {
    const job = cronJobs.find((j) => j.id === c.req.param("id"));
    if (!job) return c.json({ detail: "job not found" }, 404);
    const action = c.req.param("action");
    if (action === "pause") job.paused = true;
    if (action === "resume") job.paused = false;
    if (action === "trigger") job.last_run = new Date().toISOString();
    return c.json({ ok: true, job });
  });
  dash.delete("/api/cron/jobs/:id", (c) => {
    const i = cronJobs.findIndex((j) => j.id === c.req.param("id"));
    if (i < 0) return c.json({ detail: "job not found" }, 404);
    cronJobs.splice(i, 1);
    return c.json({ ok: true });
  });
  let config: Record<string, unknown> = { model: { default: "anthropic/claude-sonnet-5" }, approvals: { unattended_mode: "deny" }, gateway: { api_server: { enabled: true, port: opts.apiPort } } };
  const deepMerge = (a: Record<string, unknown>, b: Record<string, unknown>): Record<string, unknown> => {
    const out: Record<string, unknown> = { ...a };
    for (const [k, v] of Object.entries(b)) {
      const cur = out[k];
      out[k] = v && typeof v === "object" && !Array.isArray(v) && cur && typeof cur === "object" && !Array.isArray(cur) ? deepMerge(cur as Record<string, unknown>, v as Record<string, unknown>) : v;
    }
    return out;
  };
  const envVars: Record<string, string> = { API_SERVER_KEY: opts.apiKey, ANTHROPIC_API_KEY: "sk-ant-demo" };
  const envRows = () => ({ vars: ["API_SERVER_KEY", "ANTHROPIC_API_KEY", "OPENROUTER_API_KEY", "TELEGRAM_BOT_TOKEN", ...Object.keys(envVars)].filter((k, i, a) => a.indexOf(k) === i).map((name) => ({ name, set: name in envVars, value: name in envVars ? `${envVars[name].slice(0, 3)}***` : null, category: "LLM" })) });
  dash.get("/api/config", (c) => c.json(config));
  dash.put("/api/config", async (c) => {
    const body = await c.req.json();
    if (!body || typeof body.config !== "object") return c.json({ detail: "config required" }, 422);
    config = deepMerge(config, body.config);
    return c.json({ ok: true, config });
  });
  dash.get("/api/env", (c) => c.json(envRows()));
  dash.put("/api/env", async (c) => {
    const body = await c.req.json();
    if (!body?.key) return c.json({ detail: "key required" }, 422);
    envVars[String(body.key)] = String(body.value ?? "");
    return c.json({ ok: true, key: body.key });
  });
  dash.delete("/api/env", async (c) => {
    const body = await c.req.json().catch(() => ({}));
    if (!(body.key in envVars)) return c.json({ detail: `${body.key} not found in .env` }, 404);
    delete envVars[body.key];
    return c.json({ ok: true, found: true });
  });

  // ---- models / providers / profiles (dashboard) ----
  const providerCatalog = [
    { slug: "anthropic", name: "Anthropic", key_env: "ANTHROPIC_API_KEY", models: ["anthropic/claude-sonnet-5", "anthropic/claude-opus-5", "anthropic/claude-haiku-4-5"] },
    { slug: "openrouter", name: "OpenRouter", key_env: "OPENROUTER_API_KEY", models: ["anthropic/claude-sonnet-5", "openai/gpt-6-astra", "google/gemini-3-pro", "inclusionai/ring-2.6-1t:free"] },
    { slug: "openai", name: "OpenAI", key_env: "OPENAI_API_KEY", models: ["openai/gpt-6-astra", "openai/gpt-6-mini"] },
    { slug: "nous", name: "Nous Portal", key_env: "", models: ["nous-hermes-4"] },
  ];
  const customEndpoints: { id: string; name: string; base_url: string; model: string; models: string[] }[] = [];
  const mainModel = { provider: "anthropic", model: "anthropic/claude-sonnet-5", base_url: "" };
  const AUX_TASKS = ["vision", "compression", "approval", "web_extraction", "title_generation", "skills_hub_search", "mcp_routing", "triage_specification", "task_decomposition", "profile_description", "curator_review"];
  const aux: Record<string, { provider: string; model: string; base_url: string; reasoning_effort: string | null }> = Object.fromEntries(AUX_TASKS.map((t) => [t, { provider: "auto", model: "", base_url: "", reasoning_effort: null }]));
  const profiles: { name: string; path: string; is_default: boolean; model: string | null; provider: string | null; has_env: boolean; skill_count: number; gateway_running: boolean; description: string; display_name: string; soul: string }[] = [
    { name: "default", path: "~/.hermes", is_default: true, model: mainModel.model, provider: mainModel.provider, has_env: true, skill_count: 14, gateway_running: machine.gatewayRunning, description: "", display_name: "", soul: "You are Hermes, a helpful assistant." },
    ...(opts.name.endsWith("2") ? [{ name: "coder", path: "~/.hermes/profiles/coder", is_default: false, model: "openai/gpt-6-astra", provider: "openrouter", has_env: true, skill_count: 9, gateway_running: false, description: "Coding agent", display_name: "", soul: "You are a focused coding assistant." }] : []),
  ];
  let activeProfile = "default";
  const providerRows = () => providerCatalog.map((p) => {
    const authenticated = !p.key_env || p.key_env in envVars;
    return { slug: p.slug, name: p.name, is_current: p.slug === mainModel.provider, is_user_defined: false, authenticated, auth_type: p.key_env ? "api_key" : "oauth", key_env: p.key_env, models: authenticated ? p.models : [], warning: authenticated ? "" : `paste ${p.key_env} to activate`, source: "canonical" };
  }).concat(customEndpoints.map((e) => ({ slug: e.id, name: e.name, is_current: e.id === mainModel.provider, is_user_defined: true, authenticated: true, auth_type: "api_key", key_env: `${e.id.toUpperCase().replace(/-/g, "_")}_API_KEY`, models: e.models, warning: "", source: "config" })));
  dash.get("/api/model/options", (c) => c.json({ providers: providerRows(), current_provider: mainModel.provider, current_model: mainModel.model }));
  dash.get("/api/model/auxiliary", (c) => c.json({ tasks: AUX_TASKS.map((t) => ({ task: t, ...aux[t], local_endpoint: false })), main: { provider: mainModel.provider, model: mainModel.model } }));
  dash.post("/api/model/set", async (c) => {
    const b = await c.req.json();
    if (!["main", "auxiliary"].includes(b.scope)) return c.json({ detail: "scope must be 'main' or 'auxiliary'" }, 400);
    if (b.scope === "main") {
      if (!b.provider || !b.model) return c.json({ detail: "provider and model are required" }, 400);
      if (/opus/.test(b.model) && !b.confirm_expensive_model) return c.json({ ok: false, scope: "main", provider: b.provider, model: b.model, confirm_required: true, confirm_message: `${b.model} は高価なモデルです。続行しますか？` });
      Object.assign(mainModel, { provider: b.provider, model: b.model, base_url: b.base_url ?? "" });
      profiles[0].model = b.model; profiles[0].provider = b.provider;
      config = deepMerge(config, { model: { provider: b.provider, default: b.model } });
      return c.json({ ok: true, scope: "main", provider: b.provider, model: b.model });
    }
    if (b.task === "__reset__") { for (const t of AUX_TASKS) aux[t] = { provider: "auto", model: "", base_url: "", reasoning_effort: null }; return c.json({ ok: true, scope: "auxiliary", reset: true }); }
    const tasks = b.task ? [b.task] : AUX_TASKS;
    for (const t of tasks) { if (!aux[t]) return c.json({ detail: `unknown task ${t}` }, 400); aux[t] = { provider: b.provider || "auto", model: b.model || "", base_url: b.base_url ?? "", reasoning_effort: b.reasoning_effort ?? null }; }
    return c.json({ ok: true, scope: "auxiliary", task: b.task, provider: b.provider, model: b.model });
  });
  dash.get("/api/profiles", (c) => c.json({ profiles: profiles.map(({ soul, ...p }) => ({ ...p, gateway_running: p.is_default ? machine.gatewayRunning : p.gateway_running })) }));
  dash.post("/api/profiles", async (c) => {
    const b = await c.req.json();
    const name = String(b.name ?? "").trim().toLowerCase();
    if (!/^[a-z][a-z0-9_-]{0,31}$/.test(name)) return c.json({ detail: "invalid profile name" }, 400);
    if (profiles.some((p) => p.name === name)) return c.json({ detail: `profile '${name}' already exists` }, 400);
    const src = profiles.find((p) => p.name === (b.clone_from || "default")) ?? profiles[0];
    profiles.push({ name, path: `~/.hermes/profiles/${name}`, is_default: false, model: b.model || (b.clone_from ? src.model : null), provider: b.provider || (b.clone_from ? src.provider : null), has_env: Boolean(b.clone_from), skill_count: b.no_skills ? 0 : src.skill_count, gateway_running: false, description: b.description ?? "", display_name: "", soul: b.clone_from ? src.soul : "" });
    return c.json({ ok: true, name, path: `~/.hermes/profiles/${name}`, model_set: Boolean(b.provider && b.model), model_error: "", mcp_written: 0, skills_disabled: 0, hub_installs: [] });
  });
  dash.get("/api/profiles/active", (c) => c.json({ active: activeProfile, current: "default" }));
  dash.post("/api/profiles/active", async (c) => { const b = await c.req.json(); if (!profiles.some((p) => p.name === b.name)) return c.json({ detail: "profile not found" }, 404); activeProfile = b.name; return c.json({ ok: true, active: b.name }); });
  dash.patch("/api/profiles/:name", async (c) => { const p = profiles.find((x) => x.name === c.req.param("name")); if (!p) return c.json({ detail: "not found" }, 404); const b = await c.req.json(); if (p.is_default) { p.display_name = b.new_name; return c.json({ ok: true, name: "default", display_name: b.new_name, path: p.path }); } p.name = String(b.new_name).toLowerCase(); return c.json({ ok: true, name: p.name, path: p.path }); });
  dash.delete("/api/profiles/:name", (c) => { const i = profiles.findIndex((x) => x.name === c.req.param("name")); if (i < 0) return c.json({ detail: "not found" }, 404); if (profiles[i].is_default) return c.json({ detail: "cannot delete the default profile" }, 400); const [p] = profiles.splice(i, 1); if (activeProfile === p.name) activeProfile = "default"; return c.json({ ok: true, path: p.path }); });
  dash.put("/api/profiles/:name/model", async (c) => { const p = profiles.find((x) => x.name === c.req.param("name")); if (!p) return c.json({ detail: "not found" }, 404); const b = await c.req.json(); if (!b.provider || !b.model) return c.json({ detail: "provider and model are required" }, 400); p.provider = b.provider; p.model = b.model; if (p.is_default) Object.assign(mainModel, { provider: b.provider, model: b.model }); return c.json({ ok: true, provider: b.provider, model: b.model }); });
  dash.get("/api/profiles/:name/soul", (c) => { const p = profiles.find((x) => x.name === c.req.param("name")); return p ? c.json({ content: p.soul, path: `${p.path}/SOUL.md`, exists: p.soul !== "" }) : c.json({ detail: "not found" }, 404); });
  dash.put("/api/profiles/:name/soul", async (c) => { const p = profiles.find((x) => x.name === c.req.param("name")); if (!p) return c.json({ detail: "not found" }, 404); p.soul = String((await c.req.json()).content ?? ""); return c.json({ ok: true }); });
  dash.put("/api/profiles/:name/description", async (c) => { const p = profiles.find((x) => x.name === c.req.param("name")); if (!p) return c.json({ detail: "not found" }, 404); p.description = String((await c.req.json()).description ?? ""); return c.json({ ok: true }); });
  dash.get("/api/providers/custom-endpoints", (c) => c.json({ endpoints: customEndpoints, current: mainModel.provider }));
  dash.post("/api/providers/custom-endpoints", async (c) => {
    const b = await c.req.json();
    if (!b.name || !b.base_url) return c.json({ detail: "name and base_url required" }, 422);
    const id = (b.id || String(b.name)).toLowerCase().replace(/[^a-z0-9_-]+/g, "-").replace(/^-+|-+$/g, "") || "custom";
    const existing = customEndpoints.find((e) => e.id === id);
    const entry = { id, name: b.name, base_url: String(b.base_url).replace(/\/+$/, ""), model: b.model ?? "", models: Array.isArray(b.models) && b.models.length ? b.models : b.model ? [b.model] : ["local-model"] };
    if (existing) Object.assign(existing, entry); else customEndpoints.push(entry);
    if (b.api_key) envVars[`${id.toUpperCase().replace(/-/g, "_")}_API_KEY`] = String(b.api_key);
    if (b.make_default && entry.model) Object.assign(mainModel, { provider: id, model: entry.model, base_url: entry.base_url });
    return c.json({ ok: true, id, endpoints: customEndpoints, current: mainModel.provider });
  });
  dash.post("/api/providers/custom-endpoints/validate", async (c) => { const b = await c.req.json(); const url = String(b.base_url ?? "").replace(/\/+$/, ""); if (!url) return c.json({ ok: false, reachable: true, message: "Enter an endpoint URL first.", models: [] }); if (/unreachable|9999/.test(url)) return c.json({ ok: false, reachable: false, message: `Could not reach ${url}/models.`, models: [] }); return c.json({ ok: true, reachable: true, message: "", models: ["local-model", "local-model-small"] }); });
  dash.delete("/api/providers/custom-endpoints/:id", (c) => { const i = customEndpoints.findIndex((e) => e.id === c.req.param("id")); if (i < 0) return c.json({ detail: "custom endpoint not found" }, 404); const [e] = customEndpoints.splice(i, 1); if (mainModel.provider === e.id) Object.assign(mainModel, { provider: "anthropic", model: "anthropic/claude-sonnet-5", base_url: "" }); return c.json({ ok: true, endpoints: customEndpoints }); });
  dash.post("/api/providers/validate", async (c) => { const b = await c.req.json(); const v = String(b.value ?? ""); if (!v) return c.json({ ok: false, reachable: true, message: "Enter a value first." }); if (/bad|invalid/.test(v)) return c.json({ ok: false, reachable: true, message: "That API key was rejected. Double-check it and try again." }); return c.json({ ok: true, reachable: true, message: "" }); });

  // ---------------- api server (8642) ----------------
  const api = new Hono();
  const bearerOk = (c: { req: { header: (n: string) => string | undefined } }) => c.req.header("authorization") === `Bearer ${opts.apiKey}`;
  api.get("/health", (c) => c.json({ status: "ok" }));
  api.get("/v1/health", (c) => c.json({ status: "ok" }));
  api.use("*", async (c, next) => {
    const path = new URL(c.req.url).pathname;
    if (path === "/health" || path === "/v1/health") return next();
    if (!bearerOk(c)) return c.json({ error: { message: "Unauthorized", type: "authentication_error" } }, 401);
    return next();
  });
  api.get("/health/detailed", (c) =>
    c.json({
      status: machine.gatewayRunning ? "ok" : "degraded",
      readiness: { status: machine.gatewayRunning ? "ready" : "not_ready", configured_model: "anthropic/claude-sonnet-5", active_api_runs: [...machine.runs.values()].filter((r) => r.status === "running").length, disk_free_gb: 210 },
      platform: "hermes-agent",
      version: machine.version,
      gateway_state: machine.gatewayState,
      platforms: { api_server: { status: "connected" } },
      active_agents: 0,
      gateway_busy: false,
      gateway_drainable: true,
      exit_reason: null,
      updated_at: new Date().toISOString(),
      pid: 1234,
    }),
  );
  api.get("/v1/capabilities", (c) =>
    c.json({
      platform: "hermes-agent",
      version: machine.version,
      features: { run_status: true, run_events_sse: true, run_stop: true, run_steer: true, run_approval_response: true, tool_progress_events: true, approval_events: true, session_chat: true, session_chat_streaming: true, skills_api: true, session_continuity_header: "X-Hermes-Session-Id", session_key_header: "X-Hermes-Session-Key" },
      endpoints: { health: { method: "GET", path: "/health" }, runs: { method: "POST", path: "/v1/runs" }, run_events: { method: "GET", path: "/v1/runs/{run_id}/events" }, run_stop: { method: "POST", path: "/v1/runs/{run_id}/stop" } },
    }),
  );
  api.get("/v1/models", (c) => c.json({ object: "list", data: [{ id: "hermes-agent", object: "model", owned_by: "hermes" }, { id: "fast", object: "model", owned_by: "hermes" }] }));

  const runStatusJson = (r: RunState) => ({ object: "hermes.run", run_id: r.run_id, status: r.status, created_at: r.created_at, updated_at: r.updated_at, session_id: r.session_id, model: r.model, last_event: r.last_event, ...(r.output !== undefined ? { output: r.output } : {}), ...(r.error ? { error: r.error } : {}), ...(r.pendingApproval ? { approval: { event: "approval.request", request_id: r.pendingApproval.request_id } } : {}) });

  const emit = (r: RunState, ev: Record<string, unknown> | null) => {
    if (ev) { r.events.push(ev); r.last_event = String(ev.event); r.updated_at = Date.now() / 1000; }
    for (const l of r.listeners) l(ev);
    if (ev === null) r.closed = true;
  };

  const finish = (r: RunState, status: string, extra: Record<string, unknown>) => {
    r.status = status;
    Object.assign(r, extra);
    emit(r, { event: `run.${status}`, run_id: r.run_id, timestamp: Date.now() / 1000, completed: status === "completed", partial: false, interrupted: status === "cancelled", ...extra });
    emit(r, null);
  };

  const drive = (r: RunState) => {
    const text = r.input;
    const wantsApproval = /approve|承認/i.test(text);
    const wantsFail = /\bfail\b|失敗/i.test(text);
    const slow = /slow|遅/i.test(text);
    const unit = slow ? 900 : 250;
    const steps: (() => void)[] = [];
    steps.push(() => { r.status = "running"; });
    steps.push(() => emit(r, { event: "tool.started", run_id: r.run_id, timestamp: Date.now() / 1000, tool_name: "terminal", preview: `$ uname -a  (${opts.name})` }));
    steps.push(() => emit(r, { event: "tool.completed", run_id: r.run_id, timestamp: Date.now() / 1000, tool_name: "terminal", preview: `${opts.os} ${opts.name}.local` }));
    if (wantsApproval) {
      steps.push(() => {
        const request_id = `apr_${randomBytes(4).toString("hex")}`;
        r.pendingApproval = { request_id };
        r.status = "waiting_for_approval";
        emit(r, { event: "approval.request", run_id: r.run_id, timestamp: Date.now() / 1000, request_id, command: "rm -rf ./build", choices: ["once", "session", "always", "deny"] });
      });
    }
    const answer = wantsFail ? "" : `[${opts.name} / ${opts.os}] 受け取ったプロンプト: "${text.slice(0, 60)}". Hermes ${machine.version} が処理しました。ホスト名は ${opts.name}.local、CPU ${opts.os === "Windows" ? 16 : 10} コア、ディスク空き 210 GB です。`;
    const chunks = answer.match(/.{1,12}/g) ?? [];
    for (const ch of chunks) steps.push(() => emit(r, { event: "message.delta", run_id: r.run_id, timestamp: Date.now() / 1000, delta: ch }));
    steps.push(() => {
      if (wantsFail) finish(r, "failed", { error: "simulated failure: provider returned 500" });
      else finish(r, "completed", { output: answer, usage: { input_tokens: 120, output_tokens: chunks.length * 4 } });
    });
    let i = 0;
    const tick = () => {
      if (r.closed) return;
      if (r.status === "waiting_for_approval") { r.timer = setTimeout(tick, 200); return; }
      const step = steps[i++];
      if (!step) return;
      step();
      if (i < steps.length) r.timer = setTimeout(tick, unit);
    };
    r.timer = setTimeout(tick, unit);
  };

  api.post("/v1/runs", async (c) => {
    if (!machine.gatewayRunning) return c.json({ error: { message: "gateway not running", type: "server_error" } }, 503);
    const body = await c.req.json().catch(() => null);
    if (!body || !body.input) return c.json({ error: { message: "Missing 'input' field", type: "invalid_request_error" } }, 400);
    const input = typeof body.input === "string" ? body.input : body.input[body.input.length - 1]?.content ?? "";
    const idem = c.req.header("Idempotency-Key");
    if (idem) {
      for (const r of machine.runs.values()) if ((r as RunState & { idem?: string }).idem === idem) return c.json({ run_id: r.run_id, status: r.status, replayed: true }, 202);
    }
    const run_id = `run_${randomBytes(16).toString("hex")}`;
    const r: RunState & { idem?: string } = { run_id, status: "started", created_at: Date.now() / 1000, updated_at: Date.now() / 1000, session_id: body.session_id ?? c.req.header("X-Hermes-Session-Id") ?? run_id, model: body.model ?? "hermes-agent", input, listeners: new Set(), events: [], closed: false, idem };
    machine.runs.set(run_id, r);
    drive(r);
    return c.json({ run_id, status: "started", replayed: false }, 202);
  });
  api.get("/v1/runs/:id", (c) => {
    const r = machine.runs.get(c.req.param("id"));
    if (!r) return c.json({ error: { message: `Run not found`, code: "run_not_found" } }, 404);
    return c.json(runStatusJson(r));
  });
  api.get("/v1/runs/:id/events", (c) => {
    const r = machine.runs.get(c.req.param("id"));
    if (!r) return c.json({ error: { message: `Run not found`, code: "run_not_found" } }, 404);
    return streamSSE(c, async (stream) => {
      for (const ev of r.events) await stream.write(`data: ${JSON.stringify(ev)}\n\n`);
      if (r.closed) { await stream.write(": stream closed\n\n"); return; }
      await new Promise<void>((resolve) => {
        const listener = (ev: Record<string, unknown> | null) => {
          if (ev === null) { r.listeners.delete(listener); stream.write(": stream closed\n\n").then(() => resolve()); return; }
          stream.write(`data: ${JSON.stringify(ev)}\n\n`).catch(() => {});
        };
        r.listeners.add(listener);
        const ka = setInterval(() => stream.write(": keepalive\n\n").catch(() => {}), 10_000);
        stream.onAbort(() => { clearInterval(ka); r.listeners.delete(listener); resolve(); });
      });
    });
  });
  api.post("/v1/runs/:id/stop", (c) => {
    const r = machine.runs.get(c.req.param("id"));
    if (!r) return c.json({ error: { message: `Run not found`, code: "run_not_found" } }, 404);
    if (!r.closed) { if (r.timer) clearTimeout(r.timer); finish(r, "cancelled", {}); }
    return c.json({ ok: true, run_id: r.run_id, status: r.status });
  });
  api.post("/v1/runs/:id/approval", async (c) => {
    const r = machine.runs.get(c.req.param("id"));
    if (!r) return c.json({ error: { message: `Run not found`, code: "run_not_found" } }, 404);
    const body = await c.req.json().catch(() => ({}));
    if (!r.pendingApproval) return c.json({ error: { message: "no pending approval" } }, 409);
    const choice = String(body.choice ?? "");
    r.pendingApproval = undefined;
    if (choice === "deny") { if (r.timer) clearTimeout(r.timer); finish(r, "failed", { error: "approval denied" }); }
    else r.status = "running";
    return c.json({ ok: true, choice });
  });
  api.post("/v1/runs/:id/steer", async (c) => c.json({ ok: true }));
  api.get("/api/sessions", (c) => c.json({ sessions: sessions.slice(0, 20), total: sessions.length }));
  api.get("/api/jobs", (c) => c.json({ jobs: cronJobs }));

  machine.close = async () => {
    for (const r of machine.runs.values()) if (r.timer) clearTimeout(r.timer);
  };
  return { machine, dash, api };
}

export function startMockMachine(o: MockMachineOptions): MockMachine {
  const { machine, dash, api } = createMockMachine(o);
  const servers: ServerType[] = [];
  servers.push(serve({ fetch: dash.fetch, port: machine.opts.dashboardPort, hostname: machine.opts.host }));
  servers.push(serve({ fetch: api.fetch, port: machine.opts.apiPort, hostname: machine.opts.host }));
  const closeTimers = machine.close;
  machine.close = () =>
    Promise.all(servers.map((s) => new Promise<void>((res) => s.close(() => res())))).then(closeTimers);
  return machine;
}

/**
 * A fetch() that routes `http://<name>.demo:9119` / `:8642` to in-process mock apps, so the console
 * can run a whole demo fleet inside one process (no sockets). Anything else falls through to real fetch.
 */
export function inProcessFleetFetch(fleet: MockMachineApps[], fallback: typeof fetch = globalThis.fetch): typeof fetch {
  const byHost = new Map<string, Hono>();
  for (const m of fleet) {
    byHost.set(`${m.machine.opts.name}.demo:9119`, m.dash);
    byHost.set(`${m.machine.opts.name}.demo:8642`, m.api);
  }
  return (async (input: string | URL | Request, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    const host = new URL(url).host;
    const app = byHost.get(host);
    if (!app) return fallback(input as string, init);
    const req = new Request(url, init);
    return app.fetch(req);
  }) as typeof fetch;
}

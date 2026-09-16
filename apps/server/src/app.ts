import { Hono } from "hono";
import { cors } from "hono/cors";
import { streamSSE } from "hono/streaming";
import { serveStatic } from "@hono/node-server/serve-static";
import { readFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { HermesHttpError, probeMachine } from "@fleet/hermes-client";
import type { ServerConfig } from "./config.js";
import { AuthError, AuthService, extractToken } from "./auth.js";
import { MachineRepo, ValidationError, type MachineInput } from "./machines.js";
import { FleetPoller } from "./fleet.js";
import { AuditLog } from "./audit.js";
import { RunManager } from "./runs.js";
import { OpsManager, type OpsKind } from "./ops.js";

export interface AppDeps {
  config: ServerConfig;
  auth: AuthService;
  repo: MachineRepo;
  poller: FleetPoller;
  audit: AuditLog;
  runs: RunManager;
  ops: OpsManager;
}

const OPS_KINDS: OpsKind[] = ["gateway.start", "gateway.stop", "gateway.restart", "update", "doctor", "security-audit", "backup"];

export function buildApp(d: AppDeps): Hono {
  const app = new Hono();
  app.use(
    "/api/*",
    cors({
      origin: (origin) => (d.config.corsOrigins.includes(origin) ? origin : ""),
      allowHeaders: ["authorization", "content-type"],
      allowMethods: ["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    }),
  );

  app.onError((err, c) => {
    if (err instanceof ValidationError) return c.json({ error: err.message }, 400);
    if (err instanceof AuthError) return c.json({ error: err.message }, err.status);
    if (err instanceof HermesHttpError) return c.json({ error: `Hermes: ${err.message}`, status: err.status }, 502);
    if ((err as { code?: string }).code === "ERR_SQLITE_ERROR" && /constraint/i.test(err.message)) return c.json({ error: "同じ名前のマシンが既に登録されています" }, 400);
    console.error(err);
    return c.json({ error: err instanceof Error ? err.message : String(err) }, 500);
  });

  // ---- public ----
  app.get("/api/health", (c) => c.json({ status: "ok", version: "0.1.0" }));
  app.get("/api/auth/state", (c) => c.json({ configured: d.auth.isConfigured() }));
  app.post("/api/auth/setup", async (c) => {
    const body = await c.req.json<{ password?: string }>();
    try {
      d.auth.setup(String(body.password ?? ""));
    } catch (e) {
      return c.json({ error: (e as Error).message }, 400);
    }
    const ip = c.req.header("x-forwarded-for") ?? "local";
    const s = d.auth.login(String(body.password), ip, c.req.header("user-agent") ?? "");
    d.audit.record("auth.setup");
    return c.json({ token: s.token, expiresAt: s.expiresAt });
  });
  app.post("/api/auth/login", async (c) => {
    const body = await c.req.json<{ password?: string }>();
    const ip = c.req.header("x-forwarded-for") ?? "local";
    const s = d.auth.login(String(body.password ?? ""), ip, c.req.header("user-agent") ?? "");
    d.audit.record("auth.login", { detail: { ua: c.req.header("user-agent") ?? "" } });
    return c.json({ token: s.token, expiresAt: s.expiresAt });
  });

  // ---- authenticated ----
  const api = new Hono();
  api.use("*", d.auth.middleware());

  api.post("/auth/logout", (c) => {
    const t = extractToken(c);
    if (t) d.auth.logout(t);
    return c.json({ ok: true });
  });
  api.post("/auth/password", async (c) => {
    const body = await c.req.json<{ current?: string; next?: string }>();
    try {
      d.auth.changePassword(String(body.current ?? ""), String(body.next ?? ""));
    } catch (e) {
      return c.json({ error: (e as Error).message }, 400);
    }
    d.audit.record("auth.password_changed");
    return c.json({ ok: true, reloginRequired: true });
  });
  api.get("/auth/me", (c) => c.json({ ok: true }));

  // machines
  api.get("/machines", (c) => c.json({ machines: d.repo.list() }));
  api.post("/machines", async (c) => {
    const input = (await c.req.json()) as MachineInput;
    const m = d.repo.create(input);
    d.audit.record("machine.create", { machineId: m.id, machineName: m.name });
    void d.poller.poll(m.id, { forceUpdateCheck: true });
    return c.json({ machine: m }, 201);
  });
  api.post("/machines/test", async (c) => {
    const input = (await c.req.json()) as MachineInput;
    const { dashboard, api: apiClient } = d.repo.transientClients(input);
    return c.json(await probeMachine(dashboard, apiClient));
  });
  api.get("/machines/:id", (c) => {
    const m = d.repo.get(c.req.param("id"));
    return m ? c.json({ machine: m, snapshot: d.poller.get(m.id) }) : c.json({ error: "not found" }, 404);
  });
  api.put("/machines/:id", async (c) => {
    const input = (await c.req.json()) as Partial<MachineInput>;
    const m = d.repo.update(c.req.param("id"), input);
    if (!m) return c.json({ error: "not found" }, 404);
    d.audit.record("machine.update", { machineId: m.id, machineName: m.name });
    void d.poller.poll(m.id, { forceUpdateCheck: true });
    return c.json({ machine: m });
  });
  api.delete("/machines/:id", (c) => {
    const m = d.repo.get(c.req.param("id"));
    if (!m) return c.json({ error: "not found" }, 404);
    d.repo.delete(m.id);
    d.audit.record("machine.delete", { machineId: m.id, machineName: m.name });
    return c.json({ ok: true });
  });
  api.post("/machines/:id/test", async (c) => {
    const cl = d.repo.clients(c.req.param("id"));
    if (!cl) return c.json({ error: "not found" }, 404);
    const result = await probeMachine(cl.dashboard, cl.api);
    void d.poller.poll(cl.machine.id);
    return c.json(result);
  });
  api.post("/machines/:id/refresh", async (c) => {
    const snap = await d.poller.poll(c.req.param("id"), { forceUpdateCheck: c.req.query("update") === "1" });
    return snap ? c.json({ snapshot: snap }) : c.json({ error: "not found" }, 404);
  });

  // per-machine proxies (read-mostly)
  const withClients = (c: { req: { param: (k: string) => string } }) => {
    const cl = d.repo.clients(c.req.param("id"));
    if (!cl) throw new ValidationError("machine not found");
    if (!cl.dashboard) throw new ValidationError("dashboard URL not configured for this machine");
    return cl;
  };
  api.get("/machines/:id/logs", async (c) => {
    const { dashboard } = withClients(c);
    return c.json(await dashboard!.logs({ file: c.req.query("file") ?? "agent", lines: Number(c.req.query("lines") ?? 200), level: c.req.query("level"), search: c.req.query("search") }));
  });
  api.get("/machines/:id/sessions", async (c) => {
    const { dashboard } = withClients(c);
    return c.json(await dashboard!.sessions({ limit: Number(c.req.query("limit") ?? 20), offset: Number(c.req.query("offset") ?? 0), order: "recent" }));
  });
  api.get("/machines/:id/sessions/search", async (c) => {
    const { dashboard } = withClients(c);
    return c.json(await dashboard!.sessionSearch(c.req.query("q") ?? "", Number(c.req.query("limit") ?? 20)));
  });
  api.get("/machines/:id/sessions/:sid/messages", async (c) => {
    const { dashboard } = withClients(c);
    return c.json(await dashboard!.sessionMessages(c.req.param("sid"), { limit: Number(c.req.query("limit") ?? 100) }));
  });
  api.get("/machines/:id/cron", async (c) => {
    const { dashboard } = withClients(c);
    return c.json(await dashboard!.cronJobs());
  });
  api.post("/machines/:id/cron", async (c) => {
    const { dashboard, machine } = withClients(c);
    const body = await c.req.json();
    const r = await dashboard!.cronCreate(body);
    d.audit.record("cron.create", { machineId: machine.id, machineName: machine.name, detail: body });
    return c.json(r);
  });
  api.post("/machines/:id/cron/:jid/:action", async (c) => {
    const { dashboard, machine } = withClients(c);
    const action = c.req.param("action");
    if (!["pause", "resume", "trigger"].includes(action)) return c.json({ error: "bad action" }, 400);
    const r = await dashboard!.cronAction(c.req.param("jid"), action as "pause" | "resume" | "trigger");
    d.audit.record(`cron.${action}`, { machineId: machine.id, machineName: machine.name, detail: { job: c.req.param("jid") } });
    return c.json(r);
  });
  api.delete("/machines/:id/cron/:jid", async (c) => {
    const { dashboard, machine } = withClients(c);
    const r = await dashboard!.cronDelete(c.req.param("jid"));
    d.audit.record("cron.delete", { machineId: machine.id, machineName: machine.name, detail: { job: c.req.param("jid") } });
    return c.json(r ?? { ok: true });
  });
  api.get("/machines/:id/config", async (c) => {
    const { dashboard } = withClients(c);
    return c.json(await dashboard!.config());
  });
  api.get("/machines/:id/env", async (c) => {
    const { dashboard } = withClients(c);
    return c.json(await dashboard!.env());
  });
  api.get("/machines/:id/update-check", async (c) => {
    const { dashboard } = withClients(c);
    return c.json(await dashboard!.updateCheck(c.req.query("force") === "1"));
  });
  api.get("/machines/:id/action/:name", async (c) => {
    const { dashboard } = withClients(c);
    return c.json(await dashboard!.actionStatus(c.req.param("name"), Number(c.req.query("lines") ?? 200)));
  });

  // fleet overview + stream
  api.get("/fleet/overview", (c) => {
    d.poller.touch();
    return c.json({ machines: d.poller.all(), at: Date.now() });
  });
  api.post("/fleet/refresh", async (c) => {
    await d.poller.pollAll();
    return c.json({ machines: d.poller.all(), at: Date.now() });
  });
  api.get("/fleet/stream", (c) => {
    d.poller.touch();
    return streamSSE(c, async (stream) => {
      await stream.writeSSE({ event: "overview", data: JSON.stringify({ machines: d.poller.all(), at: Date.now() }) });
      let alive = true;
      const unsub = d.poller.subscribe((snap) => {
        if (alive) stream.writeSSE({ event: "machine", data: JSON.stringify(snap) }).catch(() => {});
      });
      const hb = setInterval(() => {
        d.poller.touch();
        stream.writeSSE({ event: "ping", data: String(Date.now()) }).catch(() => {});
      }, 15_000);
      await new Promise<void>((resolve) => {
        stream.onAbort(() => {
          alive = false;
          clearInterval(hb);
          unsub();
          resolve();
        });
      });
    });
  });

  // batch ops
  api.get("/ops", (c) => c.json({ jobs: d.ops.list() }));
  api.post("/ops", async (c) => {
    const body = await c.req.json<{ kind: OpsKind; machineIds: string[]; canary?: boolean }>();
    if (!OPS_KINDS.includes(body.kind)) return c.json({ error: "unknown kind" }, 400);
    if (!Array.isArray(body.machineIds) || body.machineIds.length === 0) return c.json({ error: "machineIds required" }, 400);
    const job = d.ops.start(body.kind, body.machineIds, { canary: body.canary });
    return c.json({ job }, 202);
  });
  api.get("/ops/:id", (c) => {
    const job = d.ops.get(c.req.param("id"));
    return job ? c.json({ job }) : c.json({ error: "not found" }, 404);
  });
  api.get("/ops/:id/stream", (c) => {
    const job = d.ops.get(c.req.param("id"));
    if (!job) return c.json({ error: "not found" }, 404);
    return streamSSE(c, async (stream) => {
      await stream.writeSSE({ event: "job", data: JSON.stringify(job) });
      if (job.status !== "running") return;
      await new Promise<void>((resolve) => {
        const unsub = d.ops.subscribe(job.id, (j) => {
          stream.writeSSE({ event: "job", data: JSON.stringify(j) }).catch(() => {});
          if (j.status !== "running") {
            unsub();
            resolve();
          }
        });
        stream.onAbort(() => {
          unsub();
          resolve();
        });
      });
    });
  });

  // prompt runs
  api.post("/runs", async (c) => {
    const body = await c.req.json<{ machineIds: string[]; prompt: string; model?: string; sessionByMachine?: Record<string, string> }>();
    if (!Array.isArray(body.machineIds) || body.machineIds.length === 0) return c.json({ error: "machineIds required" }, 400);
    if (!body.prompt || !body.prompt.trim()) return c.json({ error: "prompt required" }, 400);
    const res = d.runs.createBatch(body.machineIds, body.prompt, { model: body.model, sessionByMachine: body.sessionByMachine });
    return c.json(res, 202);
  });
  api.get("/runs/batches", (c) => c.json({ batches: d.runs.recentBatches(Number(c.req.query("limit") ?? 30)) }));
  api.get("/runs/batches/:id", (c) => c.json({ runs: d.runs.batch(c.req.param("id")) }));
  api.get("/runs/batches/:id/stream", (c) => {
    const batchId = c.req.param("id");
    return streamSSE(c, async (stream) => {
      await stream.writeSSE({ event: "runs", data: JSON.stringify({ runs: d.runs.batch(batchId) }) });
      await new Promise<void>((resolve) => {
        const unsub = d.runs.subscribe(batchId, (ev) => {
          stream.writeSSE({ event: "run", data: JSON.stringify(ev) }).catch(() => {});
        });
        const hb = setInterval(() => stream.writeSSE({ event: "ping", data: String(Date.now()) }).catch(() => {}), 15_000);
        stream.onAbort(() => {
          clearInterval(hb);
          unsub();
          resolve();
        });
      });
    });
  });
  api.post("/runs/:id/stop", async (c) => c.json({ ok: await d.runs.stop(c.req.param("id")) }));
  api.post("/runs/:id/approval", async (c) => {
    const body = await c.req.json<{ choice: "once" | "session" | "always" | "deny"; request_id?: string }>();
    return c.json({ result: await d.runs.approve(c.req.param("id"), body.choice, body.request_id) });
  });

  api.get("/audit", (c) => c.json({ entries: d.audit.list(Number(c.req.query("limit") ?? 100), Number(c.req.query("offset") ?? 0)) }));

  app.route("/api", api);

  // static SPA (production)
  if (d.config.staticDir && existsSync(d.config.staticDir)) {
    const indexHtml = readFileSync(join(d.config.staticDir, "index.html"), "utf8");
    app.use("/*", serveStatic({ root: d.config.staticDir, rewriteRequestPath: (p) => p }));
    app.get("*", (c) => (c.req.path.startsWith("/api/") ? c.json({ error: "not found" }, 404) : c.html(indexHtml)));
  }
  return app;
}

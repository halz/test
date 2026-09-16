import { serve } from "@hono/node-server";
import { loadConfig } from "./config.js";
import { openDb } from "./db.js";
import { loadMasterKey } from "./crypto.js";
import { AuthService } from "./auth.js";
import { MachineRepo } from "./machines.js";
import { FleetPoller } from "./fleet.js";
import { AuditLog } from "./audit.js";
import { RunManager } from "./runs.js";
import { OpsManager } from "./ops.js";
import { buildApp } from "./app.js";

export function createServer(env: NodeJS.ProcessEnv = process.env) {
  const config = loadConfig(env);
  const db = openDb(config.dbPath);
  const key = loadMasterKey(env, config.keyFile);
  const auth = new AuthService(db);
  const repo = new MachineRepo(db, key, config.requestTimeoutMs);
  const audit = new AuditLog(db);
  const poller = new FleetPoller(repo, config.pollIntervalMs);
  const runs = new RunManager(db, repo, audit);
  const ops = new OpsManager(repo, audit, poller);
  const app = buildApp({ config, auth, repo, poller, audit, runs, ops });
  if (env.FLEET_DEMO_SEED === "1" && repo.list().length === 0) seedDemo(repo);
  return { config, app, poller, db };
}

function seedDemo(repo: MachineRepo): void {
  for (let i = 0; i < 6; i++) {
    const isWin = i === 5;
    repo.create({
      name: isWin ? "win-1" : `mac-${i + 1}`,
      os: isWin ? "Windows" : "macOS",
      tags: isWin ? ["demo", "windows"] : ["demo", "mac"],
      dashboardUrl: `http://127.0.0.1:${19119 + i * 2}`,
      dashboardAuthKind: "basic",
      dashboardUsername: "admin",
      dashboardPassword: "hermes",
      apiUrl: `http://127.0.0.1:${19120 + i * 2}`,
      apiKey: `mock-key-${i + 1}`,
      notes: "demo (mock-hermes)",
      sortOrder: i,
    });
  }
  console.log("[fleet-console] demo seed: registered 6 mock machines");
}

const isMain = process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href;
if (isMain) {
  const { config, app, poller } = createServer();
  poller.start();
  const server = serve({ fetch: app.fetch, port: config.port, hostname: config.host }, (info) => {
    console.log(`[fleet-console] listening on http://${info.address}:${info.port}  data=${config.dataDir}${config.staticDir ? `  static=${config.staticDir}` : ""}`);
  });
  const shutdown = () => {
    poller.stop();
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 2000).unref();
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}

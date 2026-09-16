// Hosted demo entry (Vercel Node function): the console + 6 mock Hermes machines in one process.
// State is in-memory per instance; the demo password yields a stateless session token.
import { randomBytes } from "node:crypto";
import { getRequestListener } from "@hono/node-server";
import { createMockMachine, defaultFleet, inProcessFleetFetch } from "@fleet/mock-hermes";
import { loadConfig } from "./config.js";
import { openDb } from "./db.js";
import { AuthService } from "./auth.js";
import { MachineRepo } from "./machines.js";
import { FleetPoller } from "./fleet.js";
import { AuditLog } from "./audit.js";
import { RunManager } from "./runs.js";
import { OpsManager } from "./ops.js";
import { Distributor } from "./distribute.js";
import { buildApp } from "./app.js";

const DEMO_PASSWORD = process.env.FLEET_DEMO_PASSWORD || "demo";

const config = loadConfig({ ...process.env, FLEET_DEMO_PASSWORD: DEMO_PASSWORD, FLEET_DATA_DIR: "/tmp/fleet-demo", FLEET_POLL_INTERVAL_MS: process.env.FLEET_POLL_INTERVAL_MS ?? "8000" });
const db = openDb(":memory:");
const key = randomBytes(32);
const fleet = defaultFleet().map((f) => createMockMachine({ ...f, host: "127.0.0.1" }));
const fetchImpl = inProcessFleetFetch(fleet);
const auth = new AuthService(db, DEMO_PASSWORD);
const repo = new MachineRepo(db, key, config.requestTimeoutMs, fetchImpl);
const audit = new AuditLog(db);
const poller = new FleetPoller(repo, config.pollIntervalMs);
const runs = new RunManager(db, repo, audit);
const ops = new OpsManager(repo, audit, poller);
const distributor = new Distributor(repo, audit);
for (const [i, m] of fleet.entries()) {
  repo.create({
    name: m.machine.opts.name,
    os: m.machine.opts.os,
    tags: m.machine.opts.os === "Windows" ? ["demo", "windows"] : ["demo", "mac"],
    dashboardUrl: `http://${m.machine.opts.name}.demo:9119`,
    dashboardAuthKind: "basic",
    dashboardUsername: m.machine.opts.username,
    dashboardPassword: m.machine.opts.password,
    apiUrl: `http://${m.machine.opts.name}.demo:8642`,
    apiKey: m.machine.opts.apiKey,
    notes: "デモ（モック Hermes）",
    sortOrder: i,
  });
}
audit.record("demo.boot", { detail: { machines: fleet.length } });

export const app = buildApp({ config, auth, repo, poller, audit, runs, ops, distributor });
// Classic Node (req, res) handler: what the Vercel Node launcher expects; streams SSE fine.
export default getRequestListener(app.fetch);

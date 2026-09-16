#!/usr/bin/env node
// Demo mode: start 6 mock Hermes machines + the console server (pre-registered) in one process tree.
// Usage: npm run demo   → open http://127.0.0.1:8080  (first visit asks you to set the admin password)
import { spawn } from "node:child_process";
import { mkdirSync, existsSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(new URL("..", import.meta.url).pathname);
const port = process.env.FLEET_PORT ?? "8080";
const dataDir = resolve(root, process.env.FLEET_DATA_DIR ?? "data-demo");
mkdirSync(dataDir, { recursive: true });
const staticDir = resolve(root, "apps/web/dist");
if (!existsSync(staticDir)) console.warn("[demo] apps/web/dist not found: run `npm run build` first for the UI (API still works).");

const mock = spawn(process.execPath, [resolve(root, "apps/mock-hermes/dist/cli.js"), "--count", "6", "--base-port", "19119"], { stdio: "inherit" });
await new Promise((r) => setTimeout(r, 800));
const server = spawn(process.execPath, [resolve(root, "apps/server/dist/index.js")], {
  stdio: "inherit",
  env: { ...process.env, FLEET_PORT: port, FLEET_DATA_DIR: dataDir, FLEET_STATIC_DIR: staticDir, FLEET_POLL_INTERVAL_MS: process.env.FLEET_POLL_INTERVAL_MS ?? "10000", FLEET_DEMO_SEED: "1" },
});
const stop = () => { mock.kill("SIGTERM"); server.kill("SIGTERM"); };
process.on("SIGINT", () => { stop(); process.exit(0); });
process.on("SIGTERM", () => { stop(); process.exit(0); });
server.on("exit", (code) => { mock.kill("SIGTERM"); process.exit(code ?? 0); });
console.log(`[demo] console: http://127.0.0.1:${port}   (mock machines on 19119-19130, login admin/hermes, api keys mock-key-1..6)`);

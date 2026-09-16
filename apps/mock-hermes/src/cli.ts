#!/usr/bin/env node
import { writeFileSync } from "node:fs";
import { defaultFleet, startMockMachine } from "./index.js";

const args = process.argv.slice(2);
const get = (flag: string, def: string) => {
  const i = args.indexOf(flag);
  return i >= 0 && args[i + 1] ? args[i + 1] : def;
};
const count = Number(get("--count", "6"));
const basePort = Number(get("--base-port", "19119"));
const outFile = get("--out", "");

const fleet = defaultFleet(basePort, count);
const machines = fleet.map((f) => startMockMachine(f));
const table = fleet.map((f) => ({
  name: f.name,
  os: f.os,
  dashboardUrl: `http://127.0.0.1:${f.dashboardPort}`,
  apiUrl: `http://127.0.0.1:${f.apiPort}`,
  username: f.username,
  password: f.password,
  apiKey: f.apiKey,
}));
if (outFile) writeFileSync(outFile, JSON.stringify(table, null, 2));
console.log(`[mock-hermes] ${machines.length} machines up:`);
for (const t of table) console.log(`  ${t.name.padEnd(6)} ${t.os.padEnd(8)} dashboard=${t.dashboardUrl} api=${t.apiUrl} key=${t.apiKey} login=${t.username}/${t.password}`);

const shutdown = () => Promise.all(machines.map((m) => m.close())).then(() => process.exit(0));
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);

const B = process.env.FLEET_URL ?? "http://127.0.0.1:18080";
let token = "";
const j = async (method, path, body, extra = {}) => {
  const res = await fetch(B + path, { method, headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}), ...extra }, body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  let data; try { data = JSON.parse(text); } catch { data = text; }
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${text.slice(0, 200)}`);
  return data;
};
const assert = (cond, msg) => { if (!cond) throw new Error("ASSERT: " + msg); console.log("  ok:", msg); };

console.log("auth state", await j("GET", "/api/auth/state"));
const setup = await j("POST", "/api/auth/setup", { password: "correct horse battery" });
token = setup.token;
assert(token, "setup returns token");
let r401 = await fetch(B + "/api/machines"); assert(r401.status === 401, "unauthenticated is 401");
token = "";
let bad = await fetch(B + "/api/auth/login", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ password: "nope" }) });
assert(bad.status === 401, "wrong password 401");
token = (await j("POST", "/api/auth/login", { password: "correct horse battery" })).token;

// add 6 machines
const fleet = [];
for (let i = 0; i < 6; i++) {
  const isWin = i === 5;
  const m = { name: isWin ? "win-1" : `mac-${i + 1}`, os: isWin ? "Windows" : "macOS", tags: isWin ? ["win"] : ["mac"], dashboardUrl: `http://127.0.0.1:${19119 + i * 2}`, dashboardAuthKind: "basic", dashboardUsername: "admin", dashboardPassword: "hermes", apiUrl: `http://127.0.0.1:${19120 + i * 2}`, apiKey: `mock-key-${i + 1}` };
  if (i === 0) { const t = await j("POST", "/api/machines/test", m); assert(t.dashboard.ok && t.apiServer.ok, "transient test ok"); }
  fleet.push((await j("POST", "/api/machines", m)).machine);
}
// a bogus one (offline)
fleet.push((await j("POST", "/api/machines", { name: "ghost", dashboardUrl: "http://127.0.0.1:1", apiUrl: "http://127.0.0.1:2", apiKey: "x" })).machine);
assert((await j("GET", "/api/machines")).machines.length === 7, "7 machines listed");
assert(!JSON.stringify(await j("GET", "/api/machines")).includes("hermes\""), "password not leaked in list");
let dup = await fetch(B + "/api/machines", { method: "POST", headers: { "content-type": "application/json", authorization: `Bearer ${token}` }, body: JSON.stringify({ name: "MAC-1", dashboardUrl: "http://x" }) });
assert(dup.status === 500 || dup.status === 400, "duplicate name rejected (" + dup.status + ")");

await j("POST", "/api/fleet/refresh");
const ov = await j("GET", "/api/fleet/overview");
const byName = Object.fromEntries(ov.machines.map((s) => [s.machine.name, s]));
assert(byName["mac-1"].online && byName["mac-1"].version === "0.23.1", "mac-1 online with version");
assert(byName["mac-1"].dashboard.stats.hostname === "mac-1.local", "system stats present");
assert(byName["win-1"].alerts.some((a) => a.code === "gateway_stopped"), "win-1 gateway_stopped alert");
assert(byName["mac-3"].update?.update_available && byName["mac-3"].alerts.some((a) => a.code === "update"), "mac-3 update alert");
assert(byName["mac-4"].alerts.some((a) => a.code === "disk"), "mac-4 disk alert");
assert(!byName["ghost"].online && byName["ghost"].alerts[0].code === "offline", "ghost offline");

// proxies
const logs = await j("GET", `/api/machines/${fleet[0].id}/logs?lines=5&level=ERROR`);
assert(logs.lines.length === 5 && logs.lines.every((l) => l.includes(" ERROR ")), "logs proxy with level filter");
assert((await j("GET", `/api/machines/${fleet[0].id}/sessions`)).sessions.length > 0, "sessions proxy");
const cron = await j("GET", `/api/machines/${fleet[0].id}/cron`);
assert(cron.jobs.length === 2, "cron proxy");
await j("POST", `/api/machines/${fleet[0].id}/cron/${cron.jobs[0].id}/pause`);
assert((await j("GET", `/api/machines/${fleet[0].id}/cron`)).jobs[0].paused === true, "cron pause via proxy");

// batch restart mac-1, mac-2 + ghost
const job = (await j("POST", "/api/ops", { kind: "gateway.restart", machineIds: [fleet[0].id, fleet[1].id, fleet[6].id] })).job;
let jb;
for (let i = 0; i < 60; i++) { await new Promise((r) => setTimeout(r, 500)); jb = (await j("GET", `/api/ops/${job.id}`)).job; if (jb.status !== "running") break; }
console.log("  restart job:", jb.status, jb.machines.map((m) => `${m.machineName}:${m.status}`).join(" "));
assert(jb.status === "partial" && jb.machines.filter((m) => m.status === "ok").length === 2, "restart: 2 ok, ghost failed");

// canary update: mac-3 (behind), mac-5 (behind), mac-2 (not behind)
const upd = (await j("POST", "/api/ops", { kind: "update", machineIds: [fleet[2].id, fleet[4].id, fleet[1].id] })).job;
assert(upd.canary === true, "update defaults to canary");
for (let i = 0; i < 120; i++) { await new Promise((r) => setTimeout(r, 500)); jb = (await j("GET", `/api/ops/${upd.id}`)).job; if (jb.status !== "running") break; }
console.log("  update job:", jb.status, jb.machines.map((m) => `${m.machineName}:${m.status} ${m.message}`).join(" | "));
assert(jb.status === "ok", "canary update all ok");
const ov2 = await j("GET", "/api/fleet/overview");
const m3 = ov2.machines.find((s) => s.machine.name === "mac-3");
assert(m3.version === "0.23.2" && !m3.update.update_available, "mac-3 version bumped after update");

// canary failure: ghost first -> others skipped
const upd2 = (await j("POST", "/api/ops", { kind: "update", machineIds: [fleet[6].id, fleet[0].id] })).job;
for (let i = 0; i < 60; i++) { await new Promise((r) => setTimeout(r, 500)); jb = (await j("GET", `/api/ops/${upd2.id}`)).job; if (jb.status !== "running") break; }
assert(jb.status === "failed" && jb.machines[1].status === "skipped", "canary failure skips the rest");

// prompt batch with SSE
const batch = await j("POST", "/api/runs", { machineIds: [fleet[0].id, fleet[5].id, fleet[1].id], prompt: "各マシンのホスト名を教えて。please approve" });
assert(batch.runs.length === 3, "3 runs created");
const es = await fetch(`${B}/api/runs/batches/${batch.batchId}/stream`, { headers: { authorization: `Bearer ${token}` } });
const reader = es.body.getReader(); const dec = new TextDecoder(); let buf = ""; const seen = {}; const done = new Set(); let approved = new Set();
const deadline = Date.now() + 30000;
while (Date.now() < deadline && done.size < 3) {
  const { value, done: d } = await reader.read(); if (d) break;
  buf += dec.decode(value, { stream: true });
  let idx; while ((idx = buf.indexOf("\n\n")) >= 0) {
    const frame = buf.slice(0, idx); buf = buf.slice(idx + 2);
    const ev = /^event: (.*)$/m.exec(frame)?.[1]; const data = /^data: (.*)$/m.exec(frame)?.[1];
    if (ev !== "run") continue;
    const p = JSON.parse(data);
    (seen[p.runId] ??= []).push(p.event.event);
    if (p.event.event === "approval.request" && !approved.has(p.runId)) { approved.add(p.runId); await j("POST", `/api/runs/${p.runId}/approval`, { choice: "once", request_id: p.event.request_id }); }
    if (p.event.event === "fleet.status" && ["completed", "failed", "cancelled"].includes(p.event.status)) done.add(p.runId);
  }
}
reader.cancel();
const finalRuns = (await j("GET", `/api/runs/batches/${batch.batchId}`)).runs;
console.log("  runs:", finalRuns.map((r) => `${r.machineName}:${r.status}`).join(" "));
const winRun = finalRuns.find((r) => r.machineName === "win-1");
assert(winRun.status === "failed" && /503|gateway/.test(winRun.error), "win-1 run failed (gateway stopped): " + winRun.error);
const macRun = finalRuns.find((r) => r.machineName === "mac-1");
assert(macRun.status === "completed" && macRun.output.includes("mac-1.local"), "mac-1 completed with output");
assert(seen[macRun.id].includes("approval.request") && seen[macRun.id].includes("message.delta"), "approval + deltas streamed");

// stop a slow run
const slow = await j("POST", "/api/runs", { machineIds: [fleet[1].id], prompt: "slow task" });
await new Promise((r) => setTimeout(r, 1200));
await j("POST", `/api/runs/${slow.runs[0].id}/stop`);
await new Promise((r) => setTimeout(r, 800));
assert((await j("GET", `/api/runs/batches/${slow.batchId}`)).runs[0].status === "cancelled", "stop -> cancelled");

// per-profile prompts: mac-2 has a "coder" profile without an API key yet
const waitBatch = async (id, ms = 15000) => { const t = Date.now() + ms; for (;;) { const rs = (await j("GET", `/api/runs/batches/${id}`)).runs; if (rs.every((r) => ["completed", "failed", "cancelled"].includes(r.status)) || Date.now() > t) return rs; await new Promise((r) => setTimeout(r, 300)); } };
const noKey = await waitBatch((await j("POST", "/api/runs", { targets: [{ machineId: fleet[1].id, profile: "coder" }], prompt: "hi" })).batchId);
assert(noKey[0].status === "failed" && /未設定/.test(noKey[0].error) && noKey[0].profile === "coder", "profile run without key fails clearly");
const pushed = await j("PUT", `/api/machines/${fleet[1].id}/profiles/coder/key`, { push: true });
assert(pushed.key.hasKey && pushed.key.profile === "coder", "key generated and pushed to the profile .env");
assert((await j("GET", `/api/machines/${fleet[1].id}/profiles/keys`)).keys.some((k) => k.profile === "coder" && k.hasKey), "profile key listed");
const ptest = await j("POST", `/api/machines/${fleet[1].id}/profiles/coder/test`);
assert(ptest.ok && /\/p\/coder$/.test(ptest.baseUrl), "profile API reachable via /p/coder: " + JSON.stringify(ptest));
await j("POST", `/api/machines/${fleet[1].id}/refresh`);
const snapProfiles = (await j("GET", `/api/machines/${fleet[1].id}`)).snapshot.profiles;
assert(snapProfiles.some((p) => p.name === "coder" && p.hasKey), "snapshot lists coder with key");
const prof = await waitBatch((await j("POST", "/api/runs", { targets: [{ machineId: fleet[1].id, profile: "coder" }, { machineId: fleet[0].id }], prompt: "プロファイル経由のテスト" })).batchId);
const coderRun = prof.find((r) => r.profile === "coder");
assert(coderRun.status === "completed" && coderRun.output.includes("mac-2 / coder"), "run executed on the coder profile: " + coderRun.output.slice(0, 40));
assert(prof.find((r) => r.profile === null).status === "completed", "default-profile run in the same batch completed");
const badKey = await j("PUT", `/api/machines/${fleet[1].id}/profiles/coder/key`, { apiKey: "wrong-key-0123456789" });
assert(badKey.key.hasKey, "manual key stored");
assert((await j("POST", `/api/machines/${fleet[1].id}/profiles/coder/test`)).ok === false, "wrong key is rejected by the profile endpoint");
await j("DELETE", `/api/machines/${fleet[1].id}/profiles/coder/key`);
assert(!(await j("GET", `/api/machines/${fleet[1].id}/profiles/keys`)).keys.some((k) => k.profile === "coder"), "profile key deleted");

// config / env distribution with preview
const pv = await j("POST", "/api/distribute/preview", { machineIds: [fleet[0].id, fleet[1].id], config: [{ path: "approvals.unattended_mode", raw: "allow" }, { path: "model.default", raw: "anthropic/claude-sonnet-5" }], env: [{ key: "OPENROUTER_API_KEY", value: "sk-or-test" }] });
assert(pv.rows.length === 2 && pv.rows[0].config[0].current === "deny" && pv.rows[0].config[0].changed && !pv.rows[0].config[1].changed, "preview shows current vs next");
assert(pv.rows[0].env[0].currentSet === false && pv.rows[0].env[0].next === "sk-***", "preview masks env values");
const ap = await j("POST", "/api/distribute/apply", { machineIds: [fleet[0].id, fleet[1].id], config: [{ path: "approvals.unattended_mode", raw: "allow" }], env: [{ key: "OPENROUTER_API_KEY", value: "sk-or-test" }] });
assert(ap.rows.every((r) => r.ok), "apply ok on both");
const cfgAfter = await j("GET", `/api/machines/${fleet[0].id}/config`);
assert(cfgAfter.approvals.unattended_mode === "allow" && cfgAfter.model.default === "anthropic/claude-sonnet-5", "config deep-merged on machine");
const envAfter = await j("GET", `/api/machines/${fleet[0].id}/env`);
assert(envAfter.vars.find((v) => v.name === "OPENROUTER_API_KEY").set === true, "env var set on machine");
const pv2 = await j("POST", "/api/distribute/preview", { machineIds: [fleet[0].id], config: [], env: [{ key: "OPENROUTER_API_KEY", value: null }] });
assert(pv2.rows[0].env[0].changed === true && pv2.rows[0].env[0].next === null, "preview delete");
const badDist = await fetch(B + "/api/distribute/apply", { method: "POST", headers: { "content-type": "application/json", authorization: `Bearer ${token}` }, body: JSON.stringify({ machineIds: [fleet[0].id], config: [], env: [] }) });
assert(badDist.status === 400, "empty distribute rejected");

// hermes proxy: models / providers / profiles / routing
const H = (m, path, body) => j(m, `/api/machines/${fleet[1].id}/hermes${path}`, body);
const opts = await H("GET", "/api/model/options?include_unconfigured=true");
assert(opts.providers.some((p) => p.slug === "anthropic" && p.authenticated) && opts.providers.some((p) => p.authenticated === false), "model options list providers with auth state");
const setMain = await H("POST", "/api/model/set", { scope: "main", provider: "anthropic", model: "anthropic/claude-opus-5" });
assert(setMain.ok === false && setMain.confirm_required, "expensive model asks for confirmation");
const setMain2 = await H("POST", "/api/model/set", { scope: "main", provider: "anthropic", model: "anthropic/claude-opus-5", confirm_expensive_model: true });
assert(setMain2.ok === true, "main model set after confirm");
assert((await H("GET", "/api/model/auxiliary")).main.model === "anthropic/claude-opus-5", "auxiliary payload reflects main model");
await H("POST", "/api/model/set", { scope: "auxiliary", task: "vision", provider: "openrouter", model: "google/gemini-3-pro" });
assert((await H("GET", "/api/model/auxiliary")).tasks.find((t) => t.task === "vision").model === "google/gemini-3-pro", "auxiliary task set");
const val = await H("POST", "/api/providers/validate", { key: "OPENAI_API_KEY", value: "bad-key" });
assert(val.ok === false && val.reachable, "provider key validation rejects bad key");
await H("PUT", "/api/env", { key: "OPENAI_API_KEY", value: "sk-good" });
assert((await H("GET", "/api/model/options")).providers.find((p) => p.slug === "openai").authenticated, "provider authenticated after key set");
const ce = await H("POST", "/api/providers/custom-endpoints", { name: "ollama mac", base_url: "http://100.1.2.3:11434/v1", model: "local-model", api_key: "" });
assert(ce.ok && ce.id === "ollama-mac", "custom endpoint created");
await H("DELETE", `/api/providers/custom-endpoints/${ce.id}`);
assert(((await H("GET", "/api/providers/custom-endpoints")).endpoints).length === 0, "custom endpoint deleted");
const profs = await H("GET", "/api/profiles");
assert(profs.profiles.some((p) => p.name === "coder"), "mac-2 has coder profile");
const created = await H("POST", "/api/profiles", { name: "researcher", clone_from: "default", description: "reads docs", provider: "anthropic", model: "anthropic/claude-sonnet-5" });
assert(created.ok && created.model_set, "profile created with model");
await H("PUT", "/api/profiles/researcher/soul", { content: "You research things." });
assert((await H("GET", "/api/profiles/researcher/soul")).content === "You research things.", "profile soul saved");
await H("POST", "/api/profiles/active", { name: "researcher" });
assert((await H("GET", "/api/profiles/active")).active === "researcher", "active profile switched");
await H("DELETE", "/api/profiles/researcher");
assert(!(await H("GET", "/api/profiles")).profiles.some((p) => p.name === "researcher"), "profile deleted");
await H("PUT", "/api/config", { config: { fallback_providers: [{ provider: "openrouter", model: "anthropic/claude-sonnet-5" }], provider_routing: { sort: "price", only: [], ignore: [], order: ["anthropic"] }, gateway: { api_server: { model_routes: { fast: { model: "anthropic/claude-haiku-4-5", provider: "anthropic" } } } } } });
const cfgR = await H("GET", "/api/config");
assert(cfgR.fallback_providers[0].provider === "openrouter" && cfgR.provider_routing.sort === "price" && cfgR.gateway.api_server.model_routes.fast.model === "anthropic/claude-haiku-4-5" && cfgR.gateway.api_server.port, "routing config merged without clobbering siblings");
const forbidden = await fetch(B + `/api/machines/${fleet[1].id}/hermes/api/gateway/stop`, { method: "POST", headers: { "content-type": "application/json", authorization: `Bearer ${token}` }, body: "{}" });
assert(forbidden.status === 403, "proxy rejects non-allowlisted path");

const audit = await j("GET", "/api/audit?limit=200");
console.log("  audit entries:", audit.entries.length, [...new Set(audit.entries.map((e) => e.action))].join(","));
assert(audit.entries.some((e) => e.action === "ops.update.done") && audit.entries.some((e) => e.action === "distribute.apply") && audit.entries.some((e) => e.action === "hermes.post" && e.detail.includes("***")), "audit has ops + distribute + proxy entries (secrets redacted)");
await j("PUT", `/api/machines/${fleet[6].id}`, { name: "ghost-renamed" });
await j("DELETE", `/api/machines/${fleet[6].id}`);
assert((await j("GET", "/api/machines")).machines.length === 6, "delete works");
console.log("E2E PASSED");

import { describe, expect, it } from "vitest";
import { randomBytes } from "node:crypto";
import { decrypt, encrypt, hashPassword, verifyPassword } from "../src/crypto.js";
import { openDb } from "../src/db.js";
import { AuthService } from "../src/auth.js";
import { MachineRepo, ValidationError, normalizeUrl } from "../src/machines.js";
import { buildApp } from "../src/app.js";
import { loadConfig } from "../src/config.js";
import { FleetPoller } from "../src/fleet.js";
import { AuditLog } from "../src/audit.js";
import { RunManager } from "../src/runs.js";
import { OpsManager } from "../src/ops.js";

describe("crypto", () => {
  it("round-trips secrets and rejects tampering", () => {
    const key = randomBytes(32);
    const blob = encrypt(key, "s3cret");
    expect(blob.startsWith("v1.")).toBe(true);
    expect(decrypt(key, blob)).toBe("s3cret");
    expect(encrypt(key, "")).toBe("");
    expect(() => decrypt(randomBytes(32), blob)).toThrow();
  });
  it("hashes and verifies passwords", () => {
    const h = hashPassword("pw");
    expect(verifyPassword("pw", h)).toBe(true);
    expect(verifyPassword("px", h)).toBe(false);
    expect(verifyPassword("pw", "garbage")).toBe(false);
  });
});

describe("AuthService", () => {
  it("setup, login, verify, logout, rate-limit", () => {
    const db = openDb(":memory:");
    const a = new AuthService(db);
    expect(a.isConfigured()).toBe(false);
    expect(() => a.setup("short")).toThrow();
    a.setup("longenough");
    expect(() => a.setup("longenough")).toThrow();
    const s = a.login("longenough", "ip");
    expect(a.verify(s.token)).toBe(true);
    a.logout(s.token);
    expect(a.verify(s.token)).toBe(false);
    for (let i = 0; i < 5; i++) expect(() => a.login("wrong", "ip2")).toThrow(/invalid/);
    expect(() => a.login("longenough", "ip2")).toThrow(/too many/);
  });
});

describe("MachineRepo", () => {
  it("validates, encrypts secrets, and never exposes them", () => {
    const db = openDb(":memory:");
    const repo = new MachineRepo(db, randomBytes(32), 1000);
    expect(() => repo.create({ name: "" })).toThrow(ValidationError);
    expect(() => repo.create({ name: "a" })).toThrow(/dashboardUrl/);
    expect(() => repo.create({ name: "a", dashboardUrl: "http://x", dashboardAuthKind: "basic" })).toThrow(/dashboardUsername/);
    const m = repo.create({ name: "a", dashboardUrl: "x.local:9119/", dashboardAuthKind: "basic", dashboardUsername: "u", dashboardPassword: "p", apiUrl: "http://x:8642", apiKey: "super-secret-api-key" });
    expect(m.dashboardUrl).toBe("http://x.local:9119");
    expect(m.hasApiKey).toBe(true);
    expect(JSON.stringify(m)).not.toMatch(/super-secret/);
    const raw = db.prepare("SELECT api_key_enc, dashboard_password_enc FROM machines").get() as { api_key_enc: string; dashboard_password_enc: string };
    expect(raw.api_key_enc).not.toContain("super-secret");
    expect(raw.api_key_enc.startsWith("v1.")).toBe(true);
    const updated = repo.update(m.id, { name: "b", apiKey: "" })!;
    expect(updated.name).toBe("b");
    expect(updated.hasApiKey).toBe(true); // empty string keeps the old key
    expect(repo.clients(m.id)?.api).toBeTruthy();
    expect(repo.delete(m.id)).toBe(true);
  });
  it("normalizes urls", () => {
    expect(normalizeUrl(" 100.1.2.3:9119/ ")).toBe("http://100.1.2.3:9119");
    expect(normalizeUrl("https://a/b/")).toBe("https://a/b");
    expect(normalizeUrl("")).toBe("");
  });
});

describe("app auth gate", () => {
  it("requires setup then bearer token", async () => {
    const db = openDb(":memory:");
    const key = randomBytes(32);
    const auth = new AuthService(db);
    const repo = new MachineRepo(db, key, 1000);
    const audit = new AuditLog(db);
    const poller = new FleetPoller(repo, 60000);
    const app = buildApp({ config: loadConfig({ FLEET_DATA_DIR: "/tmp/x" } as NodeJS.ProcessEnv), auth, repo, poller, audit, runs: new RunManager(db, repo, audit), ops: new OpsManager(repo, audit, poller) });
    expect((await app.request("/api/machines")).status).toBe(401);
    const setup = await app.request("/api/auth/setup", { method: "POST", body: JSON.stringify({ password: "longenough" }), headers: { "content-type": "application/json" } });
    expect(setup.status).toBe(200);
    const { token } = (await setup.json()) as { token: string };
    const list = await app.request("/api/machines", { headers: { authorization: `Bearer ${token}` } });
    expect(list.status).toBe(200);
    expect(await list.json()).toEqual({ machines: [] });
    const bad = await app.request("/api/machines", { method: "POST", headers: { authorization: `Bearer ${token}`, "content-type": "application/json" }, body: JSON.stringify({ name: "" }) });
    expect(bad.status).toBe(400);
  });
});

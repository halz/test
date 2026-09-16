import type { DatabaseSync } from "node:sqlite";
import type { Context, Next } from "hono";
import { createHmac, timingSafeEqual } from "node:crypto";
import { hashPassword, hashToken, newToken, verifyPassword } from "./crypto.js";

const SESSION_TTL_MS = 30 * 24 * 3600_000;

export class AuthService {
  private failures = new Map<string, { count: number; until: number }>();

  /**
   * @param demoPassword Demo mode (stateless hosting): a fixed password whose session token is
   *   derived deterministically, so it stays valid across serverless instances.
   */
  constructor(
    private readonly db: DatabaseSync,
    private readonly demoPassword?: string,
  ) {}

  get isDemo(): boolean {
    return Boolean(this.demoPassword);
  }

  isConfigured(): boolean {
    if (this.demoPassword) return true;
    return this.getSetting("admin_password_hash") !== null;
  }

  private demoToken(): string {
    return createHmac("sha256", `fleet-demo:${this.demoPassword}`).update("session").digest("base64url");
  }

  setup(password: string): void {
    if (this.isConfigured()) throw new Error("already configured");
    if (password.length < 8) throw new Error("password must be at least 8 characters");
    this.setSetting("admin_password_hash", hashPassword(password));
  }

  changePassword(current: string, next: string): void {
    const hash = this.getSetting("admin_password_hash");
    if (!hash || !verifyPassword(current, hash)) throw new Error("current password is wrong");
    if (next.length < 8) throw new Error("password must be at least 8 characters");
    this.setSetting("admin_password_hash", hashPassword(next));
    this.db.prepare("DELETE FROM sessions").run();
  }

  login(password: string, ip: string, label = ""): { token: string; expiresAt: number } {
    if (this.demoPassword) {
      if (password !== this.demoPassword) throw new AuthError("invalid password", 401);
      return { token: this.demoToken(), expiresAt: Date.now() + SESSION_TTL_MS };
    }
    const f = this.failures.get(ip);
    if (f && f.until > Date.now()) throw new AuthError("too many attempts; try again later", 429);
    const hash = this.getSetting("admin_password_hash");
    if (!hash || !verifyPassword(password, hash)) {
      const count = (f?.count ?? 0) + 1;
      this.failures.set(ip, { count, until: count >= 5 ? Date.now() + 60_000 * Math.min(count - 4, 15) : 0 });
      throw new AuthError("invalid password", 401);
    }
    this.failures.delete(ip);
    const token = newToken();
    const now = Date.now();
    this.db.prepare("INSERT INTO sessions (token_hash, created_at, expires_at, label) VALUES (?, ?, ?, ?)").run(hashToken(token), now, now + SESSION_TTL_MS, label);
    return { token, expiresAt: now + SESSION_TTL_MS };
  }

  logout(token: string): void {
    this.db.prepare("DELETE FROM sessions WHERE token_hash = ?").run(hashToken(token));
  }

  verify(token: string): boolean {
    if (this.demoPassword) {
      const a = Buffer.from(token);
      const b = Buffer.from(this.demoToken());
      return a.length === b.length && timingSafeEqual(a, b);
    }
    const row = this.db.prepare("SELECT expires_at FROM sessions WHERE token_hash = ?").get(hashToken(token)) as { expires_at: number } | undefined;
    if (!row) return false;
    if (row.expires_at < Date.now()) {
      this.logout(token);
      return false;
    }
    return true;
  }

  /** Hono middleware: requires `Authorization: Bearer <token>` (or ?token= for EventSource). */
  middleware() {
    return async (c: Context, next: Next) => {
      const token = extractToken(c);
      if (!token || !this.verify(token)) return c.json({ error: "unauthorized" }, 401);
      c.set("token", token);
      await next();
    };
  }

  private getSetting(key: string): string | null {
    const row = this.db.prepare("SELECT value FROM settings WHERE key = ?").get(key) as { value: string } | undefined;
    return row?.value ?? null;
  }

  private setSetting(key: string, value: string): void {
    this.db.prepare("INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").run(key, value);
  }
}

export function extractToken(c: Context): string | null {
  const h = c.req.header("authorization");
  if (h?.toLowerCase().startsWith("bearer ")) return h.slice(7).trim();
  const q = c.req.query("token");
  return q ? q : null;
}

export class AuthError extends Error {
  constructor(message: string, public readonly status: 401 | 429) {
    super(message);
  }
}

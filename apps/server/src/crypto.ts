import { createCipheriv, createDecipheriv, randomBytes, scryptSync, timingSafeEqual, createHash } from "node:crypto";
import { existsSync, readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";

/** Load or create the 32-byte master key used to encrypt machine secrets at rest. */
export function loadMasterKey(env: NodeJS.ProcessEnv, keyFile: string): Buffer {
  if (env.FLEET_MASTER_KEY) {
    const k = Buffer.from(env.FLEET_MASTER_KEY, "base64");
    if (k.length !== 32) throw new Error("FLEET_MASTER_KEY must be 32 bytes base64");
    return k;
  }
  if (existsSync(keyFile)) {
    const k = Buffer.from(readFileSync(keyFile, "utf8").trim(), "base64");
    if (k.length === 32) return k;
    throw new Error(`${keyFile} does not contain a 32-byte base64 key`);
  }
  const k = randomBytes(32);
  mkdirSync(dirname(keyFile), { recursive: true });
  writeFileSync(keyFile, k.toString("base64") + "\n", { mode: 0o600 });
  return k;
}

export function encrypt(key: Buffer, plaintext: string): string {
  if (!plaintext) return "";
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  const enc = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return `v1.${iv.toString("base64url")}.${enc.toString("base64url")}.${tag.toString("base64url")}`;
}

export function decrypt(key: Buffer, blob: string): string {
  if (!blob) return "";
  const [v, ivB, encB, tagB] = blob.split(".");
  if (v !== "v1" || !ivB || !encB || !tagB) throw new Error("bad ciphertext");
  const decipher = createDecipheriv("aes-256-gcm", key, Buffer.from(ivB, "base64url"));
  decipher.setAuthTag(Buffer.from(tagB, "base64url"));
  return Buffer.concat([decipher.update(Buffer.from(encB, "base64url")), decipher.final()]).toString("utf8");
}

export function hashPassword(password: string): string {
  const salt = randomBytes(16);
  const dk = scryptSync(password, salt, 32, { N: 2 ** 14, r: 8, p: 1 });
  return `scrypt$${salt.toString("base64url")}$${dk.toString("base64url")}`;
}

export function verifyPassword(password: string, encoded: string): boolean {
  const [scheme, saltB, dkB] = encoded.split("$");
  if (scheme !== "scrypt" || !saltB || !dkB) return false;
  const expected = Buffer.from(dkB, "base64url");
  const actual = scryptSync(password, Buffer.from(saltB, "base64url"), expected.length, { N: 2 ** 14, r: 8, p: 1 });
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

export function newToken(): string {
  return randomBytes(32).toString("base64url");
}

export function hashToken(token: string): string {
  return createHash("sha256").update(token).digest("base64url");
}

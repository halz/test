// Thin API layer: base URL (same-origin by default; overridable for the Android app),
// bearer token in localStorage, JSON helpers, and EventSource with ?token=.

const KEY_TOKEN = "fleet.token";
const KEY_URL = "fleet.serverUrl";

import { Capacitor } from "@capacitor/core";

export function isNativeApp(): boolean {
  return Capacitor.isNativePlatform();
}

export function getServerUrl(): string {
  try {
    return (localStorage.getItem(KEY_URL) ?? "").replace(/\/+$/, "");
  } catch {
    return "";
  }
}

/** Normalize what a person types: trims, adds http:// when the scheme is missing, strips trailing slashes. */
export function normalizeServerUrl(url: string): string {
  let u = url.trim().replace(/\/+$/, "");
  if (u && !/^https?:\/\//i.test(u)) u = `http://${u}`;
  return u;
}

export function setServerUrl(url: string): void {
  try {
    localStorage.setItem(KEY_URL, normalizeServerUrl(url));
  } catch {
    // ignore
  }
}

export function getToken(): string | null {
  try {
    return localStorage.getItem(KEY_TOKEN);
  } catch {
    return null;
  }
}

export function setToken(token: string | null): void {
  try {
    if (token) localStorage.setItem(KEY_TOKEN, token);
    else localStorage.removeItem(KEY_TOKEN);
  } catch {
    // ignore
  }
  for (const l of tokenListeners) l(token);
}

const tokenListeners = new Set<(t: string | null) => void>();
export function onTokenChange(fn: (t: string | null) => void): () => void {
  tokenListeners.add(fn);
  return () => tokenListeners.delete(fn);
}

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
  }
}

export async function api<T = unknown>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { accept: "application/json" };
  const token = getToken();
  if (token) headers.authorization = `Bearer ${token}`;
  if (body !== undefined) headers["content-type"] = "application/json";
  let res: Response;
  try {
    res = await fetch(getServerUrl() + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch (e) {
    throw new ApiError(0, `サーバーに接続できません (${getServerUrl() || "same origin"})`);
  }
  const text = await res.text();
  let data: unknown = undefined;
  let isJson = false;
  try {
    data = text ? JSON.parse(text) : undefined;
    isJson = true;
  } catch {
    data = text;
  }
  if (res.status === 401 && token && !path.startsWith("/api/auth/")) setToken(null);
  if (!res.ok) {
    const msg = isJson ? ((data as { error?: string } | undefined)?.error ?? `${res.status} ${res.statusText}`) : `${res.status} ${res.statusText}`;
    throw new ApiError(res.status, msg);
  }
  // A 200 that is not JSON means we hit something other than the console API (e.g. the app's own
  // index.html when the server URL is wrong). Never treat that as data.
  if (!isJson && text) {
    throw new ApiError(0, `サーバー URL が正しくありません（${getServerUrl() || "same origin"} は Fleet Console の API を返しませんでした）`);
  }
  return data as T;
}

export function eventSource(path: string): EventSource {
  const token = getToken() ?? "";
  const sep = path.includes("?") ? "&" : "?";
  return new EventSource(`${getServerUrl()}${path}${sep}token=${encodeURIComponent(token)}`);
}

export function fmtBytes(n?: number): string {
  if (n === undefined || n === null) return "-";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let i = 0;
  let v = n;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(i >= 3 ? 1 : 0)} ${units[i]}`;
}

export function fmtAgo(ts?: number | string | null): string {
  if (!ts) return "-";
  const t = typeof ts === "number" ? (ts < 1e12 ? ts * 1000 : ts) : Date.parse(ts);
  if (Number.isNaN(t)) return String(ts);
  const s = Math.max(0, Math.round((Date.now() - t) / 1000));
  if (s < 60) return `${s}秒前`;
  if (s < 3600) return `${Math.floor(s / 60)}分前`;
  if (s < 86400) return `${Math.floor(s / 3600)}時間前`;
  return `${Math.floor(s / 86400)}日前`;
}

export function fmtDuration(sec?: number): string {
  if (!sec && sec !== 0) return "-";
  const d = Math.floor(sec / 86400);
  const h = Math.floor((sec % 86400) / 3600);
  const m = Math.floor((sec % 3600) / 60);
  return d > 0 ? `${d}日 ${h}時間` : h > 0 ? `${h}時間 ${m}分` : `${m}分`;
}

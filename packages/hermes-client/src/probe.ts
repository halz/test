import { ApiServerClient } from "./apiserver.js";
import { DashboardClient } from "./dashboard.js";
import { HermesAuthError, HermesHttpError } from "./errors.js";

export interface ProbeLeg {
  ok: boolean;
  reachable: boolean;
  authenticated: boolean;
  version?: string;
  latencyMs?: number;
  error?: string;
}

export interface ProbeResult {
  dashboard: ProbeLeg;
  apiServer: ProbeLeg;
}

function describe(err: unknown): string {
  if (err instanceof HermesAuthError) return err.message;
  if (err instanceof HermesHttpError) return `HTTP ${err.status}`;
  if (err instanceof Error) {
    const cause = (err as Error & { cause?: { code?: string; message?: string } }).cause;
    if (cause?.code) return `${err.name}: ${cause.code}`;
    return `${err.name}: ${err.message}`;
  }
  return String(err);
}

/** Probe both legs of a machine: public liveness first, then an authenticated call. */
export async function probeMachine(dashboard: DashboardClient | null, api: ApiServerClient | null): Promise<ProbeResult> {
  const [d, a] = await Promise.all([probeDashboard(dashboard), probeApi(api)]);
  return { dashboard: d, apiServer: a };
}

async function probeDashboard(client: DashboardClient | null): Promise<ProbeLeg> {
  if (!client) return { ok: false, reachable: false, authenticated: false, error: "not configured" };
  const t0 = Date.now();
  let version: string | undefined;
  try {
    const status = await client.status();
    version = status.version;
  } catch (err) {
    return { ok: false, reachable: false, authenticated: false, error: describe(err) };
  }
  try {
    await client.systemStats();
    return { ok: true, reachable: true, authenticated: true, version, latencyMs: Date.now() - t0 };
  } catch (err) {
    return { ok: false, reachable: true, authenticated: false, version, latencyMs: Date.now() - t0, error: describe(err) };
  }
}

async function probeApi(client: ApiServerClient | null): Promise<ProbeLeg> {
  if (!client) return { ok: false, reachable: false, authenticated: false, error: "not configured" };
  const t0 = Date.now();
  try {
    await client.health();
  } catch (err) {
    return { ok: false, reachable: false, authenticated: false, error: describe(err) };
  }
  try {
    const detailed = await client.healthDetailed();
    return { ok: true, reachable: true, authenticated: true, version: detailed.version, latencyMs: Date.now() - t0 };
  } catch (err) {
    return { ok: false, reachable: true, authenticated: false, latencyMs: Date.now() - t0, error: describe(err) };
  }
}

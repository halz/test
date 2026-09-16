import { resolve } from "node:path";

export interface ServerConfig {
  host: string;
  port: number;
  dataDir: string;
  dbPath: string;
  keyFile: string;
  pollIntervalMs: number;
  staticDir: string | null;
  corsOrigins: string[];
  requestTimeoutMs: number;
  /** Set for the hosted demo: fixed password, stateless sessions, mocks in-process. */
  demoPassword?: string;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): ServerConfig {
  const dataDir = resolve(env.FLEET_DATA_DIR ?? "./data");
  return {
    host: env.FLEET_HOST ?? "127.0.0.1",
    port: Number(env.FLEET_PORT ?? 8080),
    dataDir,
    dbPath: env.FLEET_DB_PATH ?? resolve(dataDir, "fleet.sqlite"),
    keyFile: env.FLEET_KEY_FILE ?? resolve(dataDir, "master.key"),
    pollIntervalMs: Number(env.FLEET_POLL_INTERVAL_MS ?? 30_000),
    staticDir: env.FLEET_STATIC_DIR ? resolve(env.FLEET_STATIC_DIR) : null,
    corsOrigins: (env.FLEET_CORS_ORIGINS ?? "capacitor://localhost,http://localhost,https://localhost")
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean),
    requestTimeoutMs: Number(env.FLEET_REQUEST_TIMEOUT_MS ?? 15_000),
    demoPassword: env.FLEET_DEMO_PASSWORD || undefined,
  };
}

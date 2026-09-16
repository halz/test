import { HermesHttpError, joinUrl, readBodyText } from "./errors.js";
import { parseSse } from "./sse.js";
import type {
  ApiHealth,
  ApiHealthDetailed,
  Capabilities,
  RunAccepted,
  RunCreateBody,
  RunEvent,
  RunStatus,
} from "./types.js";

export interface ApiServerClientOptions {
  baseUrl: string;
  apiKey: string;
  fetch?: typeof fetch;
  timeoutMs?: number;
}

/**
 * Client for the Hermes API server platform (default port 8642, Bearer API_SERVER_KEY).
 */
export class ApiServerClient {
  readonly baseUrl: string;
  private readonly apiKey: string;
  private readonly fetchImpl: typeof fetch;
  private readonly timeoutMs: number;

  constructor(opts: ApiServerClientOptions) {
    this.baseUrl = opts.baseUrl.replace(/\/+$/, "");
    this.apiKey = opts.apiKey;
    this.fetchImpl = opts.fetch ?? globalThis.fetch;
    this.timeoutMs = opts.timeoutMs ?? 15_000;
  }

  health(): Promise<ApiHealth> {
    return this.request("GET", "/health", undefined, { auth: false });
  }

  healthDetailed(): Promise<ApiHealthDetailed> {
    return this.request("GET", "/health/detailed");
  }

  capabilities(): Promise<Capabilities> {
    return this.request("GET", "/v1/capabilities");
  }

  models(): Promise<{ data: { id: string }[] }> {
    return this.request("GET", "/v1/models");
  }

  createRun(body: RunCreateBody, opts: { idempotencyKey?: string; sessionKey?: string; sessionId?: string } = {}): Promise<RunAccepted> {
    const headers: Record<string, string> = {};
    if (opts.idempotencyKey) headers["Idempotency-Key"] = opts.idempotencyKey;
    if (opts.sessionKey) headers["X-Hermes-Session-Key"] = opts.sessionKey;
    if (opts.sessionId) headers["X-Hermes-Session-Id"] = opts.sessionId;
    return this.request("POST", "/v1/runs", body, { headers });
  }

  getRun(runId: string): Promise<RunStatus> {
    return this.request("GET", `/v1/runs/${encodeURIComponent(runId)}`);
  }

  stopRun(runId: string): Promise<unknown> {
    return this.request("POST", `/v1/runs/${encodeURIComponent(runId)}/stop`, {});
  }

  steerRun(runId: string, text: string): Promise<unknown> {
    return this.request("POST", `/v1/runs/${encodeURIComponent(runId)}/steer`, { input: text });
  }

  approveRun(runId: string, choice: "once" | "session" | "always" | "deny", requestId?: string): Promise<unknown> {
    return this.request("POST", `/v1/runs/${encodeURIComponent(runId)}/approval`, {
      choice,
      ...(requestId ? { request_id: requestId } : {}),
    });
  }

  /** Stream run lifecycle events (SSE: `data: {json}`). Ends when the server closes the stream. */
  async *runEvents(runId: string, signal?: AbortSignal): AsyncGenerator<RunEvent> {
    const url = joinUrl(this.baseUrl, `/v1/runs/${encodeURIComponent(runId)}/events`);
    const res = await this.fetchImpl(url, {
      method: "GET",
      headers: { authorization: `Bearer ${this.apiKey}`, accept: "text/event-stream" },
      signal,
    });
    if (!res.ok || !res.body) throw new HermesHttpError(res.status, url, await readBodyText(res));
    for await (const frame of parseSse(res.body, signal)) {
      if (!frame.data) continue;
      try {
        yield JSON.parse(frame.data) as RunEvent;
      } catch {
        // ignore malformed frame
      }
    }
  }

  sessions(params: { limit?: number; offset?: number } = {}): Promise<unknown> {
    const q = new URLSearchParams();
    if (params.limit) q.set("limit", String(params.limit));
    if (params.offset) q.set("offset", String(params.offset));
    return this.request("GET", `/api/sessions?${q}`);
  }

  jobs(): Promise<unknown> {
    return this.request("GET", "/api/jobs");
  }

  async request<T>(
    method: string,
    path: string,
    body?: unknown,
    opts: { auth?: boolean; headers?: Record<string, string> } = {},
  ): Promise<T> {
    const url = joinUrl(this.baseUrl, path);
    const headers: Record<string, string> = { accept: "application/json", ...(opts.headers ?? {}) };
    if (opts.auth !== false) headers.authorization = `Bearer ${this.apiKey}`;
    if (body !== undefined) headers["content-type"] = "application/json";
    const res = await this.fetchImpl(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(this.timeoutMs),
    });
    if (!res.ok) throw new HermesHttpError(res.status, url, await readBodyText(res));
    const text = await res.text();
    if (!text) return undefined as T;
    return JSON.parse(text) as T;
  }
}

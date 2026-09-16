import { describe, expect, it } from "vitest";
import { ApiServerClient, DashboardClient, HermesAuthError, parseSse } from "../src/index.js";

function stream(chunks: string[]): ReadableStream<Uint8Array> {
  const enc = new TextEncoder();
  return new ReadableStream({
    start(c) {
      for (const ch of chunks) c.enqueue(enc.encode(ch));
      c.close();
    },
  });
}

describe("parseSse", () => {
  it("parses frames split across chunks and skips comments", async () => {
    const frames = [];
    for await (const f of parseSse(stream([": keepalive\n\ndata: {\"a\":", "1}\n\nevent: x\ndata: 2\n\n"]))) frames.push(f);
    expect(frames).toEqual([{ data: '{"a":1}', event: undefined, id: undefined }, { data: "2", event: "x", id: undefined }]);
  });
  it("handles CRLF and multi-line data", async () => {
    const frames = [];
    for await (const f of parseSse(stream(["data: a\r\ndata: b\r\n\r\n"]))) frames.push(f);
    expect(frames[0].data).toBe("a\nb");
  });
});

function fakeFetch(handler: (url: string, init: RequestInit) => Response | Promise<Response>): typeof fetch {
  return ((url: string | URL | Request, init?: RequestInit) => Promise.resolve(handler(String(url), init ?? {}))) as unknown as typeof fetch;
}

describe("DashboardClient", () => {
  it("logs in with the basic provider, replays cookies, and re-logins once on 401", async () => {
    let logins = 0;
    let cookieSeen: string[] = [];
    let expired = true;
    const fetchImpl = fakeFetch((url, init) => {
      const headers = init.headers as Record<string, string>;
      if (url.endsWith("/auth/password-login")) {
        logins++;
        const body = JSON.parse(String(init.body));
        expect(body).toMatchObject({ provider: "basic", username: "admin", password: "pw" });
        const h = new Headers({ "content-type": "application/json" });
        h.append("set-cookie", `hermes_session_at=tok${logins}; Path=/; HttpOnly; Max-Age=43200`);
        h.append("set-cookie", "hermes_session_provider=basic; Path=/");
        return new Response('{"ok":true,"next":"/"}', { status: 200, headers: h });
      }
      if (url.endsWith("/api/system/stats")) {
        cookieSeen.push(headers.cookie ?? "");
        if (expired && (headers.cookie ?? "").includes("tok1")) {
          expired = false;
          return new Response('{"error":"unauthenticated"}', { status: 401 });
        }
        return new Response('{"hostname":"h"}', { status: 200, headers: { "content-type": "application/json" } });
      }
      return new Response("nf", { status: 404 });
    });
    const c = new DashboardClient({ baseUrl: "http://x:9119/", auth: { kind: "basic", username: "admin", password: "pw" }, fetch: fetchImpl });
    const stats = await c.systemStats();
    expect(stats.hostname).toBe("h");
    expect(logins).toBe(2);
    expect(cookieSeen[0]).toContain("hermes_session_at=tok1");
    expect(cookieSeen[1]).toContain("hermes_session_at=tok2");
    expect(Object.keys(c.cookieJar)).toContain("hermes_session_at");
  });

  it("raises HermesAuthError on bad credentials", async () => {
    const c = new DashboardClient({ baseUrl: "http://x", auth: { kind: "basic", username: "a", password: "b" }, fetch: fakeFetch(() => new Response("{}", { status: 401 })) });
    await expect(c.systemStats()).rejects.toBeInstanceOf(HermesAuthError);
  });

  it("does not attempt login for public endpoints", async () => {
    let logins = 0;
    const c = new DashboardClient({
      baseUrl: "http://x",
      auth: { kind: "basic", username: "a", password: "b" },
      fetch: fakeFetch((url) => {
        if (url.includes("password-login")) logins++;
        return new Response('{"version":"1"}', { status: 200 });
      }),
    });
    expect((await c.status()).version).toBe("1");
    expect(logins).toBe(0);
  });
});

describe("ApiServerClient", () => {
  it("sends bearer + idempotency headers and streams run events", async () => {
    const c = new ApiServerClient({
      baseUrl: "http://x:8642",
      apiKey: "k",
      fetch: fakeFetch((url, init) => {
        const h = init.headers as Record<string, string>;
        expect(h.authorization).toBe("Bearer k");
        if (url.endsWith("/v1/runs")) {
          expect(h["Idempotency-Key"]).toBe("i1");
          return new Response('{"run_id":"r1","status":"started"}', { status: 202 });
        }
        if (url.endsWith("/v1/runs/r1/events")) {
          return new Response(stream(['data: {"event":"message.delta","run_id":"r1","timestamp":1,"delta":"hi"}\n\n', ': keepalive\n\n', 'data: {"event":"run.completed","run_id":"r1","timestamp":2,"output":"hi"}\n\n']), { status: 200, headers: { "content-type": "text/event-stream" } });
        }
        return new Response("nf", { status: 404 });
      }),
    });
    const acc = await c.createRun({ input: "x" }, { idempotencyKey: "i1" });
    expect(acc.run_id).toBe("r1");
    const evs = [];
    for await (const e of c.runEvents("r1")) evs.push(e.event);
    expect(evs).toEqual(["message.delta", "run.completed"]);
  });
});

import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";
import { Bot, Check, MessageSquare, Plus, Send, Sparkles, Square } from "lucide-react";
import { api, eventSource, fmtAgo } from "../api";
import { useFleet, useMachines, useMediaQuery } from "../hooks";
import { Badge, ErrorBox, MachinePicker } from "../components/ui";
import type { FleetRun, RunStreamEvent } from "../types";

interface LiveRun extends FleetRun {
  live: string;
  events: string[];
  approval?: { request_id?: string; command?: string; choices?: string[] };
}
interface Batch { id: string; label: string; createdAt: number; runs: FleetRun[] }

export function Prompt() {
  const machines = useMachines();
  const fleet = useFleet();
  const [params, setParams] = useSearchParams();
  const [ids, setIds] = useState<string[]>([]);
  /** machineId -> profile name ("default" or a named profile). */
  const [profileOf, setProfileOf] = useState<Record<string, string>>({});
  const [prompt, setPrompt] = useState("");
  const [model, setModel] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const batchId = params.get("batch");
  const setBatchId = (id: string | null) => setParams(id ? { batch: id } : {}, { replace: true });
  const history = useQuery({ queryKey: ["batches"], queryFn: () => api<{ batches: Batch[] }>("GET", "/api/runs/batches?limit=30"), refetchInterval: 10000 });
  const current = history.data?.batches.find((b) => b.id === batchId);
  const targets = (machines.data ?? []).filter((m) => ids.includes(m.id));
  const snapOf = (id: string) => fleet.snapshots.find((x) => x.machine.id === id);
  const profilesOf = (id: string) => snapOf(id)?.profiles ?? [{ name: "default", hasKey: true }];
  const profileFor = (id: string) => profileOf[id] ?? "default";
  /** Named profiles present on at least one selected machine (for the "apply to all" shortcut). */
  const namedProfiles = [...new Set(targets.flatMap((m) => profilesOf(m.id).map((p) => p.name)))].filter((n) => n !== "default").sort();
  const applyProfileToAll = (name: string) => setProfileOf((cur) => { const next = { ...cur }; for (const m of targets) next[m.id] = name === "default" || profilesOf(m.id).some((p) => p.name === name) ? name : "default"; return next; });
  const canSend = !busy && ids.length > 0 && prompt.trim().length > 0;

  const send = async () => {
    if (!canSend) return;
    setErr(null);
    setBusy(true);
    try {
      // machineIds is also sent so a console server from before profile targeting still accepts the request (it ignores targets and uses the default profile).
      const r = await api<{ batchId: string }>("POST", "/api/runs", { targets: ids.map((machineId) => ({ machineId, profile: profileFor(machineId) })), machineIds: ids, prompt, ...(model ? { model } : {}) });
      setBatchId(r.batchId);
      setPrompt("");
      history.refetch();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };
  const narrow = useMediaQuery("(max-width: 1100px)");
  const picker = <MachinePicker machines={machines.data ?? []} value={ids} onChange={setIds} snapshots={fleet.snapshots} />;
  const targetList = (
    <>
      {namedProfiles.length ? (
        <div className="row small" style={{ margin: "6px 0" }}>
          <span className="muted">プロファイル一括:</span>
          <select style={{ width: "auto" }} value="" onChange={(e) => { if (e.target.value) applyProfileToAll(e.target.value); }}>
            <option value="">選択…</option>
            <option value="default">default（既定）</option>
            {namedProfiles.map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </div>
      ) : null}
      {targets.length === 0 ? <p className="muted small" style={{ margin: "6px 0" }}>まだ選択されていません</p> : null}
      {targets.map((m) => {
        const s = snapOf(m.id);
        const profiles = profilesOf(m.id);
        const cur = profileFor(m.id);
        const curOk = profiles.find((p) => p.name === cur)?.hasKey ?? cur === "default";
        return (
          <div key={m.id} className="target-row">
            <Check style={{ width: 13, color: curOk ? "var(--green)" : "var(--amber)" }} />
            <b>{m.name}</b>
            <select value={cur} onChange={(e) => setProfileOf({ ...profileOf, [m.id]: e.target.value })} title="送信先プロファイル">
              {profiles.map((p) => <option key={p.name} value={p.name}>{p.name === "default" ? "default（既定）" : p.name}{p.hasKey ? "" : "（キー未設定）"}</option>)}
            </select>
            <span className="muted">{!curOk ? "キー未設定" : s?.model?.model ?? (s?.online ? "" : "offline")}</span>
          </div>
        );
      })}
    </>
  );
  return (
    <section className="chat">
      <aside className="history">
        <header><b>履歴</b><button className="icon small" onClick={() => setBatchId(null)} title="新規"><Plus /></button></header>
        <p>最近のプロンプト</p>
        {(history.data?.batches ?? []).length === 0 ? <small className="muted" style={{ padding: "0 7px" }}>まだ送信していません</small> : null}
        {(history.data?.batches ?? []).map((b) => (
          <button key={b.id} className={b.id === batchId ? "active" : ""} onClick={() => setBatchId(b.id)}>
            <MessageSquare />
            <div><b>{b.label}</b><small>{b.runs.length} 台 · {fmtAgo(b.createdAt)}</small></div>
          </button>
        ))}
      </aside>
      <main className="chat-main">
        <header>
          <div>
            <b>{current ? current.label : "新しいプロンプト"}</b>
            <span><i className={`dot ${fleet.connected ? "ok" : "warn"}`} /> {current ? `${current.runs.map((r) => (r.profile ? `${r.machineName}/${r.profile}` : r.machineName)).join(" · ")} · ${fmtAgo(current.createdAt)}` : ids.length ? `${ids.length} 台を選択中` : "対象マシンを選択してください"}</span>
          </div>
          {batchId ? <button className="small" onClick={() => setBatchId(null)}><Plus /> 新規</button> : null}
        </header>
        <div className="messages">
          {batchId ? <BatchView batchId={batchId} /> : (
            <div className="stack" style={{ maxWidth: 560, margin: "6vh auto", textAlign: "center", alignItems: "center" }}>
              <i className="model-logo" style={{ width: 44, height: 44 }}><Bot /></i>
              <h2 style={{ margin: 0, fontSize: 18 }}>複数マシンの Hermes に同時にプロンプトを送る</h2>
              <p className="muted small">対象マシンを選び、入力欄から送信します。各マシンの出力はリアルタイムに並んで表示され、承認が必要なコマンドはここから許可できます。</p>
            </div>
          )}
        </div>
        {narrow ? <div className="card" style={{ margin: "0 10px 10px" }}><label>対象マシン</label>{picker}{targets.length ? <div style={{ marginTop: 8 }}><label>送信先プロファイル</label>{targetList}</div> : null}</div> : null}
        <form className="composer" onSubmit={(e) => { e.preventDefault(); send(); }}>
          <textarea value={prompt} onChange={(e) => setPrompt(e.target.value)} placeholder="例: このマシンのホスト名と OS、ディスク空き容量を報告して" onKeyDown={(e) => { if ((e.metaKey || e.ctrlKey) && e.key === "Enter") send(); }} />
          <div>
            <label className="chip" title="モデル（任意、空なら各マシンの既定）"><Sparkles /><input value={model} onChange={(e) => setModel(e.target.value)} placeholder="既定モデル" /></label>
            <span />
            <small>⌘/Ctrl + ↵ で送信</small>
            <button type="submit" className="send" disabled={!canSend}><Send /> {ids.length} 台に送信</button>
          </div>
          <ErrorBox error={err} />
        </form>
      </main>
      {narrow ? null : <aside className="context">
        <header><b>送信先</b><small className="muted">{ids.length} / {(machines.data ?? []).length}</small></header>
        <section>
          <small>対象マシン</small>
          {picker}
        </section>
        <section>
          <small>選択中 · {targets.length} 台 · プロファイル</small>
          {targetList}
        </section>
      </aside>}
    </section>
  );
}

export function BatchView({ batchId }: { batchId: string }) {
  const [runs, setRuns] = useState<LiveRun[]>([]);
  const runsRef = useRef(runs);
  runsRef.current = runs;
  useEffect(() => {
    setRuns([]);
    const es = eventSource(`/api/runs/batches/${batchId}/stream`);
    es.addEventListener("runs", (e) => {
      const d = JSON.parse((e as MessageEvent).data) as { runs: FleetRun[] };
      setRuns(d.runs.map((r) => ({ ...r, live: r.output, events: [] })));
    });
    es.addEventListener("run", (e) => {
      const ev = JSON.parse((e as MessageEvent).data) as RunStreamEvent;
      setRuns((prev) => prev.map((r) => {
        if (r.id !== ev.runId) return r;
        const n = { ...r, events: r.events.slice() };
        const t = ev.event;
        if (t.event === "message.delta") n.live += String(t.delta ?? "");
        else if (t.event === "tool.started") n.events.push(`▶ ${String(t.tool_name ?? "tool")}: ${String(t.preview ?? "")}`);
        else if (t.event === "tool.completed") n.events.push(`✓ ${String(t.tool_name ?? "tool")}: ${String(t.preview ?? "")}`);
        else if (t.event === "approval.request") { n.approval = { request_id: t.request_id as string, command: t.command as string, choices: t.choices as string[] }; n.status = "waiting_for_approval"; }
        else if (t.event === "fleet.status") { n.status = String(t.status); if (typeof t.output === "string" && t.output) n.live = t.output; if (typeof t.error === "string") n.error = t.error; if (n.status !== "waiting_for_approval") n.approval = undefined; }
        else if (t.event.startsWith("run.")) { n.status = t.event.slice(4); if (typeof t.output === "string" && t.output) n.live = t.output; if (typeof t.error === "string") n.error = t.error; n.approval = undefined; }
        else n.events.push(t.event);
        return n;
      }));
    });
    es.onerror = () => es.close();
    return () => es.close();
  }, [batchId]);

  const approve = async (r: LiveRun, choice: string) => {
    await api("POST", `/api/runs/${r.id}/approval`, { choice, request_id: r.approval?.request_id });
    setRuns((prev) => prev.map((x) => (x.id === r.id ? { ...x, approval: undefined, status: "running" } : x)));
  };
  const stop = (r: LiveRun) => api("POST", `/api/runs/${r.id}/stop`);
  const terminal = (s: string) => ["completed", "failed", "cancelled", "interrupted"].includes(s);

  return (
    <div className="stack">
      {runs[0] ? <div className="message"><span className="avatar purple">管</span><p>{runs[0].prompt}</p></div> : <p className="muted small">読み込み中…</p>}
      <div className="runs">
        {runs.map((r) => (
          <div key={r.id} className="card stack">
            <div className="row" style={{ justifyContent: "space-between" }}>
              <strong className="row" style={{ gap: 6 }}><i className="model-logo"><Bot /></i>{r.machineName}{r.profile ? <span className="muted"> / {r.profile}</span> : null}</strong>
              <span className="row">
                <Badge kind={r.status === "completed" ? "ok" : r.status === "failed" ? "err" : r.status === "waiting_for_approval" ? "warn" : terminal(r.status) ? undefined : "info"}>{r.status}</Badge>
                {!terminal(r.status) ? <button className="small" onClick={() => stop(r)} title="停止"><Square /> 停止</button> : null}
              </span>
            </div>
            {r.approval ? (
              <div className="alert">
                <div><strong>承認が必要です</strong>{r.approval.command ? <>: <code>{r.approval.command}</code></> : null}</div>
                <div className="row" style={{ marginTop: 6 }}>
                  {(r.approval.choices ?? ["once", "deny"]).map((c) => <button key={c} className={`small ${c === "deny" ? "danger" : c === "once" ? "primary" : ""}`} onClick={() => approve(r, c)}>{{ once: "1 回許可", session: "このセッションで許可", always: "常に許可", deny: "拒否" }[c] ?? c}</button>)}
                </div>
              </div>
            ) : null}
            <div className="run-out">{r.live || (terminal(r.status) ? "" : "…")}</div>
            {r.error ? <div className="alert err">{r.error}</div> : null}
            {r.events.length ? <div className="events">{r.events.slice(-8).map((e, i) => <div key={i}>{e}</div>)}</div> : null}
            {r.sessionId ? <div className="muted small mono">session: {r.sessionId}</div> : null}
          </div>
        ))}
      </div>
    </div>
  );
}

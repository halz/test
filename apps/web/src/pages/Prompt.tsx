import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, eventSource, fmtAgo } from "../api";
import { useFleet, useMachines } from "../hooks";
import { Badge, ErrorBox, MachinePicker } from "../components/ui";
import type { FleetRun, RunStreamEvent } from "../types";

interface LiveRun extends FleetRun {
  live: string;
  events: string[];
  approval?: { request_id?: string; command?: string; choices?: string[] };
}

export function Prompt() {
  const machines = useMachines();
  const fleet = useFleet();
  const [ids, setIds] = useState<string[]>([]);
  const [prompt, setPrompt] = useState("");
  const [model, setModel] = useState("");
  const [batchId, setBatchId] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const history = useQuery({ queryKey: ["batches"], queryFn: () => api<{ batches: { id: string; label: string; createdAt: number; runs: FleetRun[] }[] }>("GET", "/api/runs/batches?limit=20"), refetchInterval: 10000 });

  const send = async () => {
    setErr(null);
    setBusy(true);
    try {
      const r = await api<{ batchId: string }>("POST", "/api/runs", { machineIds: ids, prompt, ...(model ? { model } : {}) });
      setBatchId(r.batchId);
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <div className="topbar"><h1>プロンプト送信</h1></div>
      <div className="stack">
        <div className="card stack">
          <div><label>対象マシン</label><MachinePicker machines={machines.data ?? []} value={ids} onChange={setIds} snapshots={fleet.snapshots} /></div>
          <div><label>プロンプト（各マシンの Hermes に同じ内容を送ります）</label><textarea value={prompt} onChange={(e) => setPrompt(e.target.value)} placeholder="例: このマシンのホスト名と OS、ディスク空き容量を報告して" onKeyDown={(e) => { if ((e.metaKey || e.ctrlKey) && e.key === "Enter" && ids.length && prompt.trim()) send(); }} /></div>
          <div className="row">
            <div style={{ width: 260 }}><label>モデル（任意、空なら各マシンの既定）</label><input value={model} onChange={(e) => setModel(e.target.value)} placeholder="hermes-agent" /></div>
            <span className="spacer" />
            <button className="primary" disabled={busy || ids.length === 0 || !prompt.trim()} onClick={send}>{ids.length} 台に送信 (⌘/Ctrl+Enter)</button>
          </div>
          <ErrorBox error={err} />
        </div>
        {batchId ? <BatchView batchId={batchId} /> : null}
        <div className="card stack">
          <strong>履歴</strong>
          <table>
            <thead><tr><th>日時</th><th>プロンプト</th><th>結果</th><th></th></tr></thead>
            <tbody>
              {(history.data?.batches ?? []).map((b) => (
                <tr key={b.id}>
                  <td className="small">{fmtAgo(b.createdAt)}</td>
                  <td style={{ maxWidth: 420, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{b.label}</td>
                  <td className="small">{b.runs.map((r) => `${r.machineName}:${r.status}`).join(", ")}</td>
                  <td><button className="small" onClick={() => setBatchId(b.id)}>表示</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}

function BatchView({ batchId }: { batchId: string }) {
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
      {runs[0] ? <div className="card small"><span className="muted">プロンプト: </span>{runs[0].prompt}</div> : null}
      <div className="runs">
        {runs.map((r) => (
          <div key={r.id} className="card stack">
            <div className="row" style={{ justifyContent: "space-between" }}>
              <strong>{r.machineName}</strong>
              <span className="row">
                <Badge kind={r.status === "completed" ? "ok" : r.status === "failed" ? "err" : r.status === "waiting_for_approval" ? "warn" : terminal(r.status) ? undefined : "info"}>{r.status}</Badge>
                {!terminal(r.status) ? <button className="small" onClick={() => stop(r)}>停止</button> : null}
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

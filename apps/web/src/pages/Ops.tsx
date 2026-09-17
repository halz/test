import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, eventSource, fmtAgo } from "../api";
import { useFleet, useMachines } from "../hooks";
import { Badge, ErrorBox, MachinePicker, Modal } from "../components/ui";
import { OPS_LABELS, type OpsJob, type OpsKind } from "../types";

export function Ops() {
  const machines = useMachines();
  const fleet = useFleet();
  const [kind, setKind] = useState<OpsKind>("gateway.restart");
  const [ids, setIds] = useState<string[]>([]);
  const [canary, setCanary] = useState(true);
  const [confirming, setConfirming] = useState(false);
  const [jobId, setJobId] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const history = useQuery({ queryKey: ["ops"], queryFn: () => api<{ jobs: OpsJob[] }>("GET", "/api/ops"), refetchInterval: 5000 });
  const targets = (machines.data ?? []).filter((m) => ids.includes(m.id));

  const start = async () => {
    setErr(null);
    try {
      const r = await api<{ job: OpsJob }>("POST", "/api/ops", { kind, machineIds: ids, canary: kind === "update" ? canary : false });
      setJobId(r.job.id);
      setConfirming(false);
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };
  return (
    <>
      <div className="page-bar"><h1>一括操作</h1></div>
      <div className="stack">
        <div className="card stack">
          <div className="row">
            <div style={{ minWidth: 220 }}><label>操作</label><select value={kind} onChange={(e) => setKind(e.target.value as OpsKind)}>{(Object.keys(OPS_LABELS) as OpsKind[]).map((k) => <option key={k} value={k}>{OPS_LABELS[k]}</option>)}</select></div>
            {kind === "update" ? <label className="row" style={{ marginTop: 18 }}><input type="checkbox" style={{ width: "auto" }} checked={canary} onChange={(e) => setCanary(e.target.checked)} /> カナリア方式（1 台成功後に残りを実行）</label> : null}
          </div>
          <div><label>対象マシン</label><MachinePicker machines={machines.data ?? []} value={ids} onChange={setIds} snapshots={fleet.snapshots} /></div>
          <ErrorBox error={err} />
          <div className="row"><button className={kind === "gateway.stop" ? "danger" : "primary"} disabled={ids.length === 0} onClick={() => setConfirming(true)}>{OPS_LABELS[kind]} を {ids.length} 台で実行…</button></div>
        </div>
        {jobId ? <JobView jobId={jobId} /> : null}
        <div className="card stack">
          <strong>履歴</strong>
          <table>
            <thead><tr><th>開始</th><th>操作</th><th>結果</th><th>対象</th><th></th></tr></thead>
            <tbody>
              {(history.data?.jobs ?? []).map((j) => (
                <tr key={j.id}>
                  <td className="small">{fmtAgo(j.createdAt)}</td><td>{OPS_LABELS[j.kind]}{j.canary ? " (canary)" : ""}</td>
                  <td><JobBadge status={j.status} /></td>
                  <td className="small">{j.machines.map((m) => `${m.machineName}:${m.status}`).join(", ")}</td>
                  <td><button className="small" onClick={() => setJobId(j.id)}>表示</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      {confirming ? (
        <Modal title="実行の確認" onClose={() => setConfirming(false)} footer={<><button onClick={() => setConfirming(false)}>キャンセル</button><button className={kind === "gateway.stop" ? "danger" : "primary"} onClick={start}>実行する</button></>}>
          <p><strong>{OPS_LABELS[kind]}</strong> を以下の {targets.length} 台で実行します{kind === "update" && canary && targets.length > 1 ? `（まず ${targets[0]?.name} で実行し、成功したら残りを実行）` : ""}。</p>
          <ul>{targets.map((m) => <li key={m.id}>{m.name} <span className="muted small">{m.dashboardUrl}</span></li>)}</ul>
          {kind === "gateway.stop" ? <div className="alert err">停止中はそのマシンへのプロンプト送信や cron が動きません。</div> : null}
        </Modal>
      ) : null}
    </>
  );
}

export function JobBadge({ status }: { status: OpsJob["status"] | OpsJob["machines"][number]["status"] }) {
  const kind = status === "ok" ? "ok" : status === "running" || status === "pending" ? "info" : status === "skipped" ? undefined : status === "partial" ? "warn" : "err";
  return <Badge kind={kind}>{status}</Badge>;
}

export function JobView({ jobId }: { jobId: string }) {
  const [job, setJob] = useState<OpsJob | null>(null);
  useEffect(() => {
    setJob(null);
    const es = eventSource(`/api/ops/${jobId}/stream`);
    es.addEventListener("job", (e) => setJob(JSON.parse((e as MessageEvent).data)));
    es.onerror = () => es.close();
    return () => es.close();
  }, [jobId]);
  if (!job) return <div className="card muted">ジョブを読み込み中…</div>;
  return (
    <div className="card stack">
      <div className="row"><strong>{OPS_LABELS[job.kind]}</strong><JobBadge status={job.status} />{job.canary ? <Badge>canary</Badge> : null}<span className="muted small">{fmtAgo(job.createdAt)}</span></div>
      <div className="stack">
        {job.machines.map((m) => (
          <div key={m.machineId} className="stack" style={{ gap: 4 }}>
            <div className="row"><JobBadge status={m.status} /><strong>{m.machineName}</strong><span className="muted small">{m.message}</span></div>
            {m.lines.length ? <pre style={{ maxHeight: 160, margin: 0 }}>{m.lines.slice(-12).join("\n")}</pre> : null}
          </div>
        ))}
      </div>
    </div>
  );
}

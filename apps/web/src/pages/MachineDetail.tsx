import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, fmtAgo, fmtBytes, fmtDuration } from "../api";
import type { FleetState } from "../hooks";
import { Badge, ErrorBox, Meter, StatusDot, osIcon, useAsync } from "../components/ui";
import { OPS_LABELS, type OpsKind } from "../types";
import { JobView } from "./Ops";
import { ModelsTab } from "./machine/ModelsTab";
import { ProfilesTab } from "./machine/ProfilesTab";

type Tab = "overview" | "models" | "profiles" | "logs" | "sessions" | "cron" | "config" | "ops";

export function MachineDetail({ fleet }: { fleet: FleetState }) {
  const { id = "" } = useParams();
  const [tab, setTab] = useState<Tab>("overview");
  const snap = fleet.snapshots.find((s) => s.machine.id === id);
  if (!snap) return <div className="card">読み込み中… <Link to="/machines">マシン一覧へ</Link></div>;
  const m = snap.machine;
  const st = snap.dashboard?.status;
  const stats = snap.dashboard?.stats;
  return (
    <>
      <div className="page-bar">
        <h1><StatusDot snap={snap} /> {osIcon(m.os)} {m.name}</h1>
        {snap.version ? <Badge>v{snap.version}</Badge> : null}
        {st ? <Badge kind={st.gateway_running ? "ok" : "warn"}>gateway {st.gateway_state ?? ""}</Badge> : null}
        {snap.update?.update_available ? <Badge kind="info">更新あり ({snap.update.behind} behind)</Badge> : null}
        <span className="spacer" />
        <button onClick={() => api("POST", `/api/machines/${id}/refresh?update=1`)}>再取得</button>
      </div>
      {snap.alerts.length ? <div className="stack" style={{ marginBottom: 12 }}>{snap.alerts.map((a) => <div key={a.code} className={`alert ${a.level === "error" ? "err" : ""}`}>{a.message}</div>)}</div> : null}
      <div className="tabs">
        {(["overview", "models", "profiles", "logs", "sessions", "cron", "config", "ops"] as Tab[]).map((t) => (
          <button key={t} className={tab === t ? "active" : ""} onClick={() => setTab(t)}>{{ overview: "概要", models: "モデル・プロバイダ", profiles: "プロファイル", logs: "ログ", sessions: "セッション", cron: "cron", config: "設定", ops: "操作" }[t]}</button>
        ))}
      </div>
      {tab === "overview" ? (
        <div className="grid">
          <div className="card stack">
            <strong>ホスト</strong>
            {stats ? (
              <dl className="kv">
                <dt>ホスト名</dt><dd>{stats.hostname}</dd>
                <dt>OS</dt><dd>{stats.platform ?? stats.system} ({stats.arch})</dd>
                <dt>CPU</dt><dd>{stats.cpu_count} cores · {stats.cpu_percent ?? "?"}% · load {stats.load_avg?.map((n) => n.toFixed(1)).join(" / ")}</dd>
                <dt>メモリ</dt><dd>{fmtBytes(stats.memory?.used)} / {fmtBytes(stats.memory?.total)}</dd>
                <dt>ディスク</dt><dd>{fmtBytes(stats.disk?.used)} / {fmtBytes(stats.disk?.total)} (空き {fmtBytes(stats.disk?.free)})</dd>
                <dt>稼働時間</dt><dd>{fmtDuration(stats.uptime_seconds)}</dd>
              </dl>
            ) : <span className="muted">{snap.dashboard?.error ?? "取得できません"}</span>}
            {stats ? <><Meter label="CPU" pct={stats.cpu_percent} /><Meter label="メモリ" pct={stats.memory?.percent} /><Meter label="ディスク" pct={stats.disk?.percent} /></> : null}
          </div>
          <div className="card stack">
            <strong>Hermes</strong>
            <dl className="kv">
              <dt>バージョン</dt><dd>{snap.version ?? "-"} {snap.update?.update_available ? <Badge kind="info">→ 更新あり</Badge> : snap.update ? <Badge kind="ok">最新</Badge> : null}</dd>
              <dt>ゲートウェイ</dt><dd>{st ? `${st.gateway_running ? "running" : "stopped"} (${st.gateway_state ?? "-"})` : "-"}</dd>
              <dt>アクティブセッション</dt><dd>{st?.active_sessions ?? "-"}</dd>
              <dt>API サーバー</dt><dd>{snap.api ? (snap.api.ok ? `ok (${snap.api.status ?? ""}, runs: ${snap.api.activeRuns ?? 0})` : snap.api.error) : "未設定"}</dd>
              <dt>コンポーネント</dt><dd className="row">{Object.entries(st?.components ?? {}).map(([k, v]) => <Badge key={k} kind={v.status === "ok" ? "ok" : "warn"}>{k}: {v.status}</Badge>)}</dd>
              <dt>確認時刻</dt><dd>{fmtAgo(snap.checkedAt)} · {snap.latencyMs ?? "?"}ms</dd>
              <dt>ダッシュボード</dt><dd className="mono">{m.dashboardUrl || "-"}</dd>
              <dt>API</dt><dd className="mono">{m.apiUrl || "-"}</dd>
            </dl>
            {snap.update?.commits?.length ? <details><summary className="small">未適用のコミット ({snap.update.commits.length})</summary><ul className="small">{snap.update.commits.map((c) => <li key={c.sha}><code>{c.sha.slice(0, 7)}</code> {c.summary}</li>)}</ul></details> : null}
          </div>
        </div>
      ) : null}
      {tab === "models" ? <ModelsTab id={id} /> : null}
      {tab === "profiles" ? <ProfilesTab id={id} /> : null}
      {tab === "logs" ? <LogsTab id={id} /> : null}
      {tab === "sessions" ? <SessionsTab id={id} /> : null}
      {tab === "cron" ? <CronTab id={id} /> : null}
      {tab === "config" ? <ConfigTab id={id} /> : null}
      {tab === "ops" ? <OpsTab id={id} name={m.name} /> : null}
    </>
  );
}

function LogsTab({ id }: { id: string }) {
  const [file, setFile] = useState("agent");
  const [level, setLevel] = useState("");
  const [search, setSearch] = useState("");
  const [lines, setLines] = useState(200);
  const [auto, setAuto] = useState(false);
  const q = useAsync(() => api<{ file: string; lines: string[] }>("GET", `/api/machines/${id}/logs?file=${file}&lines=${lines}${level ? `&level=${level}` : ""}${search ? `&search=${encodeURIComponent(search)}` : ""}`), [id, file, level, lines, search]);
  useEffect(() => {
    if (!auto) return;
    const t = setInterval(q.reload, 5000);
    return () => clearInterval(t);
  }, [auto, q.reload]);
  return (
    <div className="stack">
      <div className="row">
        <select style={{ width: 140 }} value={file} onChange={(e) => setFile(e.target.value)}><option value="agent">agent</option><option value="gateway">gateway</option><option value="errors">errors</option></select>
        <select style={{ width: 120 }} value={level} onChange={(e) => setLevel(e.target.value)}><option value="">全レベル</option><option>ERROR</option><option>WARNING</option><option>INFO</option><option>DEBUG</option></select>
        <select style={{ width: 100 }} value={lines} onChange={(e) => setLines(Number(e.target.value))}>{[50, 100, 200, 500].map((n) => <option key={n} value={n}>{n} 行</option>)}</select>
        <input style={{ width: 220 }} placeholder="検索" value={search} onChange={(e) => setSearch(e.target.value)} />
        <label className="row" style={{ margin: 0 }}><input type="checkbox" style={{ width: "auto" }} checked={auto} onChange={(e) => setAuto(e.target.checked)} /> 5 秒ごと更新</label>
        <button onClick={q.reload}>更新</button>
      </div>
      <ErrorBox error={q.error} />
      <pre style={{ maxHeight: "70vh" }}>{q.data?.lines.join("\n") ?? (q.loading ? "読み込み中…" : "")}</pre>
    </div>
  );
}

function SessionsTab({ id }: { id: string }) {
  const q = useAsync(() => api<{ sessions: Record<string, unknown>[]; total: number }>("GET", `/api/machines/${id}/sessions?limit=50`), [id]);
  const [open, setOpen] = useState<string | null>(null);
  const msgs = useAsync(() => (open ? api<unknown>("GET", `/api/machines/${id}/sessions/${open}/messages?limit=100`) : Promise.resolve(null)), [id, open]);
  return (
    <div className="stack">
      <ErrorBox error={q.error} />
      <div className="card" style={{ padding: 0, overflow: "auto" }}>
        <table>
          <thead><tr><th>タイトル</th><th>ソース</th><th>件数</th><th>開始</th><th>状態</th></tr></thead>
          <tbody>
            {(q.data?.sessions ?? []).map((s) => (
              <tr key={String(s.id)} onClick={() => setOpen(String(s.id))} style={{ cursor: "pointer" }}>
                <td>{String(s.title ?? s.id)}</td><td>{String(s.source ?? "")}</td><td>{String(s.message_count ?? "")}</td><td>{fmtAgo(s.started_at as number)}</td>
                <td>{s.is_active ? <Badge kind="ok">active</Badge> : s.archived ? <Badge>archived</Badge> : null}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {open ? <div className="card"><div className="row" style={{ justifyContent: "space-between" }}><strong>{open}</strong><button className="small" onClick={() => setOpen(null)}>閉じる</button></div><pre>{msgs.data ? JSON.stringify(msgs.data, null, 2) : msgs.error ?? "…"}</pre></div> : null}
    </div>
  );
}

export function CronTab({ id }: { id: string }) {
  const q = useAsync(() => api<{ jobs?: Record<string, unknown>[] } | Record<string, unknown>[]>("GET", `/api/machines/${id}/cron`), [id]);
  const jobs = Array.isArray(q.data) ? q.data : q.data?.jobs ?? [];
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState({ name: "", prompt: "", schedule: "0 9 * * *", deliver: "local" });
  const act = async (jid: string, action: string) => {
    if (action === "delete") {
      if (!confirm("削除しますか？")) return;
      await api("DELETE", `/api/machines/${id}/cron/${jid}`);
    } else await api("POST", `/api/machines/${id}/cron/${jid}/${action}`);
    q.reload();
  };
  const create = async () => {
    await api("POST", `/api/machines/${id}/cron`, form);
    setCreating(false);
    q.reload();
  };
  return (
    <div className="stack">
      <div className="row"><button className="primary" onClick={() => setCreating(true)}>＋ ジョブ作成</button><button onClick={q.reload}>更新</button></div>
      <ErrorBox error={q.error} />
      {creating ? (
        <div className="card form">
          <div><label>名前</label><input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></div>
          <div><label>スケジュール (cron 式)</label><input value={form.schedule} onChange={(e) => setForm({ ...form, schedule: e.target.value })} /></div>
          <div className="full"><label>プロンプト</label><textarea value={form.prompt} onChange={(e) => setForm({ ...form, prompt: e.target.value })} /></div>
          <div><label>配信先</label><input value={form.deliver} onChange={(e) => setForm({ ...form, deliver: e.target.value })} /></div>
          <div className="row full" style={{ justifyContent: "flex-end" }}><button onClick={() => setCreating(false)}>キャンセル</button><button className="primary" onClick={create} disabled={!form.name || !form.prompt}>作成</button></div>
        </div>
      ) : null}
      <CronTable jobs={jobs.map((j) => ({ ...j, _machineId: id }))} onAction={(jid, a) => act(jid, a)} />
    </div>
  );
}

export function CronTable({ jobs, onAction, showMachine }: { jobs: (Record<string, unknown> & { _machineId: string; _machineName?: string })[]; onAction: (jid: string, action: string, machineId: string) => void; showMachine?: boolean }) {
  return (
    <div className="card" style={{ padding: 0, overflow: "auto" }}>
      <table>
        <thead><tr>{showMachine ? <th>マシン</th> : null}<th>名前</th><th>スケジュール</th><th>プロンプト</th><th>次回 / 前回</th><th>状態</th><th></th></tr></thead>
        <tbody>
          {jobs.map((j) => (
            <tr key={`${j._machineId}:${String(j.id)}`}>
              {showMachine ? <td>{j._machineName}</td> : null}
              <td>{String(j.name ?? j.id)}</td><td className="mono">{String(j.schedule ?? j.cron ?? "")}</td>
              <td className="muted" style={{ maxWidth: 320, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{String(j.prompt ?? "")}</td>
              <td className="small">{fmtAgo(j.next_run as string)} / {fmtAgo(j.last_run as string)}</td>
              <td>{j.paused || j.enabled === false ? <Badge kind="warn">paused</Badge> : <Badge kind="ok">active</Badge>}</td>
              <td className="row" style={{ justifyContent: "flex-end" }}>
                {j.paused || j.enabled === false ? <button className="small" onClick={() => onAction(String(j.id), "resume", j._machineId)}>再開</button> : <button className="small" onClick={() => onAction(String(j.id), "pause", j._machineId)}>一時停止</button>}
                <button className="small" onClick={() => onAction(String(j.id), "trigger", j._machineId)}>今すぐ実行</button>
                <button className="small danger" onClick={() => onAction(String(j.id), "delete", j._machineId)}>削除</button>
              </td>
            </tr>
          ))}
          {jobs.length === 0 ? <tr><td colSpan={7} className="muted">ジョブなし</td></tr> : null}
        </tbody>
      </table>
    </div>
  );
}

function ConfigTab({ id }: { id: string }) {
  const cfg = useAsync(() => api<unknown>("GET", `/api/machines/${id}/config`), [id]);
  const env = useAsync(() => api<unknown>("GET", `/api/machines/${id}/env`), [id]);
  return (
    <div className="grid" style={{ gridTemplateColumns: "1fr 1fr" }}>
      <div className="card"><strong>config.yaml</strong><ErrorBox error={cfg.error} /><pre>{cfg.data ? JSON.stringify(cfg.data, null, 2) : "…"}</pre></div>
      <div className="card"><strong>.env（値はマスク）</strong><ErrorBox error={env.error} /><pre>{env.data ? JSON.stringify(env.data, null, 2) : "…"}</pre></div>
    </div>
  );
}

function OpsTab({ id, name }: { id: string; name: string }) {
  const [jobId, setJobId] = useState<string | null>(null);
  const run = async (kind: OpsKind) => {
    if (!confirm(`${name}: ${OPS_LABELS[kind]} を実行しますか？`)) return;
    const r = await api<{ job: { id: string } }>("POST", "/api/ops", { kind, machineIds: [id] });
    setJobId(r.job.id);
  };
  return (
    <div className="stack">
      <div className="row">
        {(Object.keys(OPS_LABELS) as OpsKind[]).map((k) => <button key={k} className={k === "update" ? "primary" : k === "gateway.stop" ? "danger" : ""} onClick={() => run(k)}>{OPS_LABELS[k]}</button>)}
      </div>
      {jobId ? <JobView jobId={jobId} /> : null}
    </div>
  );
}

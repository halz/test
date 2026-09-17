import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, ArrowDownToLine, Bot, ChevronRight, Cpu, Laptop, Monitor, RotateCw, Server, Zap } from "lucide-react";
import { api, fmtAgo, fmtDuration } from "../api";
import type { FleetState } from "../hooks";
import { Badge } from "../components/ui";
import type { FleetRun, OpsJob, Snapshot } from "../types";
import { OPS_LABELS } from "../types";

interface Batch { id: string; label: string; createdAt: number; runs: FleetRun[] }

function greeting(): string {
  const h = new Date().getHours();
  return h < 5 ? "お疲れさまです" : h < 11 ? "おはようございます" : h < 18 ? "こんにちは" : "こんばんは";
}

export function Fleet({ fleet }: { fleet: FleetState }) {
  const snaps = fleet.snapshots;
  const online = snaps.filter((s) => s.online).length;
  const alerts = snaps.flatMap((s) => s.alerts.map((a) => ({ ...a, snap: s })));
  const updates = snaps.filter((s) => s.update?.update_available).length;
  const activeRuns = snaps.reduce((n, s) => n + (s.api?.activeRuns ?? 0), 0);
  const batches = useQuery({ queryKey: ["batches", "recent"], queryFn: () => api<{ batches: Batch[] }>("GET", "/api/runs/batches?limit=6"), refetchInterval: 10000 });
  const jobs = useQuery({ queryKey: ["ops", "recent"], queryFn: () => api<{ jobs: OpsJob[] }>("GET", "/api/ops?limit=6"), refetchInterval: 10000 });
  const date = new Date().toLocaleDateString("ja-JP", { year: "numeric", month: "long", day: "numeric", weekday: "long" });
  const summary = snaps.length === 0
    ? "マシンがまだ登録されていません。"
    : online === snaps.length && alerts.length === 0
      ? "フリートは正常に稼働しています。"
      : `${snaps.length - online} 台がオフライン、${alerts.length} 件の注意があります。`;
  const activity = [
    ...(batches.data?.batches ?? []).map((b) => ({ kind: "run" as const, at: b.createdAt, id: b.id, title: b.label, sub: b.runs.map((r) => r.machineName).join(" · "), status: runStatus(b.runs) })),
    ...(jobs.data?.jobs ?? []).map((j) => ({ kind: "ops" as const, at: j.createdAt, id: j.id, title: OPS_LABELS[j.kind] ?? j.kind, sub: j.machines.map((m) => m.machineName).join(" · "), status: j.status })),
  ].sort((a, b) => b.at - a.at).slice(0, 6);

  return (
    <>
      <div className="page-head">
        <div>
          <p>{date}</p>
          <h1>{greeting()}</h1>
          <span>{summary}{fleet.at ? ` 最終確認 ${fmtAgo(fleet.at)}。` : ""}</span>
        </div>
        <div className="actions">
          <Link to="/ops"><button><Zap /> 一括操作</button></Link>
          <Link to="/prompt"><button className="primary"><Bot /> プロンプト送信</button></Link>
        </div>
      </div>

      <section className="stats">
        <article>
          <Server className="tone-blue" />
          <span>オンライン</span>
          <strong>{online} <small>/ {snaps.length}</small></strong>
          <small>{snaps.length === 0 ? "マシン未登録" : online === snaps.length ? <><b>全台</b> が応答中</> : <><b className="red-text">{snaps.length - online} 台</b> がオフライン</>}</small>
          <div className={`bar ${online < snaps.length ? "warn" : ""}`}><i style={{ width: snaps.length ? `${Math.round((online / snaps.length) * 100)}%` : "0%" }} /></div>
        </article>
        <article>
          <Cpu className="tone-violet" />
          <span>実行中のラン</span>
          <strong>{activeRuns}</strong>
          <small>{fleet.connected ? <><b>ライブ</b> 更新中</> : <><b className="amber-text">再接続中</b></>}</small>
        </article>
        <article>
          <AlertTriangle className={alerts.length ? "tone-amber" : "tone-green"} />
          <span>要対応</span>
          <strong>{alerts.length}</strong>
          <small>{alerts.length ? <><b className="amber-text">{alerts.filter((a) => a.level === "error").length} 件</b> がエラー</> : <><b>問題なし</b></>}</small>
        </article>
        <article>
          <ArrowDownToLine className={updates ? "tone-blue" : "tone-green"} />
          <span>更新あり</span>
          <strong>{updates} <small>台</small></strong>
          <small>{updates ? <Link to="/ops">一括更新へ →</Link> : <><b>最新</b> を実行中</>}</small>
        </article>
      </section>

      <section className="dash">
        <article className="panel">
          <div className="panel-head">
            <div><h2>ライブアクティビティ</h2><p>プロンプト送信と一括操作の直近の履歴</p></div>
            <Link to="/ops">すべて表示 <ChevronRight /></Link>
          </div>
          {activity.length === 0 ? <p className="muted small">まだアクティビティはありません。</p> : null}
          {activity.map((a) => (
            <div className="activity-row" key={`${a.kind}-${a.id}`}>
              <span className={`run-icon ${a.kind === "run" ? "tone-violet" : "tone-blue"}`}>{a.kind === "run" ? <Bot /> : <Zap />}</span>
              <div><b>{a.title}</b><small>{a.sub}</small></div>
              <Badge kind={badgeKind(a.status)}>{a.status}</Badge>
              <time>{fmtAgo(a.at)}</time>
            </div>
          ))}
        </article>
        <article className="panel attention">
          <div className="panel-head"><div><h2>要対応</h2><p>アラートと注意が必要な項目</p></div></div>
          {alerts.length === 0 && updates === 0 ? <p className="muted small">対応が必要な項目はありません。</p> : null}
          {alerts.slice(0, 6).map((a) => (
            <Link to={`/machines/${a.snap.machine.id}`} key={`${a.snap.machine.id}-${a.code}`}>
              <i className={`att-icon ${a.level === "error" ? "tone-red" : "tone-amber"}`}>{a.level === "error" ? "!" : <Server />}</i>
              <div><b>{a.snap.machine.name}</b><small>{a.message}</small></div>
              <ChevronRight />
            </Link>
          ))}
          {updates ? (
            <Link to="/ops">
              <i className="att-icon tone-blue"><ArrowDownToLine /></i>
              <div><b>{updates} 台に Hermes の更新あり</b><small>一括操作から適用できます</small></div>
              <ChevronRight />
            </Link>
          ) : null}
        </article>
        <article className="panel recent">
          <div className="panel-head">
            <div><h2>最近のプロンプト</h2><p>続きから確認できます</p></div>
            <Link to="/prompt">プロンプトを開く <ChevronRight /></Link>
          </div>
          {(batches.data?.batches ?? []).length === 0 ? <p className="muted small">まだ送信したプロンプトはありません。</p> : null}
          <div className="conversations">
            {(batches.data?.batches ?? []).slice(0, 3).map((b) => (
              <Link to={`/prompt?batch=${b.id}`} key={b.id}>
                <i><Bot /></i>
                <div><b>{b.label}</b><small>{b.runs.length} 台 · {runStatus(b.runs)}</small></div>
                <time>{fmtAgo(b.createdAt)}</time>
              </Link>
            ))}
          </div>
        </article>
      </section>

      <div className="page-head" style={{ marginBottom: 14 }}>
        <div><p>INFRASTRUCTURE</p><h1 style={{ fontSize: 18 }}>マシン</h1></div>
        <div className="actions"><Link to="/machines"><button>マシンを管理</button></Link></div>
      </div>
      {fleet.loading ? <p className="muted">読み込み中…</p> : null}
      {!fleet.loading && snaps.length === 0 ? (
        <div className="card">
          <p>マシンがまだ登録されていません。</p>
          <Link to="/machines" className="primary">マシンを追加する →</Link>
        </div>
      ) : null}
      <section className="machine-grid">
        {snaps.map((s) => <MachineCard key={s.machine.id} snap={s} />)}
      </section>
    </>
  );
}

function runStatus(runs: FleetRun[]): string {
  if (runs.some((r) => r.status === "waiting_for_approval")) return "承認待ち";
  if (runs.some((r) => ["queued", "running", "dispatching"].includes(r.status))) return "実行中";
  if (runs.some((r) => r.status === "failed")) return runs.every((r) => r.status === "failed") ? "失敗" : "一部失敗";
  return "完了";
}

function badgeKind(status: string): "ok" | "warn" | "err" | "info" | undefined {
  if (["完了", "ok"].includes(status)) return "ok";
  if (["実行中", "running"].includes(status)) return "info";
  if (["承認待ち", "一部失敗", "partial"].includes(status)) return "warn";
  if (["失敗", "failed"].includes(status)) return "err";
  return undefined;
}

function OsIcon({ os }: { os: string }) {
  const o = os.toLowerCase();
  if (o.includes("mac") || o.includes("darwin")) return <Laptop />;
  if (o.includes("win")) return <Monitor />;
  return <Server />;
}

function osClass(os: string): string {
  const o = os.toLowerCase();
  if (o.includes("mac") || o.includes("darwin")) return "mac";
  if (o.includes("win")) return "win";
  return "";
}

function MachineCard({ snap }: { snap: Snapshot }) {
  const st = snap.dashboard?.status;
  const stats = snap.dashboard?.stats;
  const [acting, setActing] = useState(false);
  const restart = async () => {
    if (!confirm(`${snap.machine.name} のゲートウェイを再起動しますか？`)) return;
    setActing(true);
    try {
      await api("POST", "/api/ops", { kind: "gateway.restart", machineIds: [snap.machine.id] });
    } finally {
      setActing(false);
    }
  };
  const cpu = stats?.cpu_percent;
  const mem = stats?.memory?.percent;
  const status = !snap.online ? { cls: "red", label: "オフライン" }
    : st?.gateway_running ? { cls: "", label: st.gateway_state ?? "running" }
      : st ? { cls: "amber", label: st.gateway_state ?? "停止中" } : { cls: "gray", label: "未接続" };
  return (
    <article>
      <div className="machine-top">
        <i className={`machine ${snap.online ? osClass(snap.machine.os) : "off"}`}><OsIcon os={snap.machine.os} /></i>
        <span className="row small" style={{ gap: 6 }}>
          {snap.update?.update_available ? <Badge kind="info">更新あり</Badge> : null}
          <span className="muted">{snap.version ? `v${snap.version}` : ""}</span>
        </span>
      </div>
      <h2><Link to={`/machines/${snap.machine.id}`}><span className={`dot ${!snap.online ? "err" : snap.alerts.length ? "warn" : "ok"}`} /> {snap.machine.name}</Link></h2>
      <p>{stats?.hostname ?? snap.machine.os}{stats?.platform ? ` · ${stats.platform}` : stats?.system ? ` · ${stats.system}` : ""}{stats?.uptime_seconds ? ` · 稼働 ${fmtDuration(stats.uptime_seconds)}` : ""}</p>
      <span className="row small" style={{ gap: 6 }}>
        <span className={`status ${status.cls}`}><i />gateway: {status.label}</span>
        {snap.api ? <span className={`status ${snap.api.ok ? "blue" : "amber"}`}><i />api: {snap.api.ok ? "ok" : snap.api.error ?? "ng"}</span> : null}
        {typeof st?.active_sessions === "number" ? <span className="status gray"><i />{st.active_sessions} sessions</span> : null}
      </span>
      <div className="model">
        <small>ACTIVE MODEL</small>
        <b>{snap.model?.model ? `${snap.model.model}${snap.model.provider ? ` · ${snap.model.provider}` : ""}` : snap.online ? "—" : "オフライン"}</b>
      </div>
      {stats ? (
        <>
          <div className="load"><span>CPU 負荷 <b>{cpu === undefined ? "-" : `${Math.round(cpu)}%`}</b></span><div className={`bar ${(cpu ?? 0) >= 90 ? "err" : (cpu ?? 0) >= 75 ? "warn" : ""}`}><i style={{ width: `${Math.min(100, cpu ?? 0)}%` }} /></div></div>
          <div className="load"><span>メモリ <b>{mem === undefined ? "-" : `${Math.round(mem)}%`}</b></span><div className={`bar ${(mem ?? 0) >= 90 ? "err" : (mem ?? 0) >= 75 ? "warn" : ""}`}><i style={{ width: `${Math.min(100, mem ?? 0)}%` }} /></div></div>
        </>
      ) : (
        <div className="muted small">{snap.online ? snap.dashboard?.error ?? "ダッシュボード未接続" : "オフライン"}</div>
      )}
      {snap.alerts.length ? (
        <div className="machine-alerts">
          {snap.alerts.map((a) => <span key={a.code} className={a.level === "error" ? "err" : ""}>{a.message}</span>)}
        </div>
      ) : null}
      <footer>
        <span>{fmtAgo(snap.checkedAt)}{snap.latencyMs !== undefined ? ` · ${snap.latencyMs}ms` : ""}</span>
        <span className="row" style={{ gap: 4 }}>
          <button className="small" onClick={restart} disabled={acting || !snap.dashboard?.ok} title="ゲートウェイ再起動"><RotateCw /></button>
          <Link to={`/machines/${snap.machine.id}`}><button className="small">管理 <ChevronRight /></button></Link>
        </span>
      </footer>
    </article>
  );
}

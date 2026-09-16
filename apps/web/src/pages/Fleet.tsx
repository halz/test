import { useState } from "react";
import { Link } from "react-router-dom";
import { api, fmtAgo, fmtDuration } from "../api";
import type { FleetState } from "../hooks";
import { Badge, Meter, StatusDot, osIcon } from "../components/ui";
import type { Snapshot } from "../types";

export function Fleet({ fleet }: { fleet: FleetState }) {
  const [busy, setBusy] = useState(false);
  const snaps = fleet.snapshots;
  const online = snaps.filter((s) => s.online).length;
  const alerts = snaps.reduce((n, s) => n + s.alerts.length, 0);
  const updates = snaps.filter((s) => s.update?.update_available).length;
  const refresh = async () => {
    setBusy(true);
    try {
      await fleet.refresh();
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <div className="topbar">
        <h1>フリート</h1>
        <Badge kind={online === snaps.length ? "ok" : "warn"}>{online}/{snaps.length} オンライン</Badge>
        {alerts ? <Badge kind="warn">{alerts} 件の注意</Badge> : null}
        {updates ? <Badge kind="info">{updates} 台に更新あり</Badge> : null}
        <span className="spacer" />
        <span className="muted small">{fleet.connected ? "ライブ" : "再接続中…"} · {fleet.at ? fmtAgo(fleet.at) : ""}</span>
        <button onClick={refresh} disabled={busy}>{busy ? "更新中…" : "今すぐ更新"}</button>
      </div>
      {fleet.loading ? <p className="muted">読み込み中…</p> : null}
      {!fleet.loading && snaps.length === 0 ? (
        <div className="card">
          <p>マシンがまだ登録されていません。</p>
          <Link to="/machines" className="primary">マシンを追加する →</Link>
        </div>
      ) : null}
      <div className="grid">
        {snaps.map((s) => <MachineCard key={s.machine.id} snap={s} />)}
      </div>
    </>
  );
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
  return (
    <div className="card stack">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <Link to={`/machines/${snap.machine.id}`} style={{ fontWeight: 600, fontSize: 15, color: "var(--text)" }}>
          <StatusDot snap={snap} /> {osIcon(snap.machine.os)} {snap.machine.name}
        </Link>
        <span className="muted small">{snap.version ? `v${snap.version}` : ""}</span>
      </div>
      <div className="row small">
        {st ? <Badge kind={st.gateway_running ? "ok" : "warn"}>gateway: {st.gateway_state ?? (st.gateway_running ? "running" : "stopped")}</Badge> : <Badge>gateway: ?</Badge>}
        {snap.api ? <Badge kind={snap.api.ok ? "ok" : "warn"}>api: {snap.api.ok ? "ok" : snap.api.error ?? "ng"}</Badge> : null}
        {typeof st?.active_sessions === "number" ? <Badge>{st.active_sessions} sessions</Badge> : null}
        {snap.update?.update_available ? <Badge kind="info">更新あり</Badge> : null}
      </div>
      {stats ? (
        <div className="stack" style={{ gap: 6 }}>
          <Meter label={`CPU (${stats.cpu_count ?? "?"} cores)`} pct={stats.cpu_percent} />
          <Meter label={`メモリ`} pct={stats.memory?.percent} />
          <Meter label={`ディスク`} pct={stats.disk?.percent} />
          <div className="muted small">{stats.hostname} · {stats.platform ?? stats.system} · uptime {fmtDuration(stats.uptime_seconds)}</div>
        </div>
      ) : (
        <div className="muted small">{snap.online ? snap.dashboard?.error ?? "ダッシュボード未接続" : "オフライン"}</div>
      )}
      {snap.alerts.length ? (
        <div className="stack" style={{ gap: 4 }}>
          {snap.alerts.map((a) => <div key={a.code} className={`alert ${a.level === "error" ? "err" : ""}`}>{a.message}</div>)}
        </div>
      ) : null}
      <div className="row small" style={{ justifyContent: "space-between" }}>
        <span className="muted">{fmtAgo(snap.checkedAt)}{snap.latencyMs !== undefined ? ` · ${snap.latencyMs}ms` : ""}</span>
        <span className="row">
          <button className="small" onClick={restart} disabled={acting || !snap.dashboard?.ok}>再起動</button>
          <Link to={`/machines/${snap.machine.id}`}><button className="small">詳細</button></Link>
        </span>
      </div>
    </div>
  );
}

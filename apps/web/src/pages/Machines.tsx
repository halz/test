import { useState } from "react";
import { Link } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { api } from "../api";
import { useMachines } from "../hooks";
import { Badge, ErrorBox, Modal, osIcon } from "../components/ui";
import type { Machine } from "../types";

interface FormState {
  name: string; os: string; tags: string; dashboardUrl: string; dashboardAuthKind: "none" | "basic"; dashboardUsername: string; dashboardPassword: string; apiUrl: string; apiKey: string; notes: string;
}
const empty: FormState = { name: "", os: "macOS", tags: "", dashboardUrl: "", dashboardAuthKind: "basic", dashboardUsername: "admin", dashboardPassword: "", apiUrl: "", apiKey: "", notes: "" };

interface Probe { dashboard: { ok: boolean; reachable: boolean; authenticated: boolean; version?: string; error?: string }; apiServer: { ok: boolean; reachable: boolean; authenticated: boolean; version?: string; error?: string } }

export function Machines() {
  const machines = useMachines();
  const qc = useQueryClient();
  const [editing, setEditing] = useState<Machine | "new" | null>(null);
  const [guide, setGuide] = useState<Machine | null>(null);
  const remove = async (m: Machine) => {
    if (!confirm(`${m.name} を削除しますか？（マシン側には何もしません）`)) return;
    await api("DELETE", `/api/machines/${m.id}`);
    qc.invalidateQueries({ queryKey: ["machines"] });
  };
  return (
    <>
      <div className="page-bar"><h1>マシン</h1><span className="spacer" /><button className="primary" onClick={() => setEditing("new")}>＋ 追加</button></div>
      <ErrorBox error={machines.error ? (machines.error as Error).message : null} />
      <div className="card" style={{ padding: 0, overflow: "auto" }}>
        <table>
          <thead><tr><th>名前</th><th>OS</th><th>タグ</th><th>ダッシュボード</th><th>API サーバー</th><th></th></tr></thead>
          <tbody>
            {(machines.data ?? []).map((m) => (
              <tr key={m.id}>
                <td><Link to={`/machines/${m.id}`}>{osIcon(m.os)} {m.name}</Link>{m.notes ? <div className="muted small">{m.notes}</div> : null}</td>
                <td>{m.os}</td>
                <td>{m.tags.map((t) => <Badge key={t}>#{t}</Badge>)}</td>
                <td className="mono">{m.dashboardUrl || "-"}<div className="muted small">{m.dashboardAuthKind === "basic" ? `basic (${m.dashboardUsername}${m.hasDashboardPassword ? "" : ", パスワード未設定"})` : "認証なし"}</div></td>
                <td className="mono">{m.apiUrl || "-"}<div className="muted small">{m.hasApiKey ? "キー設定済み" : "キー未設定"}</div></td>
                <td className="row" style={{ justifyContent: "flex-end" }}>
                  <button className="small" onClick={() => setGuide(m)}>有効化手順</button>
                  <button className="small" onClick={() => setEditing(m)}>編集</button>
                  <button className="small danger" onClick={() => remove(m)}>削除</button>
                </td>
              </tr>
            ))}
            {machines.data && machines.data.length === 0 ? <tr><td colSpan={6} className="muted">まだ登録がありません。「＋ 追加」から登録してください。</td></tr> : null}
          </tbody>
        </table>
      </div>
      {editing ? <MachineForm machine={editing === "new" ? null : editing} onClose={() => { setEditing(null); qc.invalidateQueries({ queryKey: ["machines"] }); }} /> : null}
      {guide ? <EnrollGuide machine={guide} onClose={() => setGuide(null)} /> : null}
    </>
  );
}

function MachineForm({ machine, onClose }: { machine: Machine | null; onClose: () => void }) {
  const [f, setF] = useState<FormState>(machine ? { name: machine.name, os: machine.os, tags: machine.tags.join(", "), dashboardUrl: machine.dashboardUrl, dashboardAuthKind: machine.dashboardAuthKind, dashboardUsername: machine.dashboardUsername, dashboardPassword: "", apiUrl: machine.apiUrl, apiKey: "", notes: machine.notes } : empty);
  const [probe, setProbe] = useState<Probe | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (k: keyof FormState) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => setF({ ...f, [k]: e.target.value });
  const payload = () => ({ ...f, tags: f.tags.split(",").map((s) => s.trim()).filter(Boolean), dashboardPassword: f.dashboardPassword || undefined, apiKey: f.apiKey || undefined });

  const test = async () => {
    setBusy(true);
    setErr(null);
    setProbe(null);
    try {
      // For an existing machine with unchanged secrets, test the stored credentials.
      const useStored = machine && !f.dashboardPassword && !f.apiKey && f.dashboardUrl === machine.dashboardUrl && f.apiUrl === machine.apiUrl;
      setProbe(await api<Probe>("POST", useStored ? `/api/machines/${machine!.id}/test` : "/api/machines/test", useStored ? undefined : payload()));
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };
  const save = async () => {
    setBusy(true);
    setErr(null);
    try {
      if (machine) await api("PUT", `/api/machines/${machine.id}`, payload());
      else await api("POST", "/api/machines", payload());
      onClose();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };
  const fill = (host: string) => setF({ ...f, dashboardUrl: `http://${host}:9119`, apiUrl: `http://${host}:8642` });

  return (
    <Modal title={machine ? `${machine.name} を編集` : "マシンを追加"} onClose={onClose} footer={<><button onClick={test} disabled={busy}>接続テスト</button><button className="primary" onClick={save} disabled={busy || !f.name}>{machine ? "保存" : "追加"}</button></>}>
      <div className="form">
        <div><label>名前（表示名、64 文字以内）</label><input value={f.name} onChange={set("name")} placeholder="mac-mini" /></div>
        <div><label>OS</label><select value={f.os} onChange={set("os")}><option>macOS</option><option>Windows</option><option>Linux</option></select></div>
        <div className="full"><label>Tailscale ホスト名 / IP（入力すると URL を自動補完）</label><input placeholder="macmini.tailnet-name.ts.net または 100.x.y.z" onBlur={(e) => e.target.value && !f.dashboardUrl && fill(e.target.value.trim())} /></div>
        <div><label>ダッシュボード URL（hermes serve, 既定 9119）</label><input value={f.dashboardUrl} onChange={set("dashboardUrl")} placeholder="http://100.x.y.z:9119" /></div>
        <div><label>ダッシュボード認証</label><select value={f.dashboardAuthKind} onChange={set("dashboardAuthKind")}><option value="basic">ユーザー名 / パスワード</option><option value="none">なし（ループバック/トンネル経由）</option></select></div>
        {f.dashboardAuthKind === "basic" ? (
          <>
            <div><label>ユーザー名</label><input value={f.dashboardUsername} onChange={set("dashboardUsername")} /></div>
            <div><label>パスワード{machine ? "（変更時のみ入力）" : ""}</label><input type="password" value={f.dashboardPassword} onChange={set("dashboardPassword")} autoComplete="new-password" /></div>
          </>
        ) : null}
        <div><label>API サーバー URL（既定 8642）</label><input value={f.apiUrl} onChange={set("apiUrl")} placeholder="http://100.x.y.z:8642" /></div>
        <div><label>API_SERVER_KEY{machine ? "（変更時のみ入力）" : ""}</label><input type="password" value={f.apiKey} onChange={set("apiKey")} autoComplete="new-password" /></div>
        <div><label>タグ（カンマ区切り）</label><input value={f.tags} onChange={set("tags")} placeholder="mac, gpu" /></div>
        <div><label>メモ</label><input value={f.notes} onChange={set("notes")} /></div>
      </div>
      <ErrorBox error={err} />
      {probe ? (
        <div className="stack" style={{ marginTop: 12 }}>
          <ProbeLine label="ダッシュボード" leg={probe.dashboard} />
          <ProbeLine label="API サーバー" leg={probe.apiServer} />
        </div>
      ) : null}
    </Modal>
  );
}

function ProbeLine({ label, leg }: { label: string; leg: Probe["dashboard"] }) {
  const kind = leg.ok ? "ok" : leg.reachable ? "warn" : "err";
  return (
    <div className={`alert ${kind === "ok" ? "ok" : kind === "err" ? "err" : ""}`}>
      <strong>{label}</strong>: {leg.ok ? `OK (v${leg.version ?? "?"})` : leg.reachable ? `到達可だが認証失敗: ${leg.error ?? ""}` : `到達不可: ${leg.error ?? ""}`}
    </div>
  );
}

function randKey(): string {
  const b = new Uint8Array(32);
  crypto.getRandomValues(b);
  return btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function EnrollGuide({ machine, onClose }: { machine: Machine; onClose: () => void }) {
  const [key] = useState(randKey);
  const [secret] = useState(randKey);
  const isWin = machine.os.toLowerCase().includes("win");
  const ip = (() => { try { return new URL(machine.apiUrl || machine.dashboardUrl).hostname; } catch { return "<tailscale-ip>"; } })();
  const env = `# ${machine.name}: ${isWin ? "%LOCALAPPDATA%\\hermes\\.env" : "~/.hermes/.env"} に追記
API_SERVER_ENABLED=true
API_SERVER_KEY=${machine.hasApiKey ? "<コンソールに登録済みのキー>" : key}
API_SERVER_HOST=${ip}
API_SERVER_PORT=8642
HERMES_DASHBOARD_BASIC_AUTH_USERNAME=${machine.dashboardUsername || "admin"}
HERMES_DASHBOARD_BASIC_AUTH_PASSWORD=<コンソールに登録したパスワード>
HERMES_DASHBOARD_BASIC_AUTH_SECRET=${secret}`;
  const mac = `# 1) .env を上記のとおり設定
# 2) Tailscale アドレスにバインドした serve を launchd で常駐（scripts/enroll/macos.sh が同じことをします）
hermes serve --host ${ip} --port 9119 --skip-build
# 3) ゲートウェイ（API サーバーを含む）を常駐
hermes gateway install && hermes gateway start
# 4) 確認
curl http://${ip}:9119/api/status
curl -H "Authorization: Bearer $API_SERVER_KEY" http://${ip}:8642/health/detailed`;
  const win = `# 1) .env を上記のとおり設定
# 2) タスクスケジューラで「ログオン時」に実行（scripts/enroll/windows.ps1 が登録します）
hermes serve --host ${ip} --port 9119 --skip-build
# 3) ゲートウェイを常駐
hermes gateway install ; hermes gateway start
# 4) 確認
curl.exe http://${ip}:9119/api/status`;
  return (
    <Modal title={`${machine.name} の有効化手順`} onClose={onClose}>
      <div className="stack">
        <p className="muted small">各マシンで 1 回だけ実施します。キーとシークレットはこの画面を開くたびに新しく生成されます（登録済みの値がある場合はそれを使ってください）。</p>
        <pre>{env}</pre>
        <pre>{isWin ? win : mac}</pre>
        <p className="muted small">注意: Hermes Desktop が自動起動する <code>127.0.0.1:9119</code> の serve とは別プロセスになります。同じポートで衝突する場合は <code>--port 9120</code> 等に変え、このコンソールの URL も合わせてください。</p>
      </div>
    </Modal>
  );
}

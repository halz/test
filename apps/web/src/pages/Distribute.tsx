import { useState } from "react";
import { api } from "../api";
import { useFleet, useMachines } from "../hooks";
import { Badge, ErrorBox, MachinePicker, Table } from "../components/ui";

interface ConfigRow { path: string; raw: string }
interface EnvRow { key: string; value: string; remove: boolean }
interface PreviewRow {
  machineId: string; machineName: string; ok: boolean; error?: string;
  config: { path: string; current: unknown; next: unknown; changed: boolean }[];
  env: { key: string; currentSet: boolean; currentMasked: string | null; next: string | null; changed: boolean }[];
}
interface ApplyRow { machineId: string; machineName: string; ok: boolean; error?: string; applied: string[] }

const PRESETS: { label: string; config?: ConfigRow[]; env?: EnvRow[] }[] = [
  { label: "無人実行の承認を deny に", config: [{ path: "approvals.unattended_mode", raw: "deny" }, { path: "approvals.cron_mode", raw: "deny" }] },
  { label: "既定モデルを設定", config: [{ path: "model.default", raw: "anthropic/claude-sonnet-5" }] },
  { label: "API サーバー同時 run 上限", config: [{ path: "gateway.api_server.max_concurrent_runs", raw: "10" }] },
  { label: "Anthropic API キーを配布", env: [{ key: "ANTHROPIC_API_KEY", value: "", remove: false }] },
];

export function Distribute() {
  const machines = useMachines();
  const fleet = useFleet();
  const [ids, setIds] = useState<string[]>([]);
  const [config, setConfig] = useState<ConfigRow[]>([{ path: "", raw: "" }]);
  const [env, setEnv] = useState<EnvRow[]>([]);
  const [preview, setPreview] = useState<PreviewRow[] | null>(null);
  const [result, setResult] = useState<ApplyRow[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const payload = () => ({
    machineIds: ids,
    config: config.filter((r) => r.path.trim()).map((r) => ({ path: r.path.trim(), raw: r.raw })),
    env: env.filter((r) => r.key.trim()).map((r) => ({ key: r.key.trim(), value: r.remove ? null : r.value })),
  });
  const hasChanges = payload().config.length + payload().env.length > 0;
  const doPreview = async () => {
    setBusy(true); setErr(null); setResult(null);
    try { setPreview((await api<{ rows: PreviewRow[] }>("POST", "/api/distribute/preview", payload())).rows); }
    catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };
  const doApply = async () => {
    if (!confirm(`${ids.length} 台に適用します。よろしいですか？`)) return;
    setBusy(true); setErr(null);
    try { setResult((await api<{ rows: ApplyRow[] }>("POST", "/api/distribute/apply", payload())).rows); setPreview(null); }
    catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };
  const fmt = (v: unknown) => (v === undefined ? <span className="muted">(未設定)</span> : <code>{typeof v === "string" ? v : JSON.stringify(v)}</code>);

  return (
    <>
      <div className="page-bar"><h1>設定配布</h1></div>
      <div className="stack">
        <div className="card stack">
          <div><label>対象マシン</label><MachinePicker machines={machines.data ?? []} value={ids} onChange={setIds} snapshots={fleet.snapshots} /></div>
          <div className="row small"><span className="muted">プリセット:</span>{PRESETS.map((p) => <button key={p.label} className="small" onClick={() => { if (p.config) setConfig(p.config); if (p.env) setEnv(p.env); setPreview(null); }}>{p.label}</button>)}</div>
          <div>
            <label>config.yaml のキー（ドット区切り）と値（JSON として解釈できれば JSON、それ以外は文字列）</label>
            <div className="stack" style={{ gap: 6 }}>
              {config.map((r, i) => (
                <div key={i} className="row" style={{ flexWrap: "nowrap" }}>
                  <input style={{ flex: 2 }} placeholder="approvals.unattended_mode" value={r.path} onChange={(e) => setConfig(config.map((x, j) => (j === i ? { ...x, path: e.target.value } : x)))} />
                  <input style={{ flex: 3 }} placeholder="deny" value={r.raw} onChange={(e) => setConfig(config.map((x, j) => (j === i ? { ...x, raw: e.target.value } : x)))} />
                  <button className="small" onClick={() => setConfig(config.filter((_, j) => j !== i))}>×</button>
                </div>
              ))}
              <div><button className="small" onClick={() => setConfig([...config, { path: "", raw: "" }])}>＋ キー追加</button></div>
            </div>
          </div>
          <div>
            <label>.env の変数（値は暗号化されずマシンの .env に書かれます。コンソール側には保存しません）</label>
            <div className="stack" style={{ gap: 6 }}>
              {env.map((r, i) => (
                <div key={i} className="row" style={{ flexWrap: "nowrap" }}>
                  <input style={{ flex: 2 }} placeholder="ANTHROPIC_API_KEY" value={r.key} onChange={(e) => setEnv(env.map((x, j) => (j === i ? { ...x, key: e.target.value.toUpperCase() } : x)))} />
                  <input style={{ flex: 3 }} type="password" placeholder={r.remove ? "(削除)" : "値"} disabled={r.remove} value={r.value} onChange={(e) => setEnv(env.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)))} />
                  <label className="row" style={{ margin: 0, whiteSpace: "nowrap" }}><input type="checkbox" style={{ width: "auto" }} checked={r.remove} onChange={(e) => setEnv(env.map((x, j) => (j === i ? { ...x, remove: e.target.checked } : x)))} />削除</label>
                  <button className="small" onClick={() => setEnv(env.filter((_, j) => j !== i))}>×</button>
                </div>
              ))}
              <div><button className="small" onClick={() => setEnv([...env, { key: "", value: "", remove: false }])}>＋ 変数追加</button></div>
            </div>
          </div>
          <ErrorBox error={err} />
          <div className="row">
            <button onClick={doPreview} disabled={busy || !ids.length || !hasChanges}>差分プレビュー</button>
            <button className="primary" onClick={doApply} disabled={busy || !preview || !preview.some((r) => r.ok)}>適用（{ids.length} 台）</button>
          </div>
        </div>
        {preview ? (
          <div className="card stack">
            <strong>差分プレビュー</strong>
            <Table>
              <thead><tr><th>マシン</th><th>項目</th><th>現在</th><th>適用後</th><th></th></tr></thead>
              <tbody>
                {preview.flatMap((r) => {
                  if (!r.ok) return [<tr key={r.machineId}><td>{r.machineName}</td><td colSpan={4} className="error">{r.error}</td></tr>];
                  return [
                    ...r.config.map((c) => <tr key={`${r.machineId}:c:${c.path}`}><td>{r.machineName}</td><td><code>{c.path}</code></td><td>{fmt(c.current)}</td><td>{fmt(c.next)}</td><td>{c.changed ? <Badge kind="info">変更</Badge> : <Badge>同じ</Badge>}</td></tr>),
                    ...r.env.map((e) => <tr key={`${r.machineId}:e:${e.key}`}><td>{r.machineName}</td><td><code>{e.key}</code></td><td>{e.currentSet ? <code>{e.currentMasked ?? "***"}</code> : <span className="muted">(未設定)</span>}</td><td>{e.next === null ? <span className="error">削除</span> : <code>{e.next}</code>}</td><td>{e.changed ? <Badge kind="info">変更</Badge> : <Badge>同じ</Badge>}</td></tr>),
                  ];
                })}
              </tbody>
            </Table>
          </div>
        ) : null}
        {result ? (
          <div className="card stack">
            <strong>適用結果</strong>
            {result.map((r) => <div key={r.machineId} className={`alert ${r.ok ? "ok" : "err"}`}><strong>{r.machineName}</strong>: {r.ok ? `適用済み (${r.applied.join(", ")})` : r.error}</div>)}
          </div>
        ) : null}
      </div>
    </>
  );
}

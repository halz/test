import { useEffect, useMemo, useState } from "react";
import { api } from "../../api";
import { Badge, ErrorBox, Modal, useAsync, Table } from "../../components/ui";

interface ProviderRow { slug: string; name: string; is_current?: boolean; is_user_defined?: boolean; authenticated?: boolean; auth_type?: string; key_env?: string; warning?: string; models?: string[] }
interface AuxTask { task: string; provider: string; model: string; base_url?: string; reasoning_effort?: string | null }
interface AuxPayload { tasks: AuxTask[]; main: { provider: string; model: string } }
interface CustomEndpoint { id: string; name: string; base_url: string; model?: string; models?: string[] }
interface Fallback { provider: string; model: string; base_url?: string; key_env?: string }
interface ModelRoute { alias: string; model: string; provider?: string; base_url?: string }

const AUX_LABELS: Record<string, string> = { vision: "画像認識", compression: "コンテキスト圧縮", approval: "承認判定", web_extraction: "Web 抽出", title_generation: "タイトル生成", skills_hub_search: "スキル検索", mcp_routing: "MCP ルーティング", triage_specification: "タスク仕様化", task_decomposition: "タスク分解", profile_description: "プロファイル説明", curator_review: "キュレーター" };

export function ModelsTab({ id }: { id: string }) {
  const [profile, setProfile] = useState("");
  const q = profile ? `?profile=${encodeURIComponent(profile)}` : "";
  const hermes = <T,>(method: string, path: string, body?: unknown) => api<T>(method, `/api/machines/${id}/hermes${path}`, body);
  const profiles = useAsync(() => hermes<{ profiles: { name: string; is_default: boolean }[] }>("GET", "/api/profiles"), [id]);
  const options = useAsync(() => hermes<{ providers: ProviderRow[] }>("GET", `/api/model/options${q ? q + "&" : "?"}include_unconfigured=true`), [id, profile]);
  const auxq = useAsync(() => hermes<AuxPayload>("GET", `/api/model/auxiliary${q}`), [id, profile]);
  const cfg = useAsync(() => hermes<Record<string, unknown>>("GET", `/api/config${q}`), [id, profile]);
  const endpoints = useAsync(() => hermes<{ endpoints?: CustomEndpoint[]; current?: string } | CustomEndpoint[]>("GET", `/api/providers/custom-endpoints${q}`), [id, profile]);
  const reloadAll = () => { options.reload(); auxq.reload(); cfg.reload(); endpoints.reload(); };
  const providers = options.data?.providers ?? [];

  return (
    <div className="stack">
      <div className="row">
        <label style={{ margin: 0 }}>対象プロファイル</label>
        <select style={{ width: 200 }} value={profile} onChange={(e) => setProfile(e.target.value)}>
          <option value="">(ダッシュボードの既定)</option>
          {(profiles.data?.profiles ?? []).map((p) => <option key={p.name} value={p.name}>{p.name}{p.is_default ? " (default)" : ""}</option>)}
        </select>
        <button onClick={reloadAll}>再読込</button>
        <span className="muted small">変更は新しいセッションから有効になります。</span>
      </div>
      <ErrorBox error={options.error ?? auxq.error ?? cfg.error ?? endpoints.error} />
      <MainModel hermes={hermes} q={q} providers={providers} main={auxq.data?.main} onChanged={reloadAll} />
      <Auxiliary hermes={hermes} q={q} providers={providers} tasks={auxq.data?.tasks ?? []} onChanged={auxq.reload} />
      <ProviderKeys hermes={hermes} q={q} providers={providers} onChanged={reloadAll} />
      <CustomEndpoints hermes={hermes} q={q} list={Array.isArray(endpoints.data) ? endpoints.data : endpoints.data?.endpoints ?? []} onChanged={reloadAll} />
      <Routing hermes={hermes} q={q} providers={providers} config={cfg.data ?? {}} onChanged={cfg.reload} />
    </div>
  );
}

type Hermes = <T>(method: string, path: string, body?: unknown) => Promise<T>;

function ModelPicker({ providers, provider, model, onChange, allowAuto }: { providers: ProviderRow[]; provider: string; model: string; onChange: (p: string, m: string) => void; allowAuto?: boolean }) {
  const row = providers.find((p) => p.slug === provider);
  const models = row?.models ?? [];
  return (
    <div className="row" style={{ flexWrap: "nowrap" }}>
      <select style={{ width: 180 }} value={provider} onChange={(e) => onChange(e.target.value, "")}>
        {allowAuto ? <option value="auto">auto（メインと同じ）</option> : null}
        {providers.map((p) => <option key={p.slug} value={p.slug} disabled={p.authenticated === false}>{p.name}{p.authenticated === false ? "（未認証）" : ""}</option>)}
      </select>
      <input list={`models-${provider}`} style={{ flex: 1 }} value={model} placeholder="モデル ID" onChange={(e) => onChange(provider, e.target.value)} disabled={provider === "auto"} />
      <datalist id={`models-${provider}`}>{models.map((m) => <option key={m} value={m} />)}</datalist>
    </div>
  );
}

function MainModel({ hermes, q, providers, main, onChanged }: { hermes: Hermes; q: string; providers: ProviderRow[]; main?: { provider: string; model: string }; onChanged: () => void }) {
  const [provider, setProvider] = useState(""); const [model, setModel] = useState("");
  const [confirm, setConfirm] = useState<string | null>(null); const [err, setErr] = useState<string | null>(null); const [msg, setMsg] = useState<string | null>(null);
  useEffect(() => { if (main) { setProvider(main.provider); setModel(main.model); } }, [main?.provider, main?.model]);
  const apply = async (confirmExpensive = false) => {
    setErr(null); setMsg(null);
    try {
      const r = await hermes<{ ok: boolean; confirm_required?: boolean; confirm_message?: string }>("POST", `/api/model/set${q}`, { scope: "main", provider, model, confirm_expensive_model: confirmExpensive });
      if (r.ok === false && r.confirm_required) return setConfirm(r.confirm_message ?? "続行しますか？");
      setMsg(`メインモデルを ${provider} / ${model} に設定しました`); onChanged();
    } catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
  };
  return (
    <div className="card stack">
      <div className="row"><strong>メインモデル</strong>{main ? <Badge>{main.provider} / {main.model}</Badge> : null}</div>
      <ModelPicker providers={providers} provider={provider} model={model} onChange={(p, m) => { setProvider(p); setModel(m); }} />
      <ErrorBox error={err} />{msg ? <div className="alert ok">{msg}</div> : null}
      <div className="row"><button className="primary" disabled={!provider || !model} onClick={() => apply(false)}>適用</button></div>
      {confirm ? <Modal title="確認" onClose={() => setConfirm(null)} footer={<><button onClick={() => setConfirm(null)}>キャンセル</button><button className="primary" onClick={() => { setConfirm(null); apply(true); }}>続行</button></>}><p>{confirm}</p></Modal> : null}
    </div>
  );
}

function Auxiliary({ hermes, q, providers, tasks, onChanged }: { hermes: Hermes; q: string; providers: ProviderRow[]; tasks: AuxTask[]; onChanged: () => void }) {
  const [edit, setEdit] = useState<AuxTask | null>(null); const [err, setErr] = useState<string | null>(null);
  const save = async () => {
    if (!edit) return; setErr(null);
    try { await hermes("POST", `/api/model/set${q}`, { scope: "auxiliary", task: edit.task, provider: edit.provider, model: edit.provider === "auto" ? "" : edit.model }); setEdit(null); onChanged(); }
    catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
  };
  const reset = async () => { if (!confirm("補助タスクをすべて auto に戻しますか？")) return; await hermes("POST", `/api/model/set${q}`, { scope: "auxiliary", task: "__reset__", provider: "", model: "" }); onChanged(); };
  return (
    <div className="card stack">
      <div className="row"><strong>補助タスクのモデル</strong><span className="muted small">auto はメインモデルを使用</span><span className="spacer" /><button className="small" onClick={reset}>すべて auto に戻す</button></div>
      <Table><thead><tr><th>タスク</th><th>プロバイダ</th><th>モデル</th><th></th></tr></thead><tbody>
        {tasks.map((t) => <tr key={t.task}><td>{AUX_LABELS[t.task] ?? t.task} <span className="muted small">{t.task}</span></td><td>{t.provider}</td><td className="mono">{t.model || "-"}</td><td><button className="small" onClick={() => setEdit({ ...t })}>変更</button></td></tr>)}
      </tbody></Table>
      {edit ? <Modal title={`${AUX_LABELS[edit.task] ?? edit.task} のモデル`} onClose={() => setEdit(null)} footer={<><button onClick={() => setEdit(null)}>キャンセル</button><button className="primary" onClick={save}>保存</button></>}>
        <ModelPicker allowAuto providers={providers} provider={edit.provider} model={edit.model} onChange={(p, m) => setEdit({ ...edit, provider: p, model: m })} /><ErrorBox error={err} />
      </Modal> : null}
    </div>
  );
}

function ProviderKeys({ hermes, q, providers, onChanged }: { hermes: Hermes; q: string; providers: ProviderRow[]; onChanged: () => void }) {
  const [edit, setEdit] = useState<ProviderRow | null>(null); const [value, setValue] = useState(""); const [err, setErr] = useState<string | null>(null); const [busy, setBusy] = useState(false);
  const save = async () => {
    if (!edit?.key_env) return; setBusy(true); setErr(null);
    try {
      const v = await hermes<{ ok: boolean; reachable: boolean; message: string }>("POST", "/api/providers/validate", { key: edit.key_env, value });
      if (!v.ok && v.reachable) throw new Error(v.message || "キーが拒否されました");
      await hermes("PUT", `/api/env${q}`, { key: edit.key_env, value });
      setEdit(null); setValue(""); onChanged();
    } catch (e) { setErr(e instanceof Error ? e.message : String(e)); } finally { setBusy(false); }
  };
  return (
    <div className="card stack">
      <strong>LLM プロバイダの認証</strong>
      <Table><thead><tr><th>プロバイダ</th><th>状態</th><th>キー変数</th><th>モデル数</th><th></th></tr></thead><tbody>
        {providers.filter((p) => !p.is_user_defined).map((p) => <tr key={p.slug}><td>{p.name}{p.is_current ? <Badge kind="info">使用中</Badge> : null}</td><td>{p.authenticated === false ? <Badge kind="warn">未設定</Badge> : <Badge kind="ok">認証済み</Badge>}</td><td className="mono">{p.key_env || (p.auth_type ?? "-")}</td><td>{p.models?.length ?? 0}</td><td>{p.key_env ? <button className="small" onClick={() => { setEdit(p); setValue(""); setErr(null); }}>{p.authenticated === false ? "キーを設定" : "キーを更新"}</button> : <span className="muted small">hermes model で設定</span>}</td></tr>)}
      </tbody></Table>
      {edit ? <Modal title={`${edit.name} の API キー`} onClose={() => setEdit(null)} footer={<><button onClick={() => setEdit(null)}>キャンセル</button><button className="primary" disabled={busy || !value} onClick={save}>{busy ? "検証中…" : "検証して保存"}</button></>}>
        <div className="stack"><div><label>{edit.key_env}</label><input type="password" value={value} onChange={(e) => setValue(e.target.value)} autoComplete="new-password" /></div><p className="muted small">保存前にプロバイダへ問い合わせて有効性を確認します。値はこのマシンの .env に書かれ、コンソールには保存しません。</p><ErrorBox error={err} /></div>
      </Modal> : null}
    </div>
  );
}

function CustomEndpoints({ hermes, q, list, onChanged }: { hermes: Hermes; q: string; list: CustomEndpoint[]; onChanged: () => void }) {
  const [form, setForm] = useState<{ name: string; base_url: string; api_key: string; model: string; make_default: boolean } | null>(null);
  const [probe, setProbe] = useState<string | null>(null); const [err, setErr] = useState<string | null>(null);
  const validate = async () => {
    if (!form) return; setErr(null); setProbe(null);
    try { const r = await hermes<{ ok: boolean; reachable: boolean; message: string; models: string[] }>("POST", "/api/providers/custom-endpoints/validate", form); if (!r.ok) throw new Error(r.message || "到達できません"); setProbe(`到達 OK: ${r.models.slice(0, 8).join(", ")}${r.models.length > 8 ? " …" : ""}`); if (!form.model && r.models[0]) setForm({ ...form, model: r.models[0] }); }
    catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
  };
  const save = async () => { if (!form) return; setErr(null); try { await hermes("POST", `/api/providers/custom-endpoints${q}`, form); setForm(null); onChanged(); } catch (e) { setErr(e instanceof Error ? e.message : String(e)); } };
  const remove = async (e: CustomEndpoint) => { if (!confirm(`${e.name} を削除しますか？`)) return; await hermes("DELETE", `/api/providers/custom-endpoints/${encodeURIComponent(e.id)}${q}`); onChanged(); };
  return (
    <div className="card stack">
      <div className="row"><strong>カスタムエンドポイント（OpenAI 互換 / ローカル LLM）</strong><span className="spacer" /><button className="small" onClick={() => { setForm({ name: "", base_url: "", api_key: "", model: "", make_default: false }); setProbe(null); setErr(null); }}>＋ 追加</button></div>
      <Table><thead><tr><th>名前</th><th>URL</th><th>モデル</th><th></th></tr></thead><tbody>
        {list.map((e) => <tr key={e.id}><td>{e.name} <span className="muted small">{e.id}</span></td><td className="mono">{e.base_url}</td><td className="mono">{e.model || (e.models ?? []).join(", ")}</td><td><button className="small danger" onClick={() => remove(e)}>削除</button></td></tr>)}
        {list.length === 0 ? <tr><td colSpan={4} className="muted">なし</td></tr> : null}
      </tbody></Table>
      {form ? <Modal title="カスタムエンドポイントを追加" onClose={() => setForm(null)} footer={<><button onClick={validate}>接続確認</button><button className="primary" disabled={!form.name || !form.base_url} onClick={save}>保存</button></>}>
        <div className="form">
          <div><label>名前</label><input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="ollama-mac-3" /></div>
          <div><label>Base URL</label><input value={form.base_url} onChange={(e) => setForm({ ...form, base_url: e.target.value })} placeholder="http://100.x.y.z:11434/v1" /></div>
          <div><label>API キー（任意）</label><input type="password" value={form.api_key} onChange={(e) => setForm({ ...form, api_key: e.target.value })} /></div>
          <div><label>既定モデル</label><input value={form.model} onChange={(e) => setForm({ ...form, model: e.target.value })} /></div>
          <label className="row full" style={{ margin: 0 }}><input type="checkbox" style={{ width: "auto" }} checked={form.make_default} onChange={(e) => setForm({ ...form, make_default: e.target.checked })} /> メインモデルとして使う</label>
        </div>
        {probe ? <div className="alert ok" style={{ marginTop: 8 }}>{probe}</div> : null}<ErrorBox error={err} />
      </Modal> : null}
    </div>
  );
}

function Routing({ hermes, q, providers, config, onChanged }: { hermes: Hermes; q: string; providers: ProviderRow[]; config: Record<string, unknown>; onChanged: () => void }) {
  const get = (path: string): unknown => path.split(".").reduce<unknown>((o, k) => (o && typeof o === "object" ? (o as Record<string, unknown>)[k] : undefined), config);
  const initialFallbacks = useMemo(() => (Array.isArray(get("fallback_providers")) ? (get("fallback_providers") as Fallback[]) : []), [config]);
  const pr = (get("provider_routing") as Record<string, unknown> | undefined) ?? {};
  const apiServerPath = get("gateway.platforms.api_server") ? "gateway.platforms.api_server" : "gateway.api_server";
  const initialRoutes = useMemo(() => { const r = get(`${apiServerPath}.model_routes`) as Record<string, ModelRoute> | undefined; return r && typeof r === "object" ? Object.entries(r).map(([alias, v]) => ({ alias, model: v.model ?? "", provider: v.provider ?? "", base_url: v.base_url ?? "" })) : []; }, [config]);
  const [fallbacks, setFallbacks] = useState<Fallback[]>(initialFallbacks);
  const [routing, setRouting] = useState({ sort: String(pr.sort ?? ""), only: (Array.isArray(pr.only) ? (pr.only as string[]) : []).join(", "), ignore: (Array.isArray(pr.ignore) ? (pr.ignore as string[]) : []).join(", "), order: (Array.isArray(pr.order) ? (pr.order as string[]) : []).join(", ") });
  const [routes, setRoutes] = useState<ModelRoute[]>(initialRoutes);
  const [err, setErr] = useState<string | null>(null); const [msg, setMsg] = useState<string | null>(null);
  useEffect(() => { setFallbacks(initialFallbacks); setRoutes(initialRoutes); setRouting({ sort: String(pr.sort ?? ""), only: (Array.isArray(pr.only) ? (pr.only as string[]) : []).join(", "), ignore: (Array.isArray(pr.ignore) ? (pr.ignore as string[]) : []).join(", "), order: (Array.isArray(pr.order) ? (pr.order as string[]) : []).join(", ") }); }, [config]);
  const csv = (s: string) => s.split(",").map((x) => x.trim()).filter(Boolean);
  const save = async () => {
    setErr(null); setMsg(null);
    const partial: Record<string, unknown> = {
      fallback_providers: fallbacks.filter((f) => f.provider && f.model).map((f) => ({ provider: f.provider, model: f.model, ...(f.base_url ? { base_url: f.base_url } : {}), ...(f.key_env ? { key_env: f.key_env } : {}) })),
      provider_routing: { ...(routing.sort ? { sort: routing.sort } : { sort: null }), only: csv(routing.only), ignore: csv(routing.ignore), order: csv(routing.order) },
    };
    const mr: Record<string, ModelRoute> = {};
    for (const r of routes) if (r.alias.trim() && r.model.trim()) mr[r.alias.trim()] = { model: r.model.trim(), ...(r.provider ? { provider: r.provider } : {}), ...(r.base_url ? { base_url: r.base_url } : {}) } as ModelRoute;
    const [g, ...rest] = apiServerPath.split(".");
    let node: Record<string, unknown> = partial; node[g] = {}; node = node[g] as Record<string, unknown>;
    for (let i = 0; i < rest.length; i++) { node[rest[i]] = {}; node = node[rest[i]] as Record<string, unknown>; }
    node.model_routes = mr;
    try { await hermes("PUT", `/api/config${q}`, { config: partial }); setMsg("ルーティング設定を保存しました（ゲートウェイ再起動後に有効）"); onChanged(); }
    catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
  };
  const slugs = providers.map((p) => p.slug);
  return (
    <div className="card stack">
      <strong>ルーティング</strong>
      <div>
        <label>フォールバック（メインが失敗したとき順に試す）</label>
        <div className="stack" style={{ gap: 6 }}>
          {fallbacks.map((f, i) => <div key={i} className="row" style={{ flexWrap: "nowrap" }}>
            <select style={{ width: 160 }} value={f.provider} onChange={(e) => setFallbacks(fallbacks.map((x, j) => (j === i ? { ...x, provider: e.target.value } : x)))}><option value="">プロバイダ</option>{slugs.map((s) => <option key={s} value={s}>{s}</option>)}<option value="custom">custom</option></select>
            <input style={{ flex: 1 }} placeholder="モデル" value={f.model} onChange={(e) => setFallbacks(fallbacks.map((x, j) => (j === i ? { ...x, model: e.target.value } : x)))} />
            {f.provider === "custom" ? <input style={{ flex: 1 }} placeholder="base_url" value={f.base_url ?? ""} onChange={(e) => setFallbacks(fallbacks.map((x, j) => (j === i ? { ...x, base_url: e.target.value } : x)))} /> : null}
            <button className="small" onClick={() => setFallbacks(fallbacks.filter((_, j) => j !== i))}>×</button>
          </div>)}
          <div><button className="small" onClick={() => setFallbacks([...fallbacks, { provider: "", model: "" }])}>＋ 追加</button></div>
        </div>
      </div>
      <div className="form">
        <div><label>provider_routing.sort（OpenRouter 等の集約プロバイダ向け）</label><select value={routing.sort} onChange={(e) => setRouting({ ...routing, sort: e.target.value })}><option value="">既定</option><option value="price">price（安い順）</option><option value="throughput">throughput（速い順）</option><option value="latency">latency（低遅延順）</option></select></div>
        <div><label>order（優先順、カンマ区切り）</label><input value={routing.order} onChange={(e) => setRouting({ ...routing, order: e.target.value })} placeholder="anthropic, together" /></div>
        <div><label>only（許可するプロバイダ）</label><input value={routing.only} onChange={(e) => setRouting({ ...routing, only: e.target.value })} /></div>
        <div><label>ignore（除外するプロバイダ）</label><input value={routing.ignore} onChange={(e) => setRouting({ ...routing, ignore: e.target.value })} /></div>
      </div>
      <div>
        <label>API サーバーのモデル別名（<code>{apiServerPath}.model_routes</code>: コンソールのプロンプト送信で「モデル」欄に指定できる名前）</label>
        <div className="stack" style={{ gap: 6 }}>
          {routes.map((r, i) => <div key={i} className="row" style={{ flexWrap: "nowrap" }}>
            <input style={{ width: 140 }} placeholder="別名 (fast)" value={r.alias} onChange={(e) => setRoutes(routes.map((x, j) => (j === i ? { ...x, alias: e.target.value } : x)))} />
            <select style={{ width: 150 }} value={r.provider ?? ""} onChange={(e) => setRoutes(routes.map((x, j) => (j === i ? { ...x, provider: e.target.value } : x)))}><option value="">既定プロバイダ</option>{slugs.map((s) => <option key={s} value={s}>{s}</option>)}</select>
            <input style={{ flex: 1 }} placeholder="モデル" value={r.model} onChange={(e) => setRoutes(routes.map((x, j) => (j === i ? { ...x, model: e.target.value } : x)))} />
            <button className="small" onClick={() => setRoutes(routes.filter((_, j) => j !== i))}>×</button>
          </div>)}
          <div><button className="small" onClick={() => setRoutes([...routes, { alias: "", model: "", provider: "" }])}>＋ 追加</button></div>
        </div>
      </div>
      <ErrorBox error={err} />{msg ? <div className="alert ok">{msg}</div> : null}
      <div className="row"><button className="primary" onClick={save}>ルーティングを保存</button></div>
    </div>
  );
}

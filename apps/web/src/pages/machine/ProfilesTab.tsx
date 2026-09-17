import { useState } from "react";
import { api } from "../../api";
import { Badge, ErrorBox, Modal, useAsync, Table } from "../../components/ui";

interface Profile { name: string; path: string; is_default: boolean; model: string | null; provider: string | null; has_env: boolean; skill_count: number; gateway_running: boolean; description: string; display_name: string }
interface ProviderRow { slug: string; name: string; authenticated?: boolean; models?: string[] }
interface ProfileKey { profile: string; apiUrl: string; hasKey: boolean; updatedAt: number }

export function ProfilesTab({ id }: { id: string }) {
  const hermes = <T,>(method: string, path: string, body?: unknown) => api<T>(method, `/api/machines/${id}/hermes${path}`, body);
  const list = useAsync(() => hermes<{ profiles: Profile[] }>("GET", "/api/profiles"), [id]);
  const active = useAsync(() => hermes<{ active: string; current: string }>("GET", "/api/profiles/active"), [id]);
  const options = useAsync(() => hermes<{ providers: ProviderRow[] }>("GET", "/api/model/options"), [id]);
  const providers = (options.data?.providers ?? []).filter((p) => p.authenticated !== false);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState({ name: "", clone_from: "", description: "", provider: "", model: "" });
  const [modelEdit, setModelEdit] = useState<Profile | null>(null); const [mp, setMp] = useState({ provider: "", model: "" });
  const [soulEdit, setSoulEdit] = useState<Profile | null>(null); const [soul, setSoul] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const keys = useAsync(() => api<{ keys: ProfileKey[] }>("GET", `/api/machines/${id}/profiles/keys`), [id]);
  const keyOf = (name: string) => keys.data?.keys.find((k) => k.profile === name);
  const [keyEdit, setKeyEdit] = useState<Profile | null>(null); const [kf, setKf] = useState({ apiKey: "", apiUrl: "" });
  const reload = () => { list.reload(); active.reload(); keys.reload(); };
  const run = async (fn: () => Promise<unknown>) => { setErr(null); try { await fn(); reload(); return true; } catch (e) { setErr(e instanceof Error ? e.message : String(e)); return false; } };

  const create = () => run(async () => { await hermes("POST", "/api/profiles", { name: form.name.trim(), ...(form.clone_from ? { clone_from: form.clone_from } : {}), description: form.description, ...(form.provider && form.model ? { provider: form.provider, model: form.model } : {}) }); setCreating(false); setForm({ name: "", clone_from: "", description: "", provider: "", model: "" }); });
  const remove = (p: Profile) => { if (!confirm(`プロファイル ${p.name} を削除しますか？ セッション・メモリ・設定がすべて消えます。`)) return; run(() => hermes("DELETE", `/api/profiles/${encodeURIComponent(p.name)}`)); };
  const rename = (p: Profile) => { const n = prompt("新しい名前", p.name); if (!n || n === p.name) return; run(() => hermes("PATCH", `/api/profiles/${encodeURIComponent(p.name)}`, { new_name: n })); };
  const setActive = (p: Profile) => run(() => hermes("POST", "/api/profiles/active", { name: p.name }));
  const saveModel = async () => { if (!modelEdit) return; if (await run(() => hermes("PUT", `/api/profiles/${encodeURIComponent(modelEdit.name)}/model`, mp))) setModelEdit(null); };
  const openSoul = async (p: Profile) => { setErr(null); try { const r = await hermes<{ content: string }>("GET", `/api/profiles/${encodeURIComponent(p.name)}/soul`); setSoul(r.content ?? ""); setSoulEdit(p); } catch (e) { setErr(e instanceof Error ? e.message : String(e)); } };
  const saveSoul = async () => { if (!soulEdit) return; if (await run(() => hermes("PUT", `/api/profiles/${encodeURIComponent(soulEdit.name)}/soul`, { content: soul }))) setSoulEdit(null); };
  // Per-profile API access: Hermes serves named profiles at <apiUrl>/p/<name> with that profile's own API_SERVER_KEY.
  const pushKey = (p: Profile) => { if (!confirm(`プロファイル ${p.name} の .env に新しい API_SERVER_KEY を書き込み、コンソールに保存しますか？`)) return; setMsg(null); run(async () => { await api("PUT", `/api/machines/${id}/profiles/${encodeURIComponent(p.name)}/key`, { push: true }); setMsg(`${p.name} に API キーを配布しました。反映されない場合はゲートウェイを再起動してください。`); }); };
  const saveKey = async () => { if (!keyEdit) return; setMsg(null); if (await run(() => api("PUT", `/api/machines/${id}/profiles/${encodeURIComponent(keyEdit.name)}/key`, { ...(kf.apiKey ? { apiKey: kf.apiKey } : {}), apiUrl: kf.apiUrl }))) setKeyEdit(null); };
  const testKey = async (p: Profile) => { setErr(null); setMsg(null); try { const r = await api<{ ok: boolean; baseUrl?: string; version?: string; error?: string }>("POST", `/api/machines/${id}/profiles/${encodeURIComponent(p.name)}/test`); if (r.ok) setMsg(`${p.name}: 接続 OK（${r.baseUrl}${r.version ? `, Hermes ${r.version}` : ""}）`); else setErr(`${p.name}: 接続できません — ${r.error ?? ""}${r.baseUrl ? `（${r.baseUrl}）` : ""}`); } catch (e) { setErr(e instanceof Error ? e.message : String(e)); } };
  const removeKey = (p: Profile) => { if (!confirm(`${p.name} の API キーをコンソールから削除しますか？（マシン側の .env は変更しません）`)) return; run(() => api("DELETE", `/api/machines/${id}/profiles/${encodeURIComponent(p.name)}/key`)); };
  const setDescription = (p: Profile) => { const d = prompt("説明（kanban / Bot Mode で役割として使われます）", p.description); if (d === null) return; run(() => hermes("PUT", `/api/profiles/${encodeURIComponent(p.name)}/description`, { description: d })); };

  return (
    <div className="stack">
      <div className="row"><button className="primary" onClick={() => setCreating(true)}>＋ プロファイル作成</button><button onClick={reload}>更新</button><span className="muted small">アクティブ: <code>{active.data?.active ?? "-"}</code>（CLI とゲートウェイの既定）</span></div>
      <ErrorBox error={err ?? list.error} />
      {msg ? <div className="alert ok">{msg}</div> : null}
      <p className="muted small" style={{ margin: 0 }}>API キー: プロンプト送信でプロファイルを指定するには、そのプロファイル自身の <code>API_SERVER_KEY</code> が必要です（マシン側は <code>gateway.multiplex_profiles: true</code> で <code>/p/&lt;profile&gt;/</code> を提供）。「キーを配布」でキーを生成してプロファイルの .env に書き込みます。</p>
      <div className="card" style={{ padding: 0, overflow: "auto" }}>
        <Table><thead><tr><th>名前</th><th>モデル</th><th>ゲートウェイ</th><th>API キー</th><th>スキル</th><th>説明</th><th></th></tr></thead><tbody>
          {(list.data?.profiles ?? []).map((p) => (
            <tr key={p.name}>
              <td><strong>{p.display_name || p.name}</strong> {p.is_default ? <Badge>default</Badge> : null}{active.data?.active === p.name ? <Badge kind="info">active</Badge> : null}<div className="muted small mono">{p.path}</div></td>
              <td className="mono">{p.provider ? `${p.provider} / ${p.model ?? ""}` : <span className="muted">未設定</span>}</td>
              <td>{p.gateway_running ? <Badge kind="ok">running</Badge> : <Badge>stopped</Badge>}</td>
              <td>{p.is_default ? <span className="muted small">マシンのキー</span> : keyOf(p.name)?.hasKey ? <div className="row" style={{ gap: 4 }}><Badge kind="ok">設定済み</Badge>{keyOf(p.name)?.apiUrl ? <span className="muted small mono">{keyOf(p.name)?.apiUrl}</span> : null}<button className="small" onClick={() => testKey(p)}>テスト</button></div> : <div className="row" style={{ gap: 4 }}><Badge kind="warn">未設定</Badge><button className="small" onClick={() => pushKey(p)}>キーを配布</button></div>}</td>
              <td>{p.skill_count}</td>
              <td className="muted small" style={{ maxWidth: 240 }}>{p.description || "-"}</td>
              <td><div className="row" style={{ justifyContent: "flex-end" }}>
                <button className="small" onClick={() => { setModelEdit(p); setMp({ provider: p.provider ?? "", model: p.model ?? "" }); }}>モデル</button>
                <button className="small" onClick={() => openSoul(p)}>SOUL</button>
                <button className="small" onClick={() => setDescription(p)}>説明</button>
                {!p.is_default ? <button className="small" onClick={() => { setKeyEdit(p); setKf({ apiKey: "", apiUrl: keyOf(p.name)?.apiUrl ?? "" }); }}>API キー</button> : null}
                {!p.is_default && keyOf(p.name)?.hasKey ? <button className="small" onClick={() => removeKey(p)}>キー削除</button> : null}
                {active.data?.active !== p.name ? <button className="small" onClick={() => setActive(p)}>アクティブに</button> : null}
                {!p.is_default ? <><button className="small" onClick={() => rename(p)}>名前変更</button><button className="small danger" onClick={() => remove(p)}>削除</button></> : null}
              </div></td>
            </tr>
          ))}
        </tbody></Table>
      </div>
      {creating ? <Modal title="プロファイルを作成" onClose={() => setCreating(false)} footer={<><button onClick={() => setCreating(false)}>キャンセル</button><button className="primary" disabled={!/^[a-z][a-z0-9_-]*$/.test(form.name)} onClick={create}>作成</button></>}>
        <div className="form">
          <div><label>名前（小文字英数字、- と _）</label><input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value.toLowerCase() })} placeholder="coder" /></div>
          <div><label>複製元（設定・SOUL・スキルをコピー）</label><select value={form.clone_from} onChange={(e) => setForm({ ...form, clone_from: e.target.value })}><option value="">新規（同梱スキルのみ）</option>{(list.data?.profiles ?? []).map((p) => <option key={p.name} value={p.name}>{p.name}</option>)}</select></div>
          <div className="full"><label>説明（任意）</label><input value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} placeholder="コードを読み、テストを書くエージェント" /></div>
          <div><label>プロバイダ（任意）</label><select value={form.provider} onChange={(e) => setForm({ ...form, provider: e.target.value, model: "" })}><option value="">複製元 / 既定のまま</option>{providers.map((p) => <option key={p.slug} value={p.slug}>{p.name}</option>)}</select></div>
          <div><label>モデル</label><input list="create-models" value={form.model} onChange={(e) => setForm({ ...form, model: e.target.value })} disabled={!form.provider} /><datalist id="create-models">{(providers.find((p) => p.slug === form.provider)?.models ?? []).map((m) => <option key={m} value={m} />)}</datalist></div>
        </div>
      </Modal> : null}
      {modelEdit ? <Modal title={`${modelEdit.name} のメインモデル`} onClose={() => setModelEdit(null)} footer={<><button onClick={() => setModelEdit(null)}>キャンセル</button><button className="primary" disabled={!mp.provider || !mp.model} onClick={saveModel}>保存</button></>}>
        <div className="form">
          <div><label>プロバイダ</label><select value={mp.provider} onChange={(e) => setMp({ provider: e.target.value, model: "" })}><option value="">選択</option>{providers.map((p) => <option key={p.slug} value={p.slug}>{p.name}</option>)}</select></div>
          <div><label>モデル</label><input list="edit-models" value={mp.model} onChange={(e) => setMp({ ...mp, model: e.target.value })} /><datalist id="edit-models">{(providers.find((p) => p.slug === mp.provider)?.models ?? []).map((m) => <option key={m} value={m} />)}</datalist></div>
        </div>
      </Modal> : null}
      {keyEdit ? <Modal title={`${keyEdit.name} の API アクセス`} onClose={() => setKeyEdit(null)} footer={<><button onClick={() => setKeyEdit(null)}>キャンセル</button><button className="primary" onClick={saveKey}>保存</button></>}>
        <div className="stack">
          <div><label>API_SERVER_KEY（プロファイル自身のキー。空なら保存済みのまま）</label><input type="password" value={kf.apiKey} onChange={(e) => setKf({ ...kf, apiKey: e.target.value })} placeholder={keyOf(keyEdit.name)?.hasKey ? "（設定済み）" : "profile の .env の API_SERVER_KEY"} autoComplete="off" /></div>
          <div><label>API URL（任意。プロファイルが独自のゲートウェイ / ポートで動く場合。空なら {"<マシンの API URL>/p/"}{keyEdit.name}）</label><input value={kf.apiUrl} onChange={(e) => setKf({ ...kf, apiUrl: e.target.value })} placeholder="http://100.x.y.z:8643" /></div>
          <p className="muted small">キーを手入力する代わりに「キーを配布」を使うと、コンソールがキーを生成してプロファイルの .env に書き込みます。</p>
        </div>
      </Modal> : null}
      {soulEdit ? <Modal title={`${soulEdit.name} の SOUL.md`} onClose={() => setSoulEdit(null)} footer={<><button onClick={() => setSoulEdit(null)}>キャンセル</button><button className="primary" onClick={saveSoul}>保存</button></>}>
        <textarea style={{ minHeight: 260, fontFamily: "ui-monospace, monospace" }} value={soul} onChange={(e) => setSoul(e.target.value)} />
      </Modal> : null}
    </div>
  );
}

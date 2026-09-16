import { useState } from "react";
import { api, getServerUrl, setServerUrl, setToken, isNativeApp, normalizeServerUrl } from "../api";
import { ErrorBox } from "../components/ui";

export function Settings({ firstRun }: { firstRun?: boolean }) {
  const [url, setUrl] = useState(getServerUrl());
  const [cur, setCur] = useState("");
  const [next, setNext] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [urlErr, setUrlErr] = useState<string | null>(null);
  const [testing, setTesting] = useState(false);

  // Probe the console before saving so a wrong URL (missing http://, wrong port, not on Tailscale)
  // is reported here instead of leaving the app stuck on a login screen that talks to nothing.
  const saveUrl = async (e: React.FormEvent) => {
    e.preventDefault();
    setUrlErr(null);
    const target = normalizeServerUrl(url);
    if (!target) return setUrlErr("URL を入力してください");
    setTesting(true);
    try {
      const res = await fetch(`${target}/api/health`, { headers: { accept: "application/json" } });
      const text = await res.text();
      let ok = false;
      try {
        ok = res.ok && JSON.parse(text)?.status === "ok";
      } catch {
        ok = false;
      }
      if (!ok) return setUrlErr(`${target} は Fleet Console ではないようです（HTTP ${res.status}）。ポート 8080 と http:// を確認してください。`);
      setServerUrl(target);
      location.reload();
    } catch (e) {
      setUrlErr(`${target} に接続できません: ${e instanceof Error ? e.message : String(e)}。Tailscale に接続しているか、Mac mini でコンソールが起動しているか確認してください。`);
    } finally {
      setTesting(false);
    }
  };
  const changePw = async (e: React.FormEvent) => {
    e.preventDefault();
    setErr(null);
    setMsg(null);
    try {
      await api("POST", "/api/auth/password", { current: cur, next });
      setMsg("パスワードを変更しました。再ログインしてください。");
      setToken(null);
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };
  const logout = async () => {
    try {
      await api("POST", "/api/auth/logout");
    } finally {
      setToken(null);
    }
  };

  return (
    <div className={firstRun ? "login" : ""}>
      <div className="topbar"><h1>{firstRun ? "サーバー設定" : "設定"}</h1></div>
      <div className="stack">
        {(isNativeApp() || firstRun || getServerUrl()) && (
          <form className="card stack" onSubmit={saveUrl}>
            <strong>コンソールサーバーの URL</strong>
            <p className="muted small">Mac mini 上の Fleet Console の URL（例: <code>http://macmini.tailnet-name.ts.net:8080</code>）。Tailscale に接続した状態で開いてください。</p>
            <input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="http://100.x.y.z:8080" inputMode="url" autoCapitalize="none" autoCorrect="off" />
            <ErrorBox error={urlErr} />
            <div className="row"><button className="primary" disabled={testing}>{testing ? "接続を確認中…" : "接続を確認して保存"}</button></div>
          </form>
        )}
        {!firstRun ? (
          <>
            <form className="card stack" onSubmit={changePw}>
              <strong>管理者パスワードの変更</strong>
              <div className="form">
                <div><label>現在のパスワード</label><input type="password" value={cur} onChange={(e) => setCur(e.target.value)} /></div>
                <div><label>新しいパスワード（8 文字以上）</label><input type="password" value={next} onChange={(e) => setNext(e.target.value)} /></div>
              </div>
              <ErrorBox error={err} />
              {msg ? <div className="alert ok">{msg}</div> : null}
              <div className="row"><button className="primary" disabled={!cur || next.length < 8}>変更</button></div>
            </form>
            <div className="card stack">
              <strong>セッション</strong>
              <div className="row"><button onClick={logout}>ログアウト</button></div>
            </div>
            <div className="card stack small muted">
              <div>Hermes Fleet Console 0.1.0</div>
              <div>PWA としてホーム画面に追加できます（Android Chrome: メニュー → ホーム画面に追加）。</div>
            </div>
          </>
        ) : null}
      </div>
    </div>
  );
}

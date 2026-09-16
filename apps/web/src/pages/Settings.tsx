import { useState } from "react";
import { api, getServerUrl, setServerUrl, setToken, isNativeApp } from "../api";
import { ErrorBox } from "../components/ui";

export function Settings({ firstRun }: { firstRun?: boolean }) {
  const [url, setUrl] = useState(getServerUrl());
  const [cur, setCur] = useState("");
  const [next, setNext] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const saveUrl = (e: React.FormEvent) => {
    e.preventDefault();
    setServerUrl(url);
    location.reload();
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
            <input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="http://macmini:8080" />
            <div className="row"><button className="primary">保存して接続</button></div>
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

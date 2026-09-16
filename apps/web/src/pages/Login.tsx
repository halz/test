import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, setToken, getServerUrl, isNativeApp, setServerUrl } from "../api";
import { ErrorBox } from "../components/ui";

export function Login() {
  const state = useQuery({
    queryKey: ["auth-state", getServerUrl()],
    queryFn: async () => {
      const s = await api<{ configured?: boolean; demo?: boolean; demoPassword?: string }>("GET", "/api/auth/state");
      if (!s || typeof s.configured !== "boolean") throw new Error("Fleet Console の API ではない応答でした。サーバー URL を確認してください。");
      return s as { configured: boolean; demo?: boolean; demoPassword?: string };
    },
    retry: 1,
  });
  const [pw, setPw] = useState("");
  const [pw2, setPw2] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const configured = state.data?.configured;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErr(null);
    if (!configured && pw !== pw2) return setErr("パスワードが一致しません");
    setBusy(true);
    try {
      const r = await api<{ token?: string }>("POST", configured ? "/api/auth/login" : "/api/auth/setup", { password: pw });
      if (!r?.token) throw new Error("サーバーからトークンが返りませんでした。サーバー URL を確認してください。");
      setToken(r.token);
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login card">
      <div className="brand"><img src="/icon.svg" alt="" />Hermes Fleet Console</div>
      {state.isLoading ? <p className="muted">接続中…</p> : null}
      {state.isError ? (
        <div className="stack">
          <ErrorBox error={(state.error as Error).message} />
          {isNativeApp() || getServerUrl() ? <a href="#" onClick={(e) => { e.preventDefault(); setServerUrl(""); location.reload(); }}>サーバー URL を変更</a> : null}
        </div>
      ) : null}
      {state.data ? (
        <form className="stack" onSubmit={submit}>
          {state.data.demo ? <div className="alert ok">デモ環境です。モックの Hermes 6 台（Mac 5 台 + Windows 1 台）が登録済みです。パスワード: <code>{state.data.demoPassword}</code></div> : null}
          {configured ? <p className="muted">管理者パスワードを入力してください。</p> : <p className="muted">初回セットアップ: このコンソールの管理者パスワードを決めてください（8 文字以上）。</p>}
          <div><label>パスワード</label><input type="password" value={pw} onChange={(e) => setPw(e.target.value)} autoFocus autoComplete={configured ? "current-password" : "new-password"} /></div>
          {!configured ? <div><label>パスワード（確認）</label><input type="password" value={pw2} onChange={(e) => setPw2(e.target.value)} /></div> : null}
          <ErrorBox error={err} />
          <button className="primary" disabled={busy || pw.length < 1}>{configured ? "ログイン" : "セットアップしてログイン"}</button>
          {isNativeApp() || getServerUrl() ? <div className="muted small">接続先: <code>{getServerUrl() || "(同一オリジン)"}</code> · <a href="#" onClick={(e) => { e.preventDefault(); setServerUrl(""); location.reload(); }}>変更</a></div> : null}
        </form>
      ) : null}
    </div>
  );
}

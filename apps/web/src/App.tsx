import { useEffect, useMemo, useState } from "react";
import { NavLink, Route, Routes, Navigate, useLocation, useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Activity, ChevronDown, ChevronRight, Clock3, History, LayoutDashboard, LogOut, Menu, MessageSquare, RefreshCw, ScrollText, Search, Send, Server, Settings as SettingsIcon, Share2, ShieldCheck, Sparkles, X, Zap } from "lucide-react";
import { api, getServerUrl, getToken, onTokenChange, isNativeApp, setToken } from "./api";
import { Login } from "./pages/Login";
import { Fleet } from "./pages/Fleet";
import { MachineDetail } from "./pages/MachineDetail";
import { Machines } from "./pages/Machines";
import { Ops } from "./pages/Ops";
import { Prompt } from "./pages/Prompt";
import { Cron } from "./pages/Cron";
import { Sessions } from "./pages/Sessions";
import { Audit } from "./pages/Audit";
import { Distribute } from "./pages/Distribute";
import { Logs } from "./pages/Logs";
import { Settings } from "./pages/Settings";
import { useFleet } from "./hooks";

const NAV = [
  { to: "/", label: "フリート", icon: LayoutDashboard },
  { to: "/prompt", label: "プロンプト", icon: MessageSquare },
  { to: "/ops", label: "一括操作", icon: Zap },
  { to: "/distribute", label: "設定配布", icon: Share2 },
  { to: "/logs", label: "ログ", icon: ScrollText },
  { to: "/cron", label: "cron", icon: Clock3 },
  { to: "/sessions", label: "セッション", icon: History },
  { to: "/machines", label: "マシン", icon: Server },
  { to: "/audit", label: "監査ログ", icon: ShieldCheck },
] as const;
const MOBILE = ["/", "/prompt", "/ops", "/machines"];

export function App() {
  const [token, setTok] = useState<string | null>(getToken());
  useEffect(() => onTokenChange(setTok), []);
  if (isNativeApp() && !getServerUrl()) return <Settings firstRun />;
  if (!token) return <Login />;
  return <Shell />;
}

function pageTitle(pathname: string): string {
  if (pathname.startsWith("/machines/")) return "マシン詳細";
  if (pathname === "/settings") return "設定";
  return NAV.find((n) => n.to === pathname)?.label ?? "Hermes Fleet";
}

function Shell() {
  const me = useQuery({ queryKey: ["me"], queryFn: () => api("GET", "/api/auth/me"), retry: false });
  const fleet = useFleet();
  const [menu, setMenu] = useState(false);
  const location = useLocation();
  useEffect(() => setMenu(false), [location.pathname]);
  const alertCount = fleet.snapshots.reduce((n, s) => n + s.alerts.length, 0);
  const offline = fleet.snapshots.filter((s) => !s.online).length;
  const online = fleet.snapshots.length - offline;
  const total = fleet.snapshots.length;
  if (me.isError) return <Login />;
  const host = (() => { try { return new URL(getServerUrl() || window.location.href).host; } catch { return "console"; } })();
  const logout = async () => { try { await api("POST", "/api/auth/logout"); } finally { setToken(null); } };
  return (
    <div className="app">
      <aside className={`sidebar ${menu ? "open" : ""}`}>
        <div className="brand"><span><Sparkles /></span>Hermes Fleet<button onClick={() => setMenu(false)} aria-label="閉じる"><X /></button></div>
        <NavLink to="/machines" className="workspace" style={{ color: "inherit" }}>
          <span className="avatar">HF</span>
          <div><b>{total} 台のフリート</b><small>{host}</small></div>
          <ChevronDown />
        </NavLink>
        <nav className="nav">
          <p>ワークスペース</p>
          {NAV.map((n) => (
            <NavLink key={n.to} to={n.to} end={n.to === "/"}>
              <n.icon /><span>{n.label}</span>
              {n.to === "/" && alertCount > 0 ? <em className={offline ? "warn" : ""}>{alertCount}</em> : null}
            </NavLink>
          ))}
        </nav>
        <div className="side-bottom">
          <NavLink to="/settings"><SettingsIcon />設定</NavLink>
          <div className="usage">
            <span>オンライン <b>{total ? `${online} / ${total}` : "-"}</b></span>
            <div><i style={{ width: total ? `${Math.round((online / total) * 100)}%` : "0%" }} /></div>
            <small>{fleet.connected ? "ライブ更新中" : "再接続中…"}{alertCount ? ` · ${alertCount} 件の注意` : ""}</small>
          </div>
          <div className="user">
            <span className="avatar purple">管</span>
            <div><b>管理者</b><small>{isNativeApp() ? "Android アプリ" : "Web コンソール"}</small></div>
            <a href="#" onClick={(e) => { e.preventDefault(); logout(); }} title="ログアウト"><LogOut /></a>
          </div>
        </div>
      </aside>
      {menu ? <button className="scrim" onClick={() => setMenu(false)} aria-label="メニューを閉じる" /> : null}
      <div className="frame">
        <Topbar title={pageTitle(location.pathname)} alertCount={alertCount} offline={offline} onMenu={() => setMenu(true)} refresh={fleet.refresh} snapshots={fleet.snapshots} />
        <main className="content">
          <Routes>
            <Route path="/" element={<Fleet fleet={fleet} />} />
            <Route path="/machines" element={<Machines />} />
            <Route path="/machines/:id" element={<MachineDetail fleet={fleet} />} />
            <Route path="/ops" element={<Ops />} />
            <Route path="/prompt" element={<Prompt />} />
            <Route path="/distribute" element={<Distribute />} />
            <Route path="/logs" element={<Logs />} />
            <Route path="/cron" element={<Cron />} />
            <Route path="/sessions" element={<Sessions />} />
            <Route path="/audit" element={<Audit />} />
            <Route path="/settings" element={<Settings />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </main>
      </div>
      <nav className="mobile-nav">
        {NAV.filter((n) => MOBILE.includes(n.to)).map((n) => (
          <NavLink key={n.to} to={n.to} end={n.to === "/"}><n.icon /><span>{n.label}</span></NavLink>
        ))}
        <NavLink to="/settings"><SettingsIcon /><span>設定</span></NavLink>
      </nav>
    </div>
  );
}

function Topbar({ title, alertCount, offline, onMenu, refresh, snapshots }: { title: string; alertCount: number; offline: number; onMenu: () => void; refresh: () => Promise<void>; snapshots: ReturnType<typeof useFleet>["snapshots"] }) {
  const [q, setQ] = useState("");
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();
  const hits = useMemo(() => (q.trim() ? snapshots.filter((s) => s.machine.name.toLowerCase().includes(q.trim().toLowerCase())).slice(0, 6) : []), [q, snapshots]);
  const go = (id: string) => { setQ(""); navigate(`/machines/${id}`); };
  const doRefresh = async () => { setBusy(true); try { await refresh(); } finally { setBusy(false); } };
  return (
    <header className="topbar">
      <button className="menu" onClick={onMenu} aria-label="メニュー"><Menu /></button>
      <div className="crumb"><span>Hermes Fleet</span><ChevronRight /><b>{title}</b></div>
      <div className="search" style={{ position: "relative" }}>
        <Search />
        <input placeholder="マシンを検索…" value={q} onChange={(e) => setQ(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter" && hits[0]) go(hits[0].machine.id); if (e.key === "Escape") setQ(""); }} />
        <kbd>↵</kbd>
        {hits.length ? <div className="search-results">{hits.map((s) => <a key={s.machine.id} href="#" onClick={(e) => { e.preventDefault(); go(s.machine.id); }}><span className={`dot ${s.online ? "ok" : "err"}`} />{s.machine.name}<small className="muted">{s.machine.os}</small></a>)}</div> : null}
      </div>
      <div className="top-actions">
        {offline ? <span className="status red"><i />{offline} 台オフライン</span> : alertCount ? <span className="status amber"><i />{alertCount} 件の注意</span> : <span className="status"><i />全マシン正常</span>}
        <button className="icon" onClick={doRefresh} disabled={busy} title="今すぐ更新"><RefreshCw className={busy ? "spin" : ""} /></button>
        <button className="icon" onClick={() => navigate("/ops")} title="一括操作"><Activity /></button>
        <button className="primary" onClick={() => navigate("/prompt")}><Send /> <span>プロンプト</span></button>
      </div>
    </header>
  );
}

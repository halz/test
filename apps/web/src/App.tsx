import { useEffect, useState } from "react";
import { NavLink, Route, Routes, Navigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api, getToken, onTokenChange, isNativeApp, getServerUrl } from "./api";
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
  ["/", "フリート", "⌂"],
  ["/prompt", "プロンプト", "✎"],
  ["/ops", "一括操作", "⚙"],
  ["/distribute", "設定配布", "⇉"],
  ["/logs", "ログ", "≡"],
  ["/cron", "cron", "⏱"],
  ["/sessions", "セッション", "☰"],
  ["/machines", "マシン", "▣"],
  ["/audit", "監査ログ", "✓"],
  ["/settings", "設定", "⚒"],
] as const;

export function App() {
  const [token, setTok] = useState<string | null>(getToken());
  useEffect(() => onTokenChange(setTok), []);
  if (isNativeApp() && !getServerUrl()) return <Settings firstRun />;
  if (!token) return <Login />;
  return <Shell />;
}

function Shell() {
  const me = useQuery({ queryKey: ["me"], queryFn: () => api("GET", "/api/auth/me"), retry: false });
  const fleet = useFleet();
  const alertCount = fleet.snapshots.reduce((n, s) => n + s.alerts.length, 0);
  if (me.isError) return <Login />;
  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand"><img src="/icon.svg" alt="" />Hermes Fleet</div>
        <nav className="nav">
          {NAV.map(([to, label, icon]) => (
            <NavLink key={to} to={to} end={to === "/"}>
              <span>{icon}</span>{label}
              {to === "/" && alertCount > 0 ? <span className="count">{alertCount}</span> : null}
            </NavLink>
          ))}
        </nav>
      </aside>
      <main className="main">
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
      <nav className="mobile-nav">
        {NAV.filter(([to]) => ["/", "/prompt", "/ops", "/distribute", "/cron", "/machines"].includes(to)).map(([to, label, icon]) => (
          <NavLink key={to} to={to} end={to === "/"}><div>{icon}</div>{label}</NavLink>
        ))}
        <NavLink to="/settings"><div>⚒</div>設定</NavLink>
      </nav>
    </div>
  );
}

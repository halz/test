import { useEffect, useState } from "react";
import { api } from "../api";
import { useFleet, useMachines } from "../hooks";
import { MachinePicker } from "../components/ui";

interface Pane { machineId: string; name: string; lines: string[]; error?: string; at: number }

export function Logs() {
  const machines = useMachines();
  const fleet = useFleet();
  const [ids, setIds] = useState<string[]>([]);
  const [file, setFile] = useState("agent");
  const [level, setLevel] = useState("");
  const [search, setSearch] = useState("");
  const [lines, setLines] = useState(100);
  const [auto, setAuto] = useState(true);
  const [panes, setPanes] = useState<Pane[]>([]);
  const [busy, setBusy] = useState(false);

  const load = async () => {
    if (!ids.length) return;
    setBusy(true);
    const list = machines.data ?? [];
    const q = `file=${file}&lines=${lines}${level ? `&level=${level}` : ""}${search ? `&search=${encodeURIComponent(search)}` : ""}`;
    const res = await Promise.all(ids.map(async (id): Promise<Pane> => {
      const name = list.find((m) => m.id === id)?.name ?? id;
      try {
        const r = await api<{ lines: string[] }>("GET", `/api/machines/${id}/logs?${q}`);
        return { machineId: id, name, lines: r.lines, at: Date.now() };
      } catch (e) {
        return { machineId: id, name, lines: [], error: e instanceof Error ? e.message : String(e), at: Date.now() };
      }
    }));
    setPanes(res);
    setBusy(false);
  };
  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ids.join(","), file, level, search, lines]);
  useEffect(() => {
    if (!auto || !ids.length) return;
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [auto, ids.join(","), file, level, search, lines]);

  return (
    <>
      <div className="page-bar"><h1>ログ（複数マシン）</h1><span className="spacer" /><button onClick={load} disabled={busy || !ids.length}>更新</button></div>
      <div className="stack">
        <div className="card stack">
          <MachinePicker machines={machines.data ?? []} value={ids} onChange={setIds} snapshots={fleet.snapshots} />
          <div className="row">
            <select style={{ width: 140 }} value={file} onChange={(e) => setFile(e.target.value)}><option value="agent">agent</option><option value="gateway">gateway</option><option value="errors">errors</option></select>
            <select style={{ width: 120 }} value={level} onChange={(e) => setLevel(e.target.value)}><option value="">全レベル</option><option>ERROR</option><option>WARNING</option><option>INFO</option><option>DEBUG</option></select>
            <select style={{ width: 100 }} value={lines} onChange={(e) => setLines(Number(e.target.value))}>{[50, 100, 200, 500].map((n) => <option key={n} value={n}>{n} 行</option>)}</select>
            <input style={{ width: 220 }} placeholder="検索" value={search} onChange={(e) => setSearch(e.target.value)} />
            <label className="row" style={{ margin: 0 }}><input type="checkbox" style={{ width: "auto" }} checked={auto} onChange={(e) => setAuto(e.target.checked)} /> 5 秒ごと更新</label>
          </div>
        </div>
        <div className="runs">
          {panes.map((p) => (
            <div key={p.machineId} className="card stack" style={{ gap: 6 }}>
              <div className="row" style={{ justifyContent: "space-between" }}><strong>{p.name}</strong><span className="muted small">{p.lines.length} 行</span></div>
              {p.error ? <div className="alert err">{p.error}</div> : <pre style={{ maxHeight: "60vh", margin: 0 }}>{p.lines.join("\n")}</pre>}
            </div>
          ))}
        </div>
      </div>
    </>
  );
}

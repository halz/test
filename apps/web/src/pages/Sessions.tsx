import { useState } from "react";
import { api, fmtAgo } from "../api";
import { useMachines } from "../hooks";
import { Badge, ErrorBox, useAsync, Table } from "../components/ui";

export function Sessions() {
  const machines = useMachines();
  const list = machines.data ?? [];
  const [query, setQuery] = useState("");
  const [submitted, setSubmitted] = useState("");
  const [open, setOpen] = useState<{ machineId: string; id: string } | null>(null);
  const q = useAsync(async () => {
    const results = await Promise.all(list.map(async (m) => {
      try {
        if (submitted) {
          const r = await api<{ results?: Record<string, unknown>[]; sessions?: Record<string, unknown>[] }>("GET", `/api/machines/${m.id}/sessions/search?q=${encodeURIComponent(submitted)}&limit=20`);
          return { machine: m, sessions: r.results ?? r.sessions ?? [], error: null as string | null };
        }
        const r = await api<{ sessions: Record<string, unknown>[] }>("GET", `/api/machines/${m.id}/sessions?limit=10`);
        return { machine: m, sessions: r.sessions, error: null as string | null };
      } catch (e) {
        return { machine: m, sessions: [], error: e instanceof Error ? e.message : String(e) };
      }
    }));
    return results;
  }, [list.map((m) => m.id).join(","), submitted]);
  const msgs = useAsync(() => (open ? api<unknown>("GET", `/api/machines/${open.machineId}/sessions/${open.id}/messages?limit=100`) : Promise.resolve(null)), [open?.machineId, open?.id]);
  const rows: (Record<string, unknown> & { _machine: { id: string; name: string } })[] = (q.data ?? []).flatMap((r) => r.sessions.map((s) => ({ ...s, _machine: r.machine })));
  const errors = (q.data ?? []).filter((r) => r.error).map((r) => `${r.machine.name}: ${r.error}`);
  return (
    <>
      <div className="page-bar">
        <h1>セッション（全マシン）</h1>
        <form className="row" onSubmit={(e) => { e.preventDefault(); setSubmitted(query.trim()); }}>
          <input style={{ width: 280 }} placeholder="全文検索…（空で最近のセッション）" value={query} onChange={(e) => setQuery(e.target.value)} />
          <button className="primary">検索</button>
        </form>
        <span className="spacer" /><button onClick={q.reload}>更新</button>
      </div>
      {errors.length ? <div className="alert" style={{ marginBottom: 10 }}>{errors.join(" / ")}</div> : null}
      <ErrorBox error={q.error} />
      <div className="card" style={{ padding: 0, overflow: "auto" }}>
        <Table>
          <thead><tr><th>マシン</th><th>タイトル</th><th>ソース</th><th>件数</th><th>開始</th><th>状態</th></tr></thead>
          <tbody>
            {rows.map((s) => (
              <tr key={`${s._machine.id}:${String(s.id)}`} style={{ cursor: "pointer" }} onClick={() => setOpen({ machineId: s._machine.id, id: String(s.id) })}>
                <td>{s._machine.name}</td><td>{String(s.title ?? s.id)}{s.snippet ? <div className="muted small">{String(s.snippet)}</div> : null}</td><td>{String(s.source ?? "")}</td><td>{String(s.message_count ?? "")}</td><td>{fmtAgo(s.started_at as number)}</td>
                <td>{s.is_active ? <Badge kind="ok">active</Badge> : s.archived ? <Badge>archived</Badge> : null}</td>
              </tr>
            ))}
            {!q.loading && rows.length === 0 ? <tr><td colSpan={6} className="muted">該当なし</td></tr> : null}
          </tbody>
        </Table>
      </div>
      {open ? <div className="card" style={{ marginTop: 12 }}><div className="row" style={{ justifyContent: "space-between" }}><strong>{open.id}</strong><button className="small" onClick={() => setOpen(null)}>閉じる</button></div><pre>{msgs.data ? JSON.stringify(msgs.data, null, 2) : msgs.error ?? "…"}</pre></div> : null}
    </>
  );
}

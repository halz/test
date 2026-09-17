import { useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { Badge, ErrorBox } from "../components/ui";
import type { AuditEntry } from "../types";

export function Audit() {
  const q = useQuery({ queryKey: ["audit"], queryFn: () => api<{ entries: AuditEntry[] }>("GET", "/api/audit?limit=300"), refetchInterval: 15000 });
  return (
    <>
      <div className="page-bar"><h1>監査ログ</h1><span className="spacer" /><button onClick={() => q.refetch()}>更新</button></div>
      <ErrorBox error={q.error ? (q.error as Error).message : null} />
      <div className="card" style={{ padding: 0, overflow: "auto" }}>
        <table>
          <thead><tr><th>日時</th><th>操作</th><th>マシン</th><th>詳細</th><th></th></tr></thead>
          <tbody>
            {(q.data?.entries ?? []).map((e) => (
              <tr key={e.id}>
                <td className="mono">{new Date(e.at).toLocaleString()}</td>
                <td><code>{e.action}</code></td>
                <td>{e.machineName ?? "-"}</td>
                <td className="mono" style={{ maxWidth: 480, overflow: "hidden", textOverflow: "ellipsis" }}>{e.detail}</td>
                <td>{e.ok ? <Badge kind="ok">ok</Badge> : <Badge kind="err">失敗</Badge>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

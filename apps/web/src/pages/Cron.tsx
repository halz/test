import { useState } from "react";
import { api } from "../api";
import { useMachines } from "../hooks";
import { ErrorBox, MachinePicker, Modal, useAsync } from "../components/ui";
import { CronTable } from "./MachineDetail";

export function Cron() {
  const machines = useMachines();
  const list = machines.data ?? [];
  const q = useAsync(async () => {
    const results = await Promise.all(list.map(async (m) => {
      try {
        const r = await api<{ jobs?: Record<string, unknown>[] } | Record<string, unknown>[]>("GET", `/api/machines/${m.id}/cron`);
        const jobs = Array.isArray(r) ? r : r.jobs ?? [];
        return { machine: m, jobs, error: null as string | null };
      } catch (e) {
        return { machine: m, jobs: [], error: e instanceof Error ? e.message : String(e) };
      }
    }));
    return results;
  }, [list.map((m) => m.id).join(",")]);
  const [creating, setCreating] = useState(false);
  const [ids, setIds] = useState<string[]>([]);
  const [form, setForm] = useState({ name: "", prompt: "", schedule: "0 9 * * *", deliver: "local" });
  const [err, setErr] = useState<string | null>(null);
  const jobs = (q.data ?? []).flatMap((r) => r.jobs.map((j) => ({ ...j, _machineId: r.machine.id, _machineName: r.machine.name })));
  const errors = (q.data ?? []).filter((r) => r.error).map((r) => `${r.machine.name}: ${r.error}`);
  const act = async (jid: string, action: string, machineId: string) => {
    if (action === "delete" && !confirm("削除しますか？")) return;
    if (action === "delete") await api("DELETE", `/api/machines/${machineId}/cron/${jid}`);
    else await api("POST", `/api/machines/${machineId}/cron/${jid}/${action}`);
    q.reload();
  };
  const create = async () => {
    setErr(null);
    const failed: string[] = [];
    for (const id of ids) {
      try {
        await api("POST", `/api/machines/${id}/cron`, form);
      } catch (e) {
        failed.push(`${list.find((m) => m.id === id)?.name}: ${e instanceof Error ? e.message : e}`);
      }
    }
    if (failed.length) setErr(failed.join("\n"));
    else setCreating(false);
    q.reload();
  };
  return (
    <>
      <div className="topbar"><h1>cron（全マシン）</h1><span className="spacer" /><button onClick={q.reload}>更新</button><button className="primary" onClick={() => setCreating(true)}>＋ 複数マシンに作成</button></div>
      {errors.length ? <div className="alert" style={{ marginBottom: 10 }}>{errors.join(" / ")}</div> : null}
      {q.loading ? <p className="muted">読み込み中…</p> : null}
      <CronTable jobs={jobs} onAction={act} showMachine />
      {creating ? (
        <Modal title="cron ジョブを複数マシンに作成" onClose={() => setCreating(false)} footer={<><button onClick={() => setCreating(false)}>キャンセル</button><button className="primary" onClick={create} disabled={!ids.length || !form.name || !form.prompt}>{ids.length} 台に作成</button></>}>
          <div className="stack">
            <MachinePicker machines={list} value={ids} onChange={setIds} />
            <div className="form">
              <div><label>名前</label><input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></div>
              <div><label>スケジュール (cron 式)</label><input value={form.schedule} onChange={(e) => setForm({ ...form, schedule: e.target.value })} /></div>
              <div className="full"><label>プロンプト</label><textarea value={form.prompt} onChange={(e) => setForm({ ...form, prompt: e.target.value })} /></div>
              <div><label>配信先</label><input value={form.deliver} onChange={(e) => setForm({ ...form, deliver: e.target.value })} /></div>
            </div>
            <ErrorBox error={err} />
          </div>
        </Modal>
      ) : null}
    </>
  );
}

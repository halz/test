import { useEffect, useRef, useState, type ReactNode } from "react";
import type { Machine, Snapshot } from "../types";

export function Badge({ kind, children }: { kind?: "ok" | "warn" | "err" | "info"; children: ReactNode }) {
  return <span className={`badge ${kind ?? ""}`}>{children}</span>;
}

export function StatusDot({ snap }: { snap: Snapshot }) {
  const cls = !snap.online ? "err" : snap.alerts.length ? "warn" : "ok";
  return <span className={`dot ${cls}`} title={snap.online ? "online" : "offline"} />;
}

export function Meter({ pct, label }: { pct?: number; label: string }) {
  const p = Math.max(0, Math.min(100, pct ?? 0));
  const cls = p >= 90 ? "err" : p >= 75 ? "warn" : "";
  return (
    <div>
      <div className="row small" style={{ justifyContent: "space-between" }}>
        <span className="muted">{label}</span>
        <span>{pct === undefined ? "-" : `${Math.round(p)}%`}</span>
      </div>
      <div className={`meter ${cls}`}><span style={{ width: `${p}%` }} /></div>
    </div>
  );
}

export function Modal({ title, onClose, children, footer }: { title: string; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
  return (
    <div className="modal-bg" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="row" style={{ justifyContent: "space-between", marginBottom: 12 }}>
          <strong>{title}</strong>
          <button className="small" onClick={onClose}>閉じる</button>
        </div>
        {children}
        {footer ? <div className="row" style={{ justifyContent: "flex-end", marginTop: 14 }}>{footer}</div> : null}
      </div>
    </div>
  );
}

export function MachinePicker({ machines, value, onChange, snapshots }: { machines: Machine[]; value: string[]; onChange: (ids: string[]) => void; snapshots?: Snapshot[] }) {
  const tags = [...new Set(machines.flatMap((m) => m.tags))].sort();
  const toggle = (id: string) => onChange(value.includes(id) ? value.filter((v) => v !== id) : [...value, id]);
  const online = (id: string) => snapshots?.find((s) => s.machine.id === id)?.online;
  return (
    <div className="stack">
      <div className="row small">
        <button className="small" onClick={() => onChange(machines.map((m) => m.id))}>すべて</button>
        <button className="small" onClick={() => onChange(snapshots ? machines.filter((m) => online(m.id)).map((m) => m.id) : value)}>オンラインのみ</button>
        <button className="small" onClick={() => onChange([])}>解除</button>
        {tags.map((t) => (
          <button key={t} className="small" onClick={() => onChange([...new Set([...value, ...machines.filter((m) => m.tags.includes(t)).map((m) => m.id)])])}>#{t}</button>
        ))}
      </div>
      <div className="picker">
        {machines.map((m) => (
          <label key={m.id} className={value.includes(m.id) ? "on" : ""}>
            <input type="checkbox" checked={value.includes(m.id)} onChange={() => toggle(m.id)} />
            {snapshots ? <span className={`dot ${online(m.id) ? "ok" : "err"}`} /> : null}
            {m.name}
          </label>
        ))}
      </div>
    </div>
  );
}

export function useAsync<T>(fn: () => Promise<T>, deps: unknown[]): { data: T | null; error: string | null; loading: boolean; reload: () => void } {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let alive = true;
    setLoading(true);
    fn().then((d) => alive && (setData(d), setError(null))).catch((e) => alive && setError(e instanceof Error ? e.message : String(e))).finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);
  return { data, error, loading, reload: () => setTick((t) => t + 1) };
}

export function ErrorBox({ error }: { error: string | null | undefined }) {
  return error ? <div className="alert err">{error}</div> : null;
}

export function osIcon(os: string): string {
  const o = os.toLowerCase();
  if (o.includes("mac") || o.includes("darwin")) return "";
  if (o.includes("win")) return "⊞";
  if (o.includes("linux")) return "🐧";
  return "▣";
}

/** Table that stacks each row into a labelled block on narrow screens (labels come from the header cells). */
export function Table({ children, className }: { children: ReactNode; className?: string }) {
  const ref = useRef<HTMLTableElement>(null);
  useEffect(() => {
    const t = ref.current;
    if (!t) return;
    const heads = [...t.querySelectorAll(":scope > thead th")].map((h) => h.textContent?.trim() ?? "");
    t.querySelectorAll(":scope > tbody > tr").forEach((tr) => {
      [...tr.children].forEach((td, i) => {
        if (td.tagName === "TD" && !td.hasAttribute("colspan")) td.setAttribute("data-label", heads[i] ?? "");
      });
    });
  });
  return <table ref={ref} className={`stack ${className ?? ""}`}>{children}</table>;
}

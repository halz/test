export class HermesHttpError extends Error {
  constructor(
    public readonly status: number,
    public readonly url: string,
    public readonly body: string,
  ) {
    super(`HTTP ${status} from ${url}: ${body.slice(0, 300)}`);
    this.name = "HermesHttpError";
  }
}

export class HermesAuthError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "HermesAuthError";
  }
}

export function joinUrl(base: string, path: string): string {
  const b = base.replace(/\/+$/, "");
  const p = path.startsWith("/") ? path : `/${path}`;
  return b + p;
}

export async function readBodyText(res: Response): Promise<string> {
  try {
    return await res.text();
  } catch {
    return "";
  }
}

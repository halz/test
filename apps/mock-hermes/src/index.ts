export { startMockMachine } from "./machine.js";
export type { MockMachine, MockMachineOptions } from "./machine.js";

export interface FleetSpec {
  name: string;
  os: "macOS" | "Windows" | "Linux";
  dashboardPort: number;
  apiPort: number;
  username: string;
  password: string;
  apiKey: string;
  updateBehind: number;
  gatewayRunning: boolean;
}

/** Default demo fleet: 5 Macs + 1 Windows 11, mirroring the target deployment. */
export function defaultFleet(basePort = 19119, count = 6): FleetSpec[] {
  const out: FleetSpec[] = [];
  for (let i = 0; i < count; i++) {
    const isWin = i === count - 1;
    out.push({
      name: isWin ? "win-1" : `mac-${i + 1}`,
      os: isWin ? "Windows" : "macOS",
      dashboardPort: basePort + i * 2,
      apiPort: basePort + i * 2 + 1,
      username: "admin",
      password: "hermes",
      apiKey: `mock-key-${i + 1}`,
      updateBehind: i === 2 || i === 4 ? 3 : 0,
      gatewayRunning: !isWin,
    });
  }
  return out;
}

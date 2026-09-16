import type { CapacitorConfig } from "@capacitor/cli";

// Android shell for the Fleet Console SPA. The app bundles the web build and talks to the
// console server URL configured on first launch (Settings → サーバー URL). The console is
// usually reached over Tailscale with plain http, so cleartext + mixed content are allowed.
const config: CapacitorConfig = {
  appId: "ai.hermes.fleetconsole",
  appName: "Hermes Fleet",
  webDir: "dist",
  server: {
    androidScheme: "http",
    cleartext: true,
  },
  android: {
    allowMixedContent: true,
  },
};

export default config;

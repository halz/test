#!/usr/bin/env node
// Emit the hosted demo (console + in-process mock fleet) in Vercel Build Output API v3 layout:
//   .vercel/output/static/**               web SPA
//   .vercel/output/functions/api/index.func  bundled Node function
// Run after `npm run build`. On Vercel this is the buildCommand (see vercel.json).
import { build } from "esbuild";
import { cpSync, mkdirSync, rmSync, writeFileSync, existsSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(new URL("..", import.meta.url).pathname);
const out = resolve(root, ".vercel/output");
rmSync(out, { recursive: true, force: true });
const fn = resolve(out, "functions/api/index.func");
mkdirSync(fn, { recursive: true });

await build({
  entryPoints: [resolve(root, "apps/server/src/vercel.ts")],
  outfile: resolve(fn, "index.mjs"),
  bundle: true,
  platform: "node",
  format: "esm",
  target: "node22",
  minify: true,
  sourcemap: false,
  external: ["node:*"],
  banner: { js: "import { createRequire } from 'node:module'; const require = createRequire(import.meta.url);" },
  logLevel: "info",
});
writeFileSync(resolve(fn, ".vc-config.json"), JSON.stringify({ runtime: "nodejs22.x", handler: "index.mjs", launcherType: "Nodejs", shouldAddHelpers: false, maxDuration: 300, memory: 1024 }, null, 2));
writeFileSync(resolve(fn, "package.json"), JSON.stringify({ type: "module" }));

const webDist = resolve(root, "apps/web/dist");
if (!existsSync(webDist)) throw new Error("apps/web/dist missing: run npm run build first");
cpSync(webDist, resolve(out, "static"), { recursive: true });

writeFileSync(
  resolve(out, "config.json"),
  JSON.stringify(
    {
      version: 3,
      routes: [
        { handle: "filesystem" },
        { src: "/api/(.*)", dest: "/api/index" },
        { src: "/(.*)", dest: "/index.html" },
      ],
    },
    null,
    2,
  ),
);
console.log(`[build-vercel-demo] wrote ${out}`);

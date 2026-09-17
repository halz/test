const { chromium } = require("playwright");
const S = process.env.SHOT_DIR ?? require("node:path").resolve(__dirname, "../../../.artifacts");
require("node:fs").mkdirSync(S, { recursive: true });
const B = process.env.FLEET_URL ?? "http://127.0.0.1:18080";
(async () => {
  const browser = await chromium.launch({ ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}) });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  page.on("pageerror", (e) => console.log("PAGEERROR", e.message));
  page.on("console", (m) => m.type() === "error" && console.log("CONSOLE", m.text()));
  await page.goto(B + "/");
  await page.waitForSelector("input[type=password]", { timeout: 10000 });
  if (await page.$("text=デモ環境です")) {
    // Hosted demo: fixed password, already configured.
    await page.fill("input[type=password] >> nth=0", "demo");
    await page.click("button:has-text('ログイン')");
  } else if (await page.$("text=パスワード（確認）")) {
    await page.fill("input[type=password] >> nth=0", "correct horse battery");
    await page.fill("input[type=password] >> nth=1", "correct horse battery");
    await page.click("button:has-text('セットアップしてログイン')");
  } else {
    // Console already set up by a previous run against the same data dir.
    await page.fill("input[type=password] >> nth=0", "correct horse battery");
    await page.click("button:has-text('ログイン')");
  }
  await page.waitForSelector("text=オンライン", { timeout: 10000 });
  await page.waitForFunction(() => document.querySelectorAll(".machine-grid > article").length >= 6, null, { timeout: 15000 });
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `${S}/shot-fleet.png`, fullPage: true });
  const cards = await page.$$eval(".machine-grid > article", (els) => els.map((e) => e.querySelector("h2 a")?.textContent?.trim()));
  console.log("fleet cards:", cards.join(", "));
  const alerts = await page.$$eval(".machine-grid .machine-alerts span", (els) => els.map((e) => e.textContent));
  console.log("alerts:", alerts.join(" | "));

  // machine detail
  await page.click(".machine-grid h2 a:has-text('mac-4')");
  await page.waitForSelector("text=ホスト名");
  await page.click("button:has-text('ログ')");
  await page.waitForFunction(() => (document.querySelector("pre")?.textContent ?? "").includes("simulated log line"), null, { timeout: 10000 });
  await page.screenshot({ path: `${S}/shot-detail-logs.png`, fullPage: true });
  await page.click("button:has-text('cron')");
  await page.waitForSelector("text=Daily summary");

  // models / providers tab (mac-2 has a coder profile)
  await page.click("nav.nav >> text=フリート");
  await page.click(".machine-grid h2 a:has-text('mac-2')");
  await page.waitForSelector("text=ホスト名");
  await page.click("button:has-text('モデル・プロバイダ')");
  await page.waitForSelector("text=メインモデル", { timeout: 10000 });
  await page.waitForSelector("text=LLM プロバイダの認証");
  await page.waitForFunction(() => document.querySelectorAll("table tbody tr").length > 5, null, { timeout: 10000 });
  await page.selectOption("select >> nth=1", "anthropic");
  await page.fill("input[placeholder='モデル ID'] >> nth=0", "anthropic/claude-haiku-4-5");
  await page.click("button:has-text('適用')");
  await page.waitForSelector("text=メインモデルを anthropic / anthropic/claude-haiku-4-5 に設定しました", { timeout: 10000 });
  await page.click((await page.$("button:has-text('キーを設定')")) ? "button:has-text('キーを設定') >> nth=0" : "button:has-text('キーを更新') >> nth=0");
  await page.fill("input[type=password]", "sk-test-1234");
  await page.click("button:has-text('検証して保存')");
  await page.waitForSelector("button:has-text('検証して保存')", { state: "detached", timeout: 10000 });
  await page.click("button:has-text('＋ 追加') >> nth=1");
  await page.waitForSelector("text=ルーティングを保存");
  await page.click("button:has-text('ルーティングを保存')");
  await page.waitForSelector("text=ルーティング設定を保存しました", { timeout: 10000 });
  await page.screenshot({ path: `${S}/shot-models.png`, fullPage: true });
  await page.click("button:has-text('プロファイル')");
  await page.waitForSelector("text=coder", { timeout: 10000 });
  await page.click("button:has-text('＋ プロファイル作成')");
  await page.fill("input[placeholder='coder']", "writer");
  await page.click(".modal button.primary:has-text('作成')");
  await page.waitForSelector("tr:has-text('writer')", { timeout: 10000 });
  page.once("dialog", (d) => d.accept());
  await page.click("tr:has-text('writer') >> button:has-text('削除')");
  await page.waitForSelector("tr:has-text('writer')", { state: "detached", timeout: 10000 });
  await page.screenshot({ path: `${S}/shot-profiles.png`, fullPage: true });

  // prompt
  await page.click("nav.nav >> text=プロンプト");
  await page.waitForSelector("text=対象マシン");
  await page.click(".picker label:has-text('mac-1')");
  await page.click(".picker label:has-text('mac-2')");
  await page.click(".picker label:has-text('win-1')");
  await page.fill("textarea", "ホスト名を教えて。please approve");
  await page.click("button:has-text('3 台に送信')");
  await page.waitForSelector("text=承認が必要です", { timeout: 15000 });
  await page.screenshot({ path: `${S}/shot-prompt-approval.png`, fullPage: true });
  const approveButtons = await page.$$("button:has-text('1 回許可')");
  for (const b of approveButtons) await b.click();
  await page.waitForFunction(() => [...document.querySelectorAll(".runs .badge")].filter((b) => b.textContent === "completed").length >= 2, null, { timeout: 20000 });
  await page.waitForTimeout(500);
  await page.screenshot({ path: `${S}/shot-prompt-done.png`, fullPage: true });
  const outs = await page.$$eval(".run-out", (els) => els.map((e) => e.textContent.slice(0, 60)));
  console.log("run outputs:", outs.join(" || "));
  const statuses = await page.$$eval(".runs .badge", (els) => els.map((e) => e.textContent));
  console.log("run statuses:", statuses.join(","));

  // ops: batch restart
  await page.click("nav.nav >> text=一括操作");
  await page.waitForSelector("text=対象マシン");
  await page.click("button:has-text('すべて')");
  await page.click("button:has-text('を 6 台で実行')");
  await page.waitForSelector("text=実行の確認");
  await page.click("button:has-text('実行する')");
  await page.waitForFunction(() => [...document.querySelectorAll(".card .badge")].filter((b) => b.textContent === "ok").length >= 6, null, { timeout: 60000 });
  await page.screenshot({ path: `${S}/shot-ops.png`, fullPage: true });
  console.log("ops job:", await page.$eval(".card .row .badge", (e) => e.textContent));

  // machines page + add form test
  await page.click("nav.nav >> text=マシン");
  await page.waitForSelector("text=有効化手順");
  await page.click("button:has-text('＋ 追加')");
  // In the hosted demo the mocks live in-process behind *.demo hosts; locally they listen on 19119/19120.
  const demoHosts = Boolean(await page.evaluate(() => fetch("/api/auth/state").then((r) => r.json()).then((s) => s.demo)));
  await page.fill("input[placeholder='mac-mini']", "mac-7");
  await page.fill("input[placeholder='http://100.x.y.z:9119']", demoHosts ? "http://mac-1.demo:9119" : "http://127.0.0.1:19119");
  await page.fill("input[type=password] >> nth=0", "hermes");
  await page.fill("input[placeholder='http://100.x.y.z:8642']", demoHosts ? "http://mac-1.demo:8642" : "http://127.0.0.1:19120");
  await page.fill("input[type=password] >> nth=1", "mock-key-1");
  await page.click("button:has-text('接続テスト')");
  await page.waitForSelector("text=OK (v", { timeout: 10000 });
  await page.screenshot({ path: `${S}/shot-machine-form.png` });
  await page.click("button:has-text('閉じる')");
  await page.click("tr:has-text('win-1') >> button:has-text('有効化手順')");
  await page.waitForSelector("text=API_SERVER_ENABLED=true");
  await page.screenshot({ path: `${S}/shot-enroll.png` });
  await page.click("button:has-text('閉じる')");

  // distribute: preset -> preview -> apply
  await page.click("nav.nav >> text=設定配布");
  await page.waitForSelector("text=差分プレビュー");
  await page.click(".picker label:has-text('mac-3')");
  await page.click("button:has-text('無人実行の承認を deny に')");
  await page.click("button:has-text('差分プレビュー')");
  await page.waitForSelector("table >> text=approvals.unattended_mode", { timeout: 10000 });
  page.once("dialog", (d) => d.accept());
  await page.click("button:has-text('適用（1 台）')");
  await page.waitForSelector("text=適用済み", { timeout: 10000 });
  await page.screenshot({ path: `${S}/shot-distribute.png`, fullPage: true });

  // multi-machine logs
  await page.click("nav.nav >> text=ログ");
  await page.waitForSelector("text=5 秒ごと更新");
  await page.click("button:has-text('すべて')");
  await page.waitForFunction(() => document.querySelectorAll(".runs pre").length >= 6, null, { timeout: 15000 });
  await page.screenshot({ path: `${S}/shot-logs.png`, fullPage: false });

  // cron + sessions + audit
  await page.click("nav.nav >> text=cron");
  await page.waitForSelector("text=Daily summary");
  await page.click("nav.nav >> text=セッション");
  await page.waitForSelector("text=mac-1 session 1");
  await page.fill("input[placeholder^='全文検索']", "session 3");
  await page.click("button:has-text('検索')");
  await page.waitForSelector("text=mac-2 session 3");
  await page.click("nav.nav >> text=監査ログ");
  await page.waitForSelector("text=prompt.batch");

  // mobile viewport
  const mobile = await browser.newPage({ viewport: { width: 390, height: 844 } });
  await mobile.goto(B + "/");
  await mobile.evaluate((t) => localStorage.setItem("fleet.token", t), await page.evaluate(() => localStorage.getItem("fleet.token")));
  await mobile.goto(B + "/");
  await mobile.waitForFunction(() => document.querySelectorAll(".machine-grid > article").length >= 6, null, { timeout: 15000 });
  await mobile.screenshot({ path: `${S}/shot-mobile.png`, fullPage: false });
  console.log("UI SMOKE PASSED");
  await browser.close();
})().catch((e) => { console.error("UI SMOKE FAILED", e); process.exit(1); });

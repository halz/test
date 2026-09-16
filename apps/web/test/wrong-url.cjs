const { chromium } = require("playwright");
(async () => {
  const browser = await chromium.launch({ ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}) });
  const page = await browser.newPage({ viewport: { width: 420, height: 800 } });
  await page.goto("http://127.0.0.1:18082/");
  // Simulate the APK pointing at a wrong URL (a server that answers HTML to /api/*).
  await page.evaluate(() => localStorage.setItem("fleet.serverUrl", "http://127.0.0.1:18082"));
  await page.reload();
  await page.waitForSelector(".alert.err", { timeout: 10000 });
  const txt = await page.$eval(".alert.err", (e) => e.textContent);
  console.log("wrong-url message:", txt.slice(0, 120));
  if (await page.$("text=初回セットアップ")) throw new Error("setup form must not show for a non-JSON server");
  const link = await page.$("text=サーバー URL を変更"); if (!link) throw new Error("no change-URL link");
  await page.screenshot({ path: (process.env.SHOT_DIR ?? ".") + "/shot-wrong-url.png" });
  // Now point at the real demo console (scheme-less on purpose is only normalized via Settings; here set full URL)
  await page.evaluate(() => localStorage.setItem("fleet.serverUrl", "http://127.0.0.1:18080"));
  await page.reload();
  await page.waitForSelector("text=初回セットアップ", { timeout: 10000 });
  console.log("correct URL -> setup form shown (server not configured yet)");
  console.log("WRONG-URL TEST PASSED");
  await browser.close();
})().catch((e) => { console.error("WRONG-URL TEST FAILED", e.message); process.exit(1); });

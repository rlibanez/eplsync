import { chromium } from "@playwright/test";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test, expect } from "./fixtures";

test("Chromium recognizes the production manifest as installable without a service worker", async ({
  baseURL,
}) => {
  // Installation is intentionally unavailable in Playwright's usual incognito contexts.
  const profile = await mkdtemp(join(tmpdir(), "eplsync-pwa-"));
  const context = await chromium.launchPersistentContext(profile, {
    headless: true,
    executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,
  });
  try {
    await context.route("**/api/**", (route) =>
      new URL(route.request().url()).pathname === "/api/auth/status"
        ? route.fulfill({
            json: {
              initialized: true,
              registrationEnabled: false,
              approvalRequired: true,
              passwordMinimumLength: 8,
              initialAdminKeyRequired: false,
            },
          })
        : route.fulfill({ status: 401, json: { code: "AUTH_REQUIRED" } }),
    );
    const page = await context.newPage();
    await page.goto(`${baseURL}/settings/account`);
    await expect(page.locator('link[rel="manifest"]')).toHaveAttribute(
      "href",
      "/manifest.webmanifest",
    );
    const session = await context.newCDPSession(page);
    await session.send("Page.enable");
    const manifest = await session.send("Page.getAppManifest");
    expect(manifest.errors).toEqual([]);
    const data = JSON.parse(manifest.data!);
    expect(data).toMatchObject({
      id: "/",
      name: "EPL Sync",
      start_url: "/",
      scope: "/",
      display: "standalone",
      prefer_related_applications: false,
    });
    await expect
      .poll(
        async () =>
          (await session.send("Page.getInstallabilityErrors"))
            .installabilityErrors,
      )
      .toEqual([]);
    expect(
      await page.evaluate(
        async () => (await navigator.serviceWorker.getRegistrations()).length,
      ),
    ).toBe(0);
    expect(await page.evaluate(async () => await caches.keys())).toEqual([]);
    await session.detach();
  } finally {
    await context.close();
    await rm(profile, { recursive: true, force: true });
  }
});

test("installation assets are public and the PNG icons have their declared sizes", async ({
  request,
}) => {
  const response = await request.get("/manifest.webmanifest");
  expect(response.ok()).toBe(true);
  expect(response.headers()["content-type"]).toContain(
    "application/manifest+json",
  );
  const manifest = await response.json();
  expect(manifest.icons.map((icon: { sizes: string }) => icon.sizes)).toEqual([
    "192x192",
    "512x512",
  ]);
  for (const icon of manifest.icons) {
    const image = await request.get(icon.src);
    expect(image.ok()).toBe(true);
    expect(image.headers()["content-type"]).toContain("image/png");
    const bytes = await image.body();
    expect(bytes.subarray(0, 8).toString("hex")).toBe("89504e470d0a1a0a");
    const size = Number(icon.sizes.split("x")[0]);
    expect(bytes.readUInt32BE(16)).toBe(size);
    expect(bytes.readUInt32BE(20)).toBe(size);
  }
});

test("no offline document is served when the server is unreachable", async ({
  page,
  context,
}) => {
  await page.goto("/settings/account");
  await context.setOffline(true);
  try {
    await expect(
      page.goto("/?pwa-offline-check=1", { timeout: 5000 }),
    ).rejects.toThrow(/ERR_INTERNET_DISCONNECTED|ERR_NETWORK_CHANGED/);
  } finally {
    await context.setOffline(false);
  }
});

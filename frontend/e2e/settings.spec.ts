import { test, expect } from "./fixtures";
test.beforeEach(async ({ page }) => {
  await page.route("**/api/catalog/covers/config", (r) =>
    r.fulfill({
      json: {
        connectTimeoutMs: 3000,
        requestTimeoutMs: 3000,
        batchTimeoutMs: 4000,
        concurrency: 4,
      },
    }),
  );
  await page.route("**/api/settings/covers", (r) =>
    r.fulfill({ json: { section: "covers", fields: [] } }),
  );
  await page.route("**/api/settings/torrent", (r) =>
    r.fulfill({ json: { section: "torrent", fields: [] } }),
  );
});
test("sidebar preserves icon positions, has separators and no language selector", async ({
  page,
}) => {
  await page.goto("/settings/general");
  const sidebar = page.locator("#sidebar");
  await expect(sidebar.locator("select")).toHaveCount(0);
  const icons = sidebar.locator("nav a svg");
  await expect(icons).toHaveCount(5);
  const before = await icons.evaluateAll((nodes) =>
    nodes.map((n) => n.getBoundingClientRect().y),
  );
  await page.getByRole("button", { name: "Plegar menú lateral" }).click();
  const after = await icons.evaluateAll((nodes) =>
    nodes.map((n) => n.getBoundingClientRect().y),
  );
  expect(after).toEqual(before);
  await expect(sidebar.locator(".nav-group-label")).toHaveCount(2);
  await expect(
    sidebar.getByRole("link", { name: "Inicio", exact: true }),
  ).toHaveCount(0);
  await expect(
    sidebar.getByRole("link", { name: "General", exact: true }),
  ).toHaveCount(0);
  await page.screenshot({ path: "test-results/sidebar-collapsed-new.png" });
  await page.getByRole("button", { name: "Desplegar menú lateral" }).click();
  await expect(page.locator(".sidebar-bottom .sidebar-toggle")).toBeVisible();
  await expect(page.locator(".breadcrumbs, .topline")).toHaveCount(0);
});
test("palette changes the interface and survives navigation and reload", async ({
  page,
}) => {
  await page.goto("/settings/general");
  const favicon = page.locator('link[rel="icon"]');
  const initialIcon = await favicon.getAttribute("href");
  const previous = await page
    .locator("#sidebar")
    .evaluate((e) => getComputedStyle(e).backgroundColor);
  await page.getByRole("radio", { name: "Azul", exact: true }).check();
  await expect(page.locator("html")).toHaveAttribute("data-palette", "blue");
  expect(
    await page
      .locator("#sidebar")
      .evaluate((e) => getComputedStyle(e).backgroundColor),
  ).not.toBe(previous);
  await expect(favicon).not.toHaveAttribute("href", initialIcon!);
  const blueIcon = await favicon.getAttribute("href");
  await page.getByRole("radio", { name: "Claro", exact: true }).check();
  await expect(favicon).not.toHaveAttribute("href", blueIcon!);
  const lightIcon = await favicon.getAttribute("href");
  await page.getByRole("link", { name: "Torrent", exact: true }).click();
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-palette", "blue");
  await expect(favicon).toHaveAttribute("href", lightIcon!);
  await page.getByRole("link", { name: "General", exact: true }).click();
  await expect(
    page.getByRole("radio", { name: "Azul", exact: true }),
  ).toBeChecked();
  await page.screenshot({ path: "test-results/settings-palette.png" });
});
test("torrent connection is explicit and handles success, disabled and server failures", async ({
  page,
}) => {
  let calls = 0;
  let status = 200;
  let body: object = {
    enabled: true,
    connected: true,
    client: "qbittorrent",
    authMode: "credentials",
    version: "5.1.2",
    apiVersion: "2.11.4",
  };
  await page.route("**/api/settings/torrent/connection", (r) => {
    calls++;
    return r.fulfill({ status, json: body });
  });
  await page.goto("/settings/torrent");
  await expect(
    page.getByRole("heading", { name: "Ajustes", exact: true }),
  ).toBeVisible();
  expect(calls).toBe(0);
  await page.getByRole("button", { name: "Comprobar conexión" }).click();
  await expect(page.locator(".notification-toasts")).toContainText(
    "Conexión correcta",
  );
  await expect(page.getByText("5.1.2", { exact: true })).toBeVisible();
  await page.screenshot({ path: "test-results/torrent-connected.png" });
  body = {
    enabled: false,
    connected: false,
    client: "qbittorrent",
    authMode: null,
    version: null,
    apiVersion: null,
  };
  await page.getByRole("button", { name: "Comprobar conexión" }).click();
  await expect(page.locator(".notification-toasts")).toContainText(
    "desactivada",
  );
  await expect(page.getByText("5.1.2", { exact: true })).toHaveCount(0);
  status = 502;
  body = { details: "El cliente torrent ha rechazado la autenticación" };
  await page.getByRole("button", { name: "Comprobar conexión" }).click();
  await expect(page.locator(".notification-toasts")).toContainText(
    "rechazado la autenticación",
  );
  status = 504;
  body = {};
  await page.getByRole("button", { name: "Comprobar conexión" }).click();
  await expect(page.locator(".notification-toasts")).toContainText(
    "tiempo de espera",
  );
  expect(calls).toBe(4);
});
test("legacy database URL redirects and settings work on mobile", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/maintenance/catalog");
  await expect(page).toHaveURL(/\/settings\/catalog$/);
  await page.getByRole("button", { name: "Abrir menú" }).click();
  await page
    .locator("#sidebar")
    .getByRole("link", { name: "Ajustes", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Abrir menú" }),
  ).toHaveAttribute("aria-expanded", "false");
  await page.getByRole("radio", { name: "Violeta", exact: true }).check();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(
    390,
  );
  await page.screenshot({
    path: "test-results/settings-mobile-new.png",
    fullPage: true,
    animations: "disabled",
  });
});

test("connection check uses edited form values while the integration is disabled without saving", async ({
  page,
}) => {
  const calls: Record<string, unknown>[] = [];
  let saves = 0;
  await page.route("**/api/settings/torrent", (route) => {
    if (route.request().method() !== "GET") saves++;
    return route.fulfill({
      json: {
        section: "torrent",
        fields: [
          { key: "torrent.enabled", type: "boolean", value: false },
          {
            key: "torrent.base-url",
            type: "text",
            value: "http://old-client:8080",
          },
          {
            key: "torrent.qbittorrent.auth.api-key",
            type: "secret",
            value: "",
            configured: true,
          },
        ],
      },
    });
  });
  await page.route("**/api/settings/torrent/connection", (route) => {
    expect(route.request().method()).toBe("POST");
    calls.push(route.request().postDataJSON());
    return route.fulfill({
      json: {
        enabled: false,
        connected: true,
        client: "qbittorrent",
        authMode: "api-key",
        version: "5.2.4",
        apiVersion: "2.15.1",
      },
    });
  });
  await page.goto("/settings/torrent");
  await page
    .getByLabel("URL del cliente", { exact: true })
    .fill("http://new-client:8080");
  await page.getByLabel("API key", { exact: true }).fill("candidate-key");
  await page
    .getByRole("button", { name: "Comprobar conexión", exact: true })
    .click();
  await expect(page.locator(".connection-result")).toContainText("5.2.4");
  expect(calls).toEqual([
    {
      "torrent.base-url": "http://new-client:8080",
      "torrent.qbittorrent.auth.api-key": "candidate-key",
      "torrent.qbittorrent.auth.username": "",
    },
  ]);
  await expect(
    page.getByLabel("Habilitar integración torrent"),
  ).not.toBeChecked();
  expect(saves).toBe(0);
});

import { test, expect } from "@playwright/test";
test.beforeEach(async ({ page }) => {
  await page.route("**/api/ui/config", (r) =>
    r.fulfill({ json: { defaultLanguage: "es" } }),
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
  await page.getByRole("link", { name: "Torrent", exact: true }).click();
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-palette", "blue");
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
  await page.route("**/api/torrent/client/connection", (r) => {
    calls++;
    return r.fulfill({ status, json: body });
  });
  await page.goto("/settings/torrent");
  await expect(
    page.getByRole("heading", { name: "Ajustes", exact: true }),
  ).toBeVisible();
  expect(calls).toBe(0);
  await page.getByRole("button", { name: "Comprobar conexión" }).click();
  await expect(page.getByRole("alert")).toContainText("Conexión correcta");
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
  await expect(page.getByRole("alert")).toContainText("desactivada");
  await expect(page.getByText("5.1.2", { exact: true })).toHaveCount(0);
  status = 502;
  body = { details: "El cliente torrent ha rechazado la autenticación" };
  await page.getByRole("button", { name: "Comprobar conexión" }).click();
  await expect(page.getByRole("alert")).toContainText(
    "rechazado la autenticación",
  );
  status = 504;
  body = {};
  await page.getByRole("button", { name: "Comprobar conexión" }).click();
  await expect(page.getByRole("alert")).toContainText("tiempo de espera");
  expect(calls).toBe(4);
});
test("legacy database URL redirects and settings work on mobile", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/maintenance/catalog");
  await expect(page).toHaveURL(/\/settings\/database$/);
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

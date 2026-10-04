import { test, expect } from "./fixtures";

async function setup(page: import("@playwright/test").Page, empty = false) {
  await page.route("**/api/ui/config", (r) =>
    r.fulfill({ json: { defaultLanguage: "es" } }),
  );
  await page.route("**/api/catalog/books?**", (r) =>
    r.fulfill({
      json: {
        items: empty
          ? []
          : Array.from({ length: 10 }, (_, i) => ({
              eplId: i + 1,
              title: `Libro reciente ${i + 1}`,
              author: "Autora de prueba",
              coverUrl: "invalid",
              coverAvailable: null,
            })),
        meta: {
          page: 0,
          size: 10,
          totalItems: empty ? 0 : 73726,
          totalPages: 7373,
        },
      },
    }),
  );
  await page.route("**/api/catalog/import/metadata", (r) =>
    r.fulfill({
      json: {
        metadata: empty ? null : { sourceModifiedAt: "2026-10-04T04:00:00" },
      },
    }),
  );
  await page.route("**/api/torrent/downloads/summary", (r) =>
    r.fulfill({ json: { total: 12, byStatus: { DOWNLOADED: 10, QUEUED: 2 } } }),
  );
  await page.route("**/api/torrent/jobs?**", (r) =>
    r.fulfill({
      json: {
        items: [],
        meta: {
          totalItems:
            !empty &&
            new URL(r.request().url()).searchParams.get("status") === "RUNNING"
              ? 2
              : 0,
        },
      },
    }),
  );
  await page.route("**/api/events/operations?**", (r) =>
    r.fulfill({
      json: {
        items: empty
          ? []
          : [
              {
                latest: {
                  operationId: "op-1",
                  action: "UPDATE",
                  outcome: "SUCCEEDED",
                  createdAt: "2026-10-04T12:00:00Z",
                },
              },
            ],
        total: empty ? 0 : 1,
      },
    }),
  );
}

test("home presents local summaries, recent books and grouped event links", async ({
  page,
}) => {
  await setup(page);
  const requests: string[] = [];
  page.on("request", (r) => {
    if (r.url().includes("/api/")) requests.push(r.url());
  });
  await page.goto("/");
  await expect(page.getByText("73726", { exact: true })).toBeVisible();
  await expect(
    page.getByText("2026-10-04 04:00:00", { exact: false }),
  ).toBeVisible();
  await expect(page.locator(".home-book")).toHaveCount(10);
  await expect(page.locator(".home-events a")).toHaveAttribute(
    "href",
    "/events?operationId=op-1",
  );
  await expect(
    page.locator(".home-stats article").nth(2).locator(".home-count"),
  ).toHaveText("2");
  expect(
    requests.some(
      (url) => url.includes("/torrent/client/") || url.includes("/sync"),
    ),
  ).toBe(false);
  await page.screenshot({
    path: "/tmp/eplsync-home-desktop.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(400); // Let the sidebar resize transition finish before visual inspection.
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "/tmp/eplsync-home-mobile.png",
    fullPage: true,
  });
});

test("empty home offers import without an empty book shelf", async ({
  page,
}) => {
  await setup(page, true);
  await page.goto("/");
  await expect(
    page.getByRole("link", { name: "Importar catálogo" }),
  ).toHaveAttribute("href", "/settings/database");
  await expect(page.getByText("No hay trabajos activos.")).toBeVisible();
  await expect(
    page.getByText("Todavía no hay eventos registrados."),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Últimos libros añadidos" }),
  ).toHaveCount(0);
});

test("a failed summary leaves the remaining home sections usable", async ({
  page,
}) => {
  await setup(page);
  await page.route("**/api/torrent/downloads/summary", (r) =>
    r.fulfill({ status: 503, json: {} }),
  );
  await page.goto("/");
  await expect(
    page
      .locator(".home-stats article")
      .nth(1)
      .getByRole("button", { name: "Reintentar" }),
  ).toBeVisible({ timeout: 15000 });
  await expect(page.locator(".home-book")).toHaveCount(10);
  await expect(page.locator(".home-events a")).toBeVisible();
});

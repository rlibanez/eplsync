import { test, expect } from "./fixtures";

test("Calibre updates compare the snapshot and refresh it without sending files", async ({
  page,
}) => {
  await page.route("**/api/settings/calibre/snapshot", (r) =>
    r.fulfill({
      json: {
        snapshot: {
          library: "main",
          syncedAt: "2026-10-13T12:00:00Z",
          total: 1,
          identified: 1,
        },
      },
    }),
  );
  await page.route("**/api/settings/calibre/sync", (r) =>
    r.fulfill({
      json: {
        library: "main",
        syncedAt: "2026-10-13T13:00:00Z",
        total: 1,
        identified: 1,
      },
    }),
  );
  await page.route("**/api/calibre/books?**", (r) => {
    expect(new URL(r.request().url()).searchParams.get("updatesOnly")).toBe(
      "true",
    );
    return r.fulfill({
      json: {
        items: [
          {
            calibreId: 2,
            eplId: 1,
            title: "Libro antiguo",
            authors: "Autor",
            revision: 1.2,
            catalogRevision: 2,
            comparison: "OUTDATED",
            presence: "PRESENT",
            coverAvailable: false,
          },
        ],
        meta: {
          page: 0,
          size: 20,
          totalItems: 1,
          totalPages: 1,
          hasNext: false,
          hasPrevious: false,
        },
      },
    });
  });
  await page.goto("/calibre/updates");
  await expect(
    page.getByRole("heading", { name: "Actualizaciones de Calibre" }),
  ).toBeVisible();
  await expect(page.locator("tbody")).toContainText("Libro antiguo");
  await expect(page.getByLabel("Comparación", { exact: true })).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Descargar EPUB", exact: true }),
  ).toHaveCount(0);
  const sync = page.waitForRequest(
    (r) =>
      r.url().endsWith("/api/settings/calibre/sync") && r.method() === "POST",
  );
  await page
    .getByRole("button", { name: "Buscar actualizaciones", exact: true })
    .click();
  await sync;
  await page.reload();
  await expect(page.locator("tbody")).toContainText("Libro antiguo");
});

test("Downloads state opens the existing update search dialog", async ({
  page,
}) => {
  await page.route("**/api/torrent/downloads?**", (r) =>
    r.fulfill({
      json: {
        items: [],
        meta: {
          page: 0,
          size: 20,
          totalItems: 0,
          totalPages: 0,
          hasNext: false,
          hasPrevious: false,
        },
      },
    }),
  );
  await page.route("**/api/settings/downloads/updates", (r) =>
    r.fulfill({ json: { states: ["DOWNLOADED"] } }),
  );
  await page.goto("/downloads");
  await page
    .getByRole("link", { name: "Buscar actualizaciones", exact: true })
    .click();
  await expect(page).toHaveURL(/\/downloads\/updates/);
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(
    page
      .getByRole("dialog")
      .getByRole("button", { name: "Buscar", exact: true }),
  ).toBeVisible();
});

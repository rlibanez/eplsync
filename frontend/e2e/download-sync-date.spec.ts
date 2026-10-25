import { test, expect } from "./fixtures";
test("download state displays the saved full synchronization date after reload", async ({
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
  await page.route("**/api/torrent/downloads/sync-state", (r) =>
    r.fulfill({ json: { syncedAt: "2026-10-14T10:04:37Z" } }),
  );
  await page.goto("/downloads");
  const date = page.getByText(/Última sincronización:/);
  await expect(date).toContainText("14 oct 2026");
  await expect(date).toContainText(/\d{2}:04:37/);
  await expect(date).toHaveClass("muted");
  await page.reload();
  await expect(date).toContainText("14 oct 2026");
  await expect(date).toContainText(/\d{2}:04:37/);
});

test("Calibre displays synchronization seconds", async ({ page }) => {
  await page.route("**/api/calibre/books?**", (r) => r.fulfill({ json: {
    items: [], meta: { page: 0, size: 20, totalItems: 0, totalPages: 0, hasNext: false, hasPrevious: false },
  } }));
  await page.route("**/api/settings/calibre/snapshot", (r) => r.fulfill({ json: {
    snapshot: { library: "main", syncedAt: "2026-10-14T10:04:37Z", total: 0, identified: 0 },
  } }));
  await page.goto("/calibre");
  await expect(page.getByText(/Última sincronización:/)).toContainText(/\d{2}:04:37/);
});

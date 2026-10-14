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
    r.fulfill({ json: { syncedAt: "2026-10-14T10:00:00Z" } }),
  );
  await page.goto("/downloads");
  const date = page.getByText(/Última sincronización:/);
  await expect(date).toContainText("14 oct 2026");
  await expect(date).toHaveClass("muted");
  await page.reload();
  await expect(date).toContainText("14 oct 2026");
});

import { test, expect } from "./fixtures";
for (const permissions of [
  ["CATALOG_READ", "BOOK_HISTORY_READ", "CALIBRE_READ"],
  ["CATALOG_READ"],
]) {
  test(`library badges in mosaic and detail: ${permissions.length} permissions`, async ({
    page,
  }) => {
    const book = {
      eplId: 1677,
      title: "1984",
      author: "George Orwell",
      revision: 3.3,
      download: {
        items: [
          { id: "old", revision: 2, status: "DOWNLOADED", completed: true },
        ],
        latest: {
          id: "new",
          revision: 3.3,
          status: "DOWNLOADING",
          completed: false,
        },
      },
      calibre: {
        library: "main",
        totalItems: 2,
        revision: 3.2,
        items: [{ calibreId: 1, revision: 3.2 }],
      },
    };
    await page.route("**/api/auth/me", (r) =>
      r.fulfill({
        json: { id: "reader", username: "reader", role: "USER", permissions },
      }),
    );
    await page.route("**/api/catalog/books?**", (r) =>
      r.fulfill({
        json: {
          items: [
            book,
            {
              ...book,
              eplId: 2,
              title: "Sin revisión",
              download: {
                items: [],
                latest: { ...book.download.latest, revision: null },
              },
              calibre: { ...book.calibre, revision: null },
            },
            {
              ...book,
              eplId: 3,
              title: "Ausente",
              download: { items: [], latest: null },
              calibre: null,
            },
          ],
          meta: {
            page: 0,
            size: 20,
            totalItems: 3,
            totalPages: 1,
            hasNext: false,
            hasPrevious: false,
          },
        },
      }),
    );
    await page.route("**/api/catalog/books/1677", (r) =>
      r.fulfill({ json: book }),
    );
    await page.route("**/api/catalog/books/1677/magnets", (r) =>
      r.fulfill({ json: [] }),
    );
    await page.route("**/api/calibre/books?**", (r) =>
      r.fulfill({
        json: {
          items: [],
          meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
        },
      }),
    );
    await page.addInitScript(() =>
      localStorage.setItem("eplsync.catalog.view", "mosaic"),
    );
    await page.goto("/catalog");
    const badges = page
      .locator(".catalog-card")
      .first()
      .locator(".catalog-library-badges");
    if (permissions.length > 1) {
      await expect(badges).toContainText("Descargando · r3.3");
      await expect(badges).toContainText("Calibre · r3.2");
      await expect(badges).not.toContainText("Descargado");
      const unknown = page
        .locator(".catalog-card")
        .nth(1)
        .locator(".catalog-library-badges");
      await expect(unknown).toContainText("Descargando");
      await expect(unknown).not.toContainText(" · r");
      await expect(
        page.locator(".catalog-card").nth(2).locator(".catalog-library-badges"),
      ).toHaveCount(0);
      await page.screenshot({
        path: "test-results/catalog-library-badges.png",
      });
    } else await expect(page.locator(".catalog-library-badges")).toHaveCount(0);
    await page.goto("/catalog/1677");
    const detail = page.locator(".detail-heading .catalog-library-badges");
    if (permissions.length > 1) {
      await expect(detail).toContainText("Descargando · r3.3");
      await expect(detail).toContainText("Calibre · r3.2");
      await page.screenshot({ path: "test-results/book-library-badges.png" });
    } else await expect(detail).toHaveCount(0);
  });
}

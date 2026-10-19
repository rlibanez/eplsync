import { test, expect } from "./fixtures";
for (const calibre of [false, true]) {
  for (const mobile of [false, true]) {
    test(`selection does not move ${calibre ? "Calibre" : "download"} rows on ${mobile ? "mobile" : "desktop"}`, async ({
      page,
    }) => {
      await page.setViewportSize(
        mobile ? { width: 390, height: 844 } : { width: 1440, height: 1000 },
      );
      await page.route("**/api/settings/calibre/snapshot", (r) =>
        r.fulfill({ json: { snapshot: null } }),
      );
      await page.route(
        `**/api/${calibre ? "calibre/books" : "torrent/downloads"}?**`,
        (r) =>
          r.fulfill({
            json: {
              items: [1, 2].map((id) =>
                calibre
                  ? {
                      calibreId: id,
                      eplId: id,
                      title: `Libro ${id}`,
                      authors: "Autor",
                      revision: 1,
                      catalogRevision: 1,
                      presence: "PRESENT",
                      comparison: "CURRENT",
                      coverAvailable: false,
                    }
                  : {
                      id: String(id),
                      eplId: id,
                      title: `Libro ${id}`,
                      hash: "A".repeat(40),
                      revision: 1,
                      status: "DOWNLOADED",
                      client: "qbittorrent",
                      origin: "EPLSYNC",
                      coverAvailable: false,
                    },
              ),
              meta: {
                page: 0,
                size: 20,
                totalItems: 2,
                totalPages: 1,
                hasNext: false,
                hasPrevious: false,
              },
            },
          }),
      );
      await page.goto(calibre ? "/calibre" : "/downloads");
      await expect(page.locator("tbody tr")).toHaveCount(2);
      const rowDelete = page
        .locator("tbody")
        .getByRole("button", { name: "Eliminar", exact: true })
        .first();
      await expect(rowDelete.locator("svg")).toHaveAttribute("width", "16");
      const redChannels = await rowDelete.evaluate((el) =>
        getComputedStyle(el)
          .color.match(/[\d.]+/g)!
          .map(Number),
      );
      expect(redChannels[0]).toBeGreaterThan(redChannels[1]);
      expect(redChannels[0]).toBeGreaterThan(redChannels[2]);
      const table = page.locator("table");
      const top = () =>
        table.evaluate((el) => el.getBoundingClientRect().top + window.scrollY);
      const y = await top();
      const controlsTop = () =>
        page
          .getByRole("button", { name: "Columnas", exact: true })
          .evaluate((el) => el.getBoundingClientRect().top + window.scrollY);
      const controlsY = await controlsTop();
      await page.locator("tbody input[type=checkbox]").first().check();
      await expect(
        page.getByRole("button", { name: "Quitar selección (1)", exact: true }),
      ).toBeVisible();
      const selectionDelete = page
        .locator(".selection-toolbar-actions")
        .getByRole("button", { name: "Eliminar", exact: true });
      await expect(selectionDelete.locator("svg")).toHaveAttribute(
        "width",
        "16",
      );
      const selectionChannels = await selectionDelete.evaluate((el) =>
        getComputedStyle(el)
          .color.match(/[\d.]+/g)!
          .map(Number),
      );
      expect(selectionChannels[0]).toBeGreaterThan(selectionChannels[1]);
      expect(selectionChannels[0]).toBeGreaterThan(selectionChannels[2]);
      expect(await top()).toBe(y);
      expect(await controlsTop()).toBe(controlsY);
      await page.locator("tbody input[type=checkbox]").last().check();
      await expect(
        page.getByRole("button", { name: "Quitar selección (2)", exact: true }),
      ).toBeVisible();
      expect(await top()).toBe(y);
      await page.locator(".selection-table-toolbar").screenshot({
        path: `/tmp/selection-toolbar-${calibre ? "calibre" : "downloads"}-${mobile ? "mobile" : "desktop"}.png`,
      });
      await page
        .getByRole("button", { name: "Quitar selección (2)", exact: true })
        .click();
      await expect(page.locator("tbody input:checked")).toHaveCount(0);
      expect(await top()).toBe(y);
    });
  }
}

import { test, expect } from "./fixtures";

for (const view of ["downloads", "calibre", "catalog"] as const) {
  test(`${view}: page header adds and removes only the visible rows`, async ({
    page,
  }) => {
    await page.route("**/api/settings/calibre/snapshot", (route) =>
      route.fulfill({ json: { snapshot: null } }),
    );
    const endpoint =
      view === "downloads"
        ? "torrent/downloads"
        : view === "calibre"
          ? "calibre/books"
          : "catalog/books";
    await page.route(`**/api/${endpoint}?**`, (route) => {
      const index = Number(
        new URL(route.request().url()).searchParams.get("page") ?? 0,
      );
      const items = [1, 2].map((n) => {
        const id = index * 2 + n;
        return {
          id: String(id),
          calibreId: id,
          eplId: id,
          title: `Libro ${id}`,
          author: "Autor",
          authors: "Autor",
          revision: 1,
          catalogRevision: 1,
          comparison: "CURRENT",
          presence: "PRESENT",
          download: { items: [] },
          status: "DOWNLOADED",
          hash: String(id).repeat(40),
          client: "qbittorrent",
          origin: "EPLSYNC",
          coverAvailable: false,
        };
      });
      return route.fulfill({
        json: {
          items,
          meta: {
            page: index,
            size: 20,
            totalItems: 6,
            totalPages: 3,
            hasNext: index < 2,
            hasPrevious: index > 0,
          },
        },
      });
    });
    await page.goto(`/${view}`);
    const header = page.locator("thead input[type=checkbox]");
    const rows = page.locator("tbody input[type=checkbox]");
    const next = page.getByRole("button", { name: "Siguiente", exact: true });
    const previous = page.getByRole("button", {
      name: "Anterior",
      exact: true,
    });
    await next.click();
    await expect(page.locator("tbody")).toContainText("Libro 3");
    await next.click();
    await expect(page.locator("tbody")).toContainText("Libro 5");
    await header.check();
    await previous.click();
    await expect(page.locator("tbody")).toContainText("Libro 3");
    await previous.click();
    await expect(page.locator("tbody")).toContainText("Libro 1");
    await expect(header).not.toBeChecked();
    await expect(header).toHaveJSProperty("indeterminate", false);
    await rows.first().check();
    await expect(header).toHaveJSProperty("indeterminate", true);
    await header.check();
    await expect(
      page.getByRole("button", { name: "Quitar selección (4)", exact: true }),
    ).toBeVisible();
    await header.uncheck();
    await expect(
      page.getByRole("button", { name: "Quitar selección (2)", exact: true }),
    ).toBeVisible();
    await next.click();
    await expect(page.locator("tbody")).toContainText("Libro 3");
    await next.click();
    await expect(page.locator("tbody")).toContainText("Libro 5");
    await expect(header).toBeChecked();
    await expect(rows.nth(0)).toBeChecked();
    await expect(rows.nth(1)).toBeChecked();
    await header.uncheck();
    await expect(
      page.getByRole("button", { name: /^Quitar selección/ }),
    ).toHaveCount(0);
  });
}

for (const calibre of [false, true]) {
  test(`book ${calibre ? "Calibre" : "download"} history retains selections between pages`, async ({
    page,
  }) => {
    await page.route("**/api/catalog/books/1", (r) =>
      r.fulfill({
        json: {
          eplId: 1,
          title: "Libro",
          author: "Autor",
          revision: 1,
          language: "es",
          coverAvailable: false,
          download: { items: [], totalItems: 4, statuses: [] },
          calibre: calibre ? { totalItems: 4, items: [] } : null,
        },
      }),
    );
    await page.route("**/api/catalog/books/1/magnets", (r) =>
      r.fulfill({ json: [] }),
    );
    const endpoint = calibre ? "calibre/books" : "catalog/books/1/history";
    await page.route(`**/api/${endpoint}?**`, (r) => {
      const index = Number(
        new URL(r.request().url()).searchParams.get("page") ?? 0,
      );
      return r.fulfill({
        json: {
          items: [1, 2].map((n) => ({
            id: String(index * 2 + n),
            calibreId: index * 2 + n,
            eplId: 1,
            revision: 100 + index * 2 + n,
            title: "Libro",
            authors: "Autor",
            status: "DOWNLOADED",
            hash: String(index * 2 + n).repeat(40),
            client: "qbittorrent",
            origin: "EPLSYNC",
            comparison: "CURRENT",
            presence: "PRESENT",
          })),
          meta: {
            page: index,
            size: 20,
            totalItems: 4,
            totalPages: 2,
            hasNext: index === 0,
            hasPrevious: index > 0,
          },
        },
      });
    });
    await page.goto("/catalog/1");
    const section = page.locator(calibre ? ".book-calibre" : ".book-history");
    const header = section.locator("thead input[type=checkbox]");
    await header.check();
    await section
      .getByRole("button", { name: "Siguiente", exact: true })
      .click();
    await expect(section.locator("tbody tr").first()).toContainText("103");
    await expect(header).not.toBeChecked();
    await expect(header).toHaveJSProperty("indeterminate", false);
    await header.check();
    await section
      .getByRole("button", { name: "Anterior", exact: true })
      .click();
    await expect(section.locator("tbody tr").first()).toContainText("101");
    await expect(header).toBeChecked();
    await header.uncheck();
    await section
      .getByRole("button", { name: "Siguiente", exact: true })
      .click();
    await expect(section.locator("tbody tr").first()).toContainText("103");
    await expect(header).toBeChecked();
  });
}

for (const source of ["downloads", "calibre"] as const) {
  for (const individual of [false, true]) {
    test(`${source} ${individual ? "job items" : "job list"}: page selections accumulate`, async ({
      page,
    }) => {
      const base =
        source === "downloads" ? "torrent/jobs" : "calibre/transfers";
      await page.route(`**/api/${base}/job`, (r) =>
        r.fulfill({
          json: {
            id: "job",
            jobId: "job",
            state: "COMPLETED",
            status: "COMPLETED",
            type: "DOWNLOAD",
            library: "main",
            client: "qbittorrent",
            createdAt: "2026-10-17T00:00:00Z",
            updatedAt: "2026-10-17T00:00:00Z",
            total: 4,
            selectedItems: 4,
            processedItems: 4,
            selectedBooks: 4,
            accepted: 4,
            failed: 0,
            pending: 0,
            inFlight: 0,
            batchSize: 100,
            concurrency: 5,
            interval: "0ms",
            skipped: 0,
            uncertain: 0,
            alreadyExists: 0,
            cancelled: 0,
          },
        }),
      );
      await page.route(`**/api/job-retries/${source}/job`, (r) =>
        r.fulfill({
          json: {
            terminal: true,
            source: null,
            retries: [],
            failed: 0,
            reason: null,
          },
        }),
      );
      const endpoint = individual ? `${base}/job/items` : base;
      await page.route(`**/api/${endpoint}?**`, (r) => {
        const index = Number(
          new URL(r.request().url()).searchParams.get("page") ?? 0,
        );
        return r.fulfill({
          json: {
            items: [1, 2].map((n) => {
              const id = index * 2 + n;
              return {
                id: String(id),
                jobId: String(id),
                eplId: id,
                position: id,
                title: `Libro ${id}`,
                name: `Libro ${id}`,
                hash: String(id).repeat(40),
                revision: 1,
                type: source === "downloads" ? "DOWNLOAD" : "SEND",
                state: individual ? "ACCEPTED" : "COMPLETED",
                status: individual ? "ACCEPTED" : "COMPLETED",
                createdAt: "2026-10-17T00:00:00Z",
                library: "main",
                total: 1,
                selectedBooks: 1,
                selectedItems: 1,
                processedItems: 1,
                accepted: 1,
                failed: 0,
                attempts: 1,
              };
            }),
            meta: {
              page: index,
              size: 20,
              totalItems: 4,
              totalPages: 2,
              hasNext: index === 0,
              hasPrevious: index > 0,
            },
          },
        });
      });
      await page.goto(`/${source}/jobs${individual ? "/job" : ""}`);
      const header = page.locator("thead input[type=checkbox]");
      const first = page.locator("tbody input[type=checkbox]").first();
      await header.check();
      await page
        .getByRole("button", { name: "Siguiente", exact: true })
        .click();
      await expect(first).toHaveAttribute(
        "aria-label",
        individual ? "EPL 3" : /3$/,
      );
      await expect(header).not.toBeChecked();
      await expect(header).toHaveJSProperty("indeterminate", false);
      await first.check();
      await expect(header).toHaveJSProperty("indeterminate", true);
      await header.check();
      await page.getByRole("button", { name: "Anterior", exact: true }).click();
      await expect(first).toHaveAttribute(
        "aria-label",
        individual ? "EPL 1" : /1$/,
      );
      await expect(header).toBeChecked();
      await header.uncheck();
      await page
        .getByRole("button", { name: "Siguiente", exact: true })
        .click();
      await expect(first).toHaveAttribute(
        "aria-label",
        individual ? "EPL 3" : /3$/,
      );
      await expect(header).toBeChecked();
    });
  }
}

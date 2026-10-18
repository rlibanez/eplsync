import { test, expect } from "./fixtures";

for (const view of [
  "downloads",
  "calibre",
  "catalog",
  "downloads/jobs",
  "calibre/jobs",
  "downloads/jobs/job",
  "calibre/jobs/job",
] as const) {
  test(`${view}: Shift selects/deselects ranges without affecting other pages`, async ({
    page,
  }) => {
    const job = view.includes("jobs"),
      individual = view.endsWith("/job"),
      calibre = view.startsWith("calibre");
    const base = job
      ? calibre
        ? "calibre/transfers"
        : "torrent/jobs"
      : view === "catalog"
        ? "catalog/books"
        : calibre
          ? "calibre/books"
          : "torrent/downloads";
    await page.route("**/api/settings/calibre/snapshot", (r) =>
      r.fulfill({ json: { snapshot: null } }),
    );
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
          total: 8,
          selectedItems: 8,
          processedItems: 8,
          selectedBooks: 8,
          accepted: 8,
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
    await page.route(
      `**/api/job-retries/${calibre ? "calibre" : "downloads"}/job`,
      (r) =>
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
          items: [1, 2, 3, 4].map((n) => {
            const id = index * 4 + n;
            return {
              id: String(id),
              jobId: String(id),
              calibreId: id,
              eplId: id,
              position: id,
              title: `Libro ${id}`,
              name: `Libro ${id}`,
              author: "Autor",
              authors: "Autor",
              hash: String(id).repeat(40),
              revision: 1,
              catalogRevision: 1,
              comparison: "CURRENT",
              presence: "PRESENT",
              download: { items: [] },
              coverAvailable: false,
              type: calibre ? "SEND" : "DOWNLOAD",
              state: individual ? "ACCEPTED" : "COMPLETED",
              status: job
                ? individual
                  ? "ACCEPTED"
                  : "COMPLETED"
                : "DOWNLOADED",
              client: "qbittorrent",
              origin: "EPLSYNC",
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
            totalItems: 8,
            totalPages: 2,
            hasNext: index === 0,
            hasPrevious: index > 0,
          },
        },
      });
    });
    await page.goto(`/${view}`);
    const rows = page.locator("tbody input[type=checkbox]"),
      header = page.locator("thead input[type=checkbox]");
    const next = page.getByRole("button", { name: "Siguiente", exact: true }),
      previous = page.getByRole("button", { name: "Anterior", exact: true });
    const firstLabel = await rows.first().getAttribute("aria-label");
    await next.click();
    await expect(rows.first()).not.toHaveAttribute("aria-label", firstLabel!);
    await rows.nth(1).click();
    await previous.click();
    await expect(rows.first()).toHaveAttribute("aria-label", firstLabel!);
    // A Shift click on a new page has no anchor inherited from the previous page.
    await rows.nth(3).click({ modifiers: ["Shift"] });
    await expect(rows.nth(0)).not.toBeChecked();
    await expect(rows.nth(2)).not.toBeChecked();
    await expect
      .poll(() => page.evaluate(() => window.getSelection()?.toString() ?? ""))
      .toBe("");
    // Reverse range, inclusive of both ends.
    await rows.nth(0).click({ modifiers: ["Shift"] });
    await expect(header).toBeChecked();
    await expect
      .poll(() => page.evaluate(() => window.getSelection()?.toString() ?? ""))
      .toBe("");
    // Shift-uncheck removes the range, then repeating Shift-check restores it.
    await rows.nth(3).click({ modifiers: ["Shift"] });
    for (let i = 0; i < 4; i++) await expect(rows.nth(i)).not.toBeChecked();
    await rows.nth(0).click({ modifiers: ["Shift"] });
    await expect(header).toBeChecked();
    await expect
      .poll(() => page.evaluate(() => window.getSelection()?.toString() ?? ""))
      .toBe("");
    // Ordinary click still toggles only one row.
    await rows.nth(1).click();
    await expect(rows.nth(0)).toBeChecked();
    await expect(rows.nth(2)).toBeChecked();
    await next.click();
    await expect(rows.first()).not.toHaveAttribute("aria-label", firstLabel!);
    await expect(rows.nth(1)).toBeChecked();
    await expect(rows.nth(0)).not.toBeChecked();
    // Returning after a page visit without a row click also resets the old anchor.
    await previous.click();
    await expect(rows.first()).toHaveAttribute("aria-label", firstLabel!);
    await rows.nth(3).click({ modifiers: ["Shift"] });
    await expect(rows.nth(3)).not.toBeChecked();
    await expect(rows.nth(2)).toBeChecked();
    await expect(rows.nth(0)).toBeChecked();
    if (view === "downloads") {
      // Text can still be selected normally for copying.
      await page.locator("tbody .hash-text").first().dblclick();
      await expect
        .poll(() =>
          page.evaluate(() => window.getSelection()?.toString().length ?? 0),
        )
        .toBeGreaterThan(0);
    }
  });
}

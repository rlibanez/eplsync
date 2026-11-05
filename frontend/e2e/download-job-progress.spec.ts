import { test, expect } from "./fixtures";
for (const calibre of [true, false]) {
  test(`separate report phases, Calibre permission=${calibre}`, async ({
    page,
  }) => {
    if (!calibre)
      await page.route("**/api/auth/me", (r) =>
        r.fulfill({
          json: {
            id: "reader",
            username: "reader",
            role: "USER",
            mustChangePassword: false,
            permissions: ["TORRENT_JOBS_MANAGE"],
          },
        }),
      );
    const job = {
      jobId: "progress",
      status: "RUNNING",
      client: "qbittorrent",
      selectedItems: 100,
      selectedBooks: 100,
      processedItems: 78,
      accepted: 78,
      alreadyExists: 0,
      skipped: 0,
      failed: 0,
      pending: 22,
      inFlight: 0,
      cancelled: 0,
      concurrency: 5,
      batchSize: 100,
      interval: "10ms",
      multipleHashes: "ALL",
      createdAt: "2026-11-05T10:00:00Z",
      updatedAt: "2026-11-05T10:10:00Z",
      download: {
        selected: 50,
        downloaded: 6,
        pending: 44,
        attention: 0,
        ignored: 0,
        filesChecked: 5,
      },
      calibre: {
        counts: {
          total: 100,
          sent: 3,
          existing: 0,
          pending: 97,
          failed: 0,
          uncertain: 0,
          cancelled: 0,
          omitted: 0,
        },
        jobs: [],
      },
    };
    await page.route("**/api/torrent/jobs/progress", (r) =>
      r.fulfill({ json: job }),
    );
    await page.route("**/api/torrent/jobs/progress/items?**", (r) =>
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
    await page.goto("/downloads/jobs/progress");
    const summary = page
      .locator("summary")
      .filter({ hasText: "Informe del trabajo" });
    await expect(summary).toContainText("Envío 78 %");
    await expect(summary).toContainText("Descargas 12 %");
    if (calibre) await expect(summary).toContainText("Calibre 3 %");
    else await expect(summary).not.toContainText("Calibre");
    await summary.click();
    await expect(
      page.getByText("Torrents con archivos comprobados", { exact: true }),
    ).toBeVisible();
    const downloads = page
      .locator("section.job-report-group")
      .filter({
        has: page.getByRole("heading", { name: "Descarga", exact: true }),
      });
    await expect(downloads).toContainText("5 / 50");
    await expect(downloads).toContainText("12 %");
    await summary.click();
    await expect(summary).toContainText("Descargas 12 %");
  });
}

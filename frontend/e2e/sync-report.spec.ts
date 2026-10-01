import { test, expect } from "@playwright/test";
test("preview and execution show local searchable detail without repeating sync", async ({
  page,
}) => {
  await page.route("**/api/ui/config", (r) =>
    r.fulfill({ json: { defaultLanguage: "es" } }),
  );
  await page.route("**/api/catalog/covers/task", (r) =>
    r.fulfill({ json: { task: null } }),
  );
  let reads = 0;
  const calls: boolean[] = [];
  await page.route("**/api/torrent/downloads?**", (r) => {
    reads++;
    return r.fulfill({
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
    });
  });
  await page.route("**/api/torrent/downloads/summary?**", (r) =>
    r.fulfill({ json: { total: 0, byStatus: {} } }),
  );
  await page.route("**/api/torrent/downloads/sync", (r) => {
    const { dryRun, includeDetails } = r.request().postDataJSON();
    expect(includeDetails).toBe(true);
    calls.push(dryRun);
    return r.fulfill({
      json: {
        dryRun,
        applied: !dryRun,
        client: "qbittorrent",
        checkedAt: "2026-10-01T12:00:00Z",
        remote: { total: 27, matched: 25, ignored: 2 },
        records: { checked: 25, created: 25, updated: 0, unchanged: 0 },
        outcomes: { newlyCompleted: 0, notFound: 0, newlyNotFound: 0 },
        items: Array.from({ length: 25 }, (_, i) => ({
          downloadId: dryRun ? null : String(i),
          eplId: i + 1,
          title: `Libro ${i + 1}`,
          hash: String(i).padStart(40, "a"),
          action: "CREATE",
          previousStatus: null,
          resultingStatus: "DOWNLOADING",
          foundInClient: true,
          changedFields: [],
          newlyCompleted: i === 0,
          resultingCompletedAt: i === 0 ? "2026-09-29T14:25:00Z" : null,
          newlyNotFound: false,
        })),
        ignoredTorrents: [
          {
            hash: "b".repeat(40),
            name: "Torrent ajeno",
            reason: "NO_CATALOG_MATCH",
          },
          {
            hash: "c".repeat(40),
            name: "Otro torrent",
            reason: "NO_CATALOG_MATCH",
          },
        ],
      },
    });
  });
  await page.goto("/downloads");
  await expect(page.locator(".empty-list")).toBeVisible();
  const initialReads = reads;
  await page
    .getByRole("button", { name: "Previsualizar sincronización", exact: true })
    .click();
  const report = page.locator(".sync-report");
  await expect(
    report.getByText("Simulación: sin cambios guardados", { exact: true }),
  ).toBeVisible();
  expect(reads).toBe(initialReads);
  await expect(report.locator("tbody tr")).toHaveCount(20);
  await expect(
    report.locator("tbody tr").first().locator("td").last(),
  ).toContainText("Finalización detectada:");
  await expect(
    report.locator("tbody tr").first().locator("td").last(),
  ).not.toContainText("—");
  await page.route("**/api/catalog/books/1", (r) =>
    r.fulfill({
      json: {
        eplId: 1,
        title: "Libro 1",
        author: "Autor",
        revision: 1,
        language: "es",
        publicationYear: 2026,
        pages: 100,
        publicationDate: null,
        insertDate: null,
        lastModifiedDate: null,
        coverUrl: null,
        coverAvailable: false,
        download: { items: [] },
      },
    }),
  );
  await page.route("**/api/catalog/books/1/magnets", (r) =>
    r.fulfill({ json: [] }),
  );
  await page.route("https://images.epublibre.org/**", (r) => r.abort());
  await report.getByRole("link", { name: "Libro 1", exact: true }).click();
  await expect(page).toHaveURL(/catalog\/1$/);
  await page.goBack();
  await expect(report).toBeVisible();
  expect(calls).toEqual([true]);
  await report.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect(report.locator("tbody tr")).toHaveCount(5);
  await report
    .getByRole("button", { name: "Torrents ignorados", exact: true })
    .click();
  await expect(
    report.getByText("Torrent ajeno", { exact: true }),
  ).toBeVisible();
  await report.getByLabel("Buscar", { exact: true }).fill("ajeno");
  await expect(report.locator("tbody tr")).toHaveCount(1);
  await page
    .locator("#sidebar")
    .getByRole("link", { name: "Ajustes", exact: true })
    .click();
  await page
    .locator("#sidebar")
    .getByRole("link", { name: "Estado", exact: true })
    .click();
  await expect(report.getByLabel("Buscar", { exact: true })).toHaveValue(
    "ajeno",
  );
  await expect(report.locator("tbody tr")).toHaveCount(1);
  expect(calls).toEqual([true]);
  await page
    .getByRole("button", { name: "Sincronizar con el cliente", exact: true })
    .click();
  await expect(
    report.getByText("Cambios aplicados", { exact: true }),
  ).toBeVisible();
  expect(calls).toEqual([true, false]);
  await expect.poll(() => reads).toBe(initialReads + 1);
  await report
    .getByRole("button", {
      name: "Cerrar resultado de la sincronización",
      exact: true,
    })
    .click();
  await expect(report).toHaveCount(0);
  await page
    .locator("#sidebar")
    .getByRole("link", { name: "Ajustes", exact: true })
    .click();
  await page
    .locator("#sidebar")
    .getByRole("link", { name: "Estado", exact: true })
    .click();
  await expect(report).toHaveCount(0);
  expect(calls).toEqual([true, false]);
});

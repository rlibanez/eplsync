import { test, expect } from "./fixtures";
const meta = {
  page: 0,
  size: 20,
  totalItems: 40,
  totalPages: 2,
  hasNext: true,
  hasPrevious: false,
};
const book = {
  eplId: 23,
  title: "Libro de retorno",
  author: "Autora",
  revision: 1,
  language: "es",
  download: { items: [] },
};
test.beforeEach(async ({ page }) => {
  await page.route("**/api/catalog/books/23/magnets", (r) =>
    r.fulfill({ json: [] }),
  );
  await page.route("**/api/settings/calibre/snapshot", (r) =>
    r.fulfill({ json: { snapshot: null } }),
  );

  await page.route("**/api/catalog/books/23", (r) => r.fulfill({ json: book }));
});
for (const kind of ["downloads", "calibre"] as const)
  test(`returns to ${kind} and restores local filters and page after reload`, async ({
    page,
  }) => {
    const requests: URL[] = [];
    await page.route(
      `**/api/${kind === "downloads" ? "torrent/downloads" : "calibre/books"}?**`,
      (r) => {
        const url = new URL(r.request().url());
        requests.push(url);
        const row =
          kind === "downloads"
            ? {
                ...book,
                id: "record",
                hash: "A".repeat(40),
                status: "DOWNLOADED",
                client: "qbittorrent",
                origin: "EPLSYNC",
              }
            : {
                ...book,
                calibreId: 3,
                authors: "Autora",
                catalogRevision: 1,
                comparison: "UPDATED",
                presence: "PRESENT",
              };
        return r.fulfill({
          json: {
            items: [row],
            meta: { ...meta, page: Number(url.searchParams.get("page") ?? 0) },
          },
        });
      },
    );
    await page.goto(`/${kind}`);
    await page.getByLabel("EPL ID", { exact: true }).fill("23");
    await expect
      .poll(() => requests.at(-1)?.searchParams.get("eplId"))
      .toBe("23");
    await page.getByRole("button", { name: "Siguiente", exact: true }).click();
    await expect
      .poll(() => requests.at(-1)?.searchParams.get("page"))
      .toBe("1");
    await page.getByRole("link", { name: book.title, exact: true }).click();
    await expect(page).toHaveURL(/\/catalog\/23$/);
    const label =
      kind === "downloads"
        ? "Volver a estado de las descargas"
        : "Volver a biblioteca de Calibre";
    await expect(
      page.getByRole("link", { name: label, exact: true }),
    ).toHaveAttribute("href", `/${kind}`);
    await page.reload();
    await page.getByRole("link", { name: label, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/${kind}$`));
    await expect(page.getByLabel("EPL ID", { exact: true })).toHaveValue("23");
    await expect
      .poll(() => requests.at(-1)?.searchParams.get("page"))
      .toBe("1");
  });

for (const kind of ["downloads", "calibre"] as const)
  test(`book returns to its ${kind} job`, async ({ page }) => {
    const endpoint =
      kind === "downloads" ? "torrent/jobs" : "calibre/transfers";
    await page.route(`**/api/${endpoint}/origin-job`, (r) =>
      r.fulfill({
        json:
          kind === "downloads"
            ? {
                jobId: "origin-job",
                status: "COMPLETED",
                type: "DOWNLOAD",
                selectedItems: 1,
                processedItems: 1,
                selectedBooks: 1,
                accepted: 1,
                failed: 0,
                alreadyExists: 0,
                skipped: 0,
                pending: 0,
                inFlight: 0,
                cancelled: 0,
                batchSize: 100,
                concurrency: 1,
                interval: "0ms",
                multipleHashes: "FIRST",
                createdAt: "2026-10-18T08:00:00Z",
                updatedAt: "2026-10-18T08:00:01Z",
              }
            : {
                id: "origin-job",
                state: "COMPLETED",
                total: 1,
                accepted: 1,
                pending: 0,
                skipped: 0,
                failed: 0,
                uncertain: 0,
                library: "main",
                username: "owner",
                createdAt: "2026-10-18T08:00:00Z",
                updatedAt: "2026-10-18T08:00:01Z",
              },
      }),
    );
    await page.route(`**/api/${endpoint}/origin-job/items?**`, (r) =>
      r.fulfill({
        json: {
          items: [
            {
              ...book,
              id: "item",
              name: book.title,
              recordId: "record",
              position: 1,
              index: 0,
              size: 100,
              state: "ACCEPTED",
              status: "ACCEPTED",
              attempts: 1,
              hash: "A".repeat(40),
              message: null,
            },
          ],
          meta: { ...meta, totalItems: 1, totalPages: 1, hasNext: false },
        },
      }),
    );
    await page.goto(`/${kind}/jobs/origin-job`);
    await page.getByRole("link", { name: book.title, exact: true }).click();
    const label =
      kind === "downloads"
        ? "Volver a trabajo de descarga"
        : "Volver a trabajo de Calibre";
    await page.getByRole("link", { name: label, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/${kind}/jobs/origin-job$`));
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  });

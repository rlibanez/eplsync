import { test, expect } from "./fixtures";

test("sync details are fetched by page without repeating synchronization", async ({
  page,
}) => {
  let syncs = 0;
  const pages: string[] = [];
  await page.route("**/api/torrent/downloads?**", (route) =>
    route.fulfill({
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
  await page.route("**/api/torrent/downloads/summary?**", (route) =>
    route.fulfill({ json: { total: 0, byStatus: {} } }),
  );
  await page.route("**/api/torrent/downloads/sync", (route) => {
    syncs++;
    return route.fulfill({
      json: {
        detailsId: "snapshot",
        client: "qbittorrent",
        clientInstanceId: "client",
        dryRun: true,
        applied: false,
        checkedAt: "2026-10-01T12:00:00Z",
        remote: { total: 25, matched: 25, ignored: 0 },
        records: { checked: 25, created: 25, updated: 0, unchanged: 0 },
        outcomes: { newlyCompleted: 0, notFound: 0, newlyNotFound: 0 },
      },
    });
  });
  await page.route(
    "**/api/torrent/downloads/reports/snapshot/books?**",
    (route) => {
      const params = new URL(route.request().url()).searchParams;
      pages.push(params.toString());
      const pageNumber = Number(params.get("page"));
      const filtered = !!params.get("search");
      const total = filtered ? 1 : 25;
      const items = Array.from(
        { length: filtered ? 1 : pageNumber === 0 ? 20 : 5 },
        (_, index) => ({
          eplId: pageNumber * 20 + index + 1,
          title: filtered
            ? "Libro filtrado"
            : `Libro ${pageNumber * 20 + index + 1}`,
          hash: String(index).padStart(40, "a"),
          action: "CREATE",
          resultingStatus: "DOWNLOADING",
          changedFields: [],
          newlyCompleted: false,
          newlyNotFound: false,
        }),
      );
      return route.fulfill({
        json: {
          items,
          meta: {
            page: pageNumber,
            size: 20,
            totalItems: total,
            totalPages: Math.ceil(total / 20),
            hasNext: pageNumber === 0 && !filtered,
            hasPrevious: pageNumber > 0,
          },
        },
      });
    },
  );
  await page.goto("/downloads");
  await page
    .getByRole("button", { name: "Previsualizar sincronización", exact: true })
    .click();
  await page.locator(".sync-report summary").click();
  const report = page.locator(".sync-report");
  await expect(
    report.getByRole("link", { name: "Libro 1", exact: true }),
  ).toBeVisible();
  await report.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect(
    report.getByRole("link", { name: "Libro 21", exact: true }),
  ).toBeVisible();
  await report.getByLabel("Buscar", { exact: true }).fill("filtrado");
  await expect(
    report.getByRole("link", { name: "Libro filtrado", exact: true }),
  ).toBeVisible();
  expect(pages.some((value) => value.includes("page=1"))).toBe(true);
  expect(syncs).toBe(1);
});

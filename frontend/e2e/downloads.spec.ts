import { test, expect, emitEvent } from "./fixtures";
const meta = (total = 1, page = 0, size = 20) => ({
  page,
  size,
  totalItems: total,
  totalPages: Math.ceil(total / size),
  hasNext: (page + 1) * size < total,
  hasPrevious: page > 0,
});
const book = {
  eplId: 32,
  title: "Dune",
  author: "Frank Herbert",
  language: "es",
  revision: 1.2,
  genres: "Ficción",
  publicationYear: 1965,
  download: { items: [] },
};
test.beforeEach(async ({ page }) => {
  await page.route("**/api/ui/config", (r) =>
    r.fulfill({ json: { defaultLanguage: "es" } }),
  );
});
test("state reads local records and sync is explicit", async ({ page }) => {
  let syncs = 0;
  let synced = false;
  await page.route("**/api/torrent/downloads?**", (r) =>
    r.fulfill({
      json: {
        items: [
          {
            id: "d",
            eplId: 32,
            hash: "a".repeat(40),
            revision: 1.2,
            client: "qbittorrent",
            clientInstanceId: "x",
            origin: "EPLSYNC",
            status: synced ? "DOWNLOADED" : "DOWNLOADING",
            lastCheckedAt: "2026-09-30T08:00:00Z",
            completedAt: synced ? "2026-09-30T09:00:00Z" : null,
          },
        ],
        meta: meta(),
      },
    }),
  );
  await page.route("**/api/torrent/downloads/summary?**", (r) =>
    r.fulfill({
      json: {
        total: 1,
        byStatus: { DOWNLOADING: synced ? 0 : 1, DOWNLOADED: synced ? 1 : 0 },
      },
    }),
  );
  await page.route("**/api/torrent/downloads/sync", (r) => {
    expect(r.request().method()).toBe("POST");
    expect(r.request().postDataJSON()).toEqual({
      dryRun: false,
      includeDetails: true,
    });
    syncs++;
    synced = true;
    return r.fulfill({
      json: {
        checkedAt: "2026-09-30T09:00:00Z",
        client: "qbittorrent",
        dryRun: false,
        applied: true,
        remote: { total: 1, matched: 1, ignored: 0 },
        records: { checked: 1, created: 0, updated: 1, unchanged: 0 },
        outcomes: { newlyCompleted: 1, notFound: 0, newlyNotFound: 0 },
        items: [],
        ignoredTorrents: [],
      },
    });
  });
  await page.goto("/downloads");
  await expect(
    page.getByRole("cell", { name: "Descargando", exact: true }),
  ).toBeVisible();
  expect(syncs).toBe(0);
  await page
    .getByRole("button", { name: "Sincronizar con el cliente" })
    .click();
  await expect(
    page.getByText("Cambios aplicados", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("cell", { name: "Descargado", exact: true }),
  ).toBeVisible();
  expect(syncs).toBe(1);
  await page.screenshot({
    path: "test-results/download-state.png",
    fullPage: true,
  });
});
test("directory opens a filtered catalog, pagination includes 10 to 500", async ({
  page,
}) => {
  await page.route("**/api/catalog/directory/genres?**", (r) =>
    r.fulfill({ json: { items: [{ value: "Ficción" }], meta: meta() } }),
  );
  await page.route("**/api/catalog/books?**", (r) => {
    expect(new URL(r.request().url()).searchParams.get("genres")).toBe(
      "Ficción",
    );
    return r.fulfill({ json: { items: [book], meta: meta() } });
  });
  await page.goto("/directory?section=genres");
  await page.getByRole("link", { name: "Ficción", exact: true }).click();
  await expect(page).toHaveURL(/genres=Ficci/);
  await expect(
    page.getByRole("link", { name: "Dune", exact: true }),
  ).toBeVisible();
});
test("light scheme and ten palettes persist with clean settings navigation", async ({
  page,
}) => {
  await page.goto("/settings/general");
  await expect(
    page
      .locator("#sidebar")
      .getByRole("link", { name: "General", exact: true }),
  ).toHaveCount(0);
  await expect(page.locator("#sidebar")).not.toContainText("TU CATÁLOGO");
  await expect(
    page.locator(".palette-options").last().getByRole("radio"),
  ).toHaveCount(10);
  await expect(page.locator(".language-picker label")).toHaveClass("sr-only");
  await page.getByRole("radio", { name: "Claro", exact: true }).check();
  await page.getByRole("radio", { name: "Cian", exact: true }).check();
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute(
    "data-mantine-color-scheme",
    "light",
  );
  await expect(
    page.getByRole("radio", { name: "Cian", exact: true }),
  ).toBeChecked();
  await page.screenshot({
    path: "test-results/settings-light.png",
    fullPage: true,
  });
});
test("catalog page size offers all requested choices and resets page", async ({
  page,
}) => {
  await page.route("**/api/catalog/books?**", (r) => {
    const p = new URL(r.request().url()).searchParams;
    return r.fulfill({
      json: {
        items: [book],
        meta: meta(1000, Number(p.get("page")), Number(p.get("size"))),
      },
    });
  });
  await page.goto("/catalog?page=2&author=Herbert");
  await page.getByRole("textbox", { name: "Libros por página" }).click();
  for (const n of [10, 20, 50, 100, 200, 500])
    await expect(
      page.getByRole("option", { name: `${n} por página`, exact: true }),
    ).toBeVisible();
  await page
    .getByRole("option", { name: "500 por página", exact: true })
    .click();
  await expect(page).toHaveURL(/size=500/);
  await expect(page).toHaveURL(/page=0/);
  await expect(page).toHaveURL(/author=Herbert/);
});

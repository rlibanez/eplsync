import { test, expect } from "./fixtures";

async function records(page: import("@playwright/test").Page) {
  await page.route("**/api/torrent/downloads?**", (r) =>
    r.fulfill({
      json: {
        items: [
          {
            id: "record",
            eplId: 1,
            title: "Libro",
            hash: "A".repeat(40),
            revision: 1,
            status: "DOWNLOADED",
            client: "qbittorrent",
            clientInstanceId: "server",
            origin: "EPLSYNC",
            coverAvailable: false,
          },
        ],
        meta: {
          page: 0,
          size: 20,
          totalItems: 1,
          totalPages: 1,
          hasNext: false,
          hasPrevious: false,
        },
      },
    }),
  );
}
test("download record offers completed EPUBs and downloads the selected file", async ({
  page,
  context,
}) => {
  await records(page);
  await page.route("**/api/torrent/downloads/record/epubs", (r) =>
    r.fulfill({
      json: [
        { index: 4, name: "primero.epub", size: 5 },
        { index: 8, name: "segundo.epub", size: 5 },
      ],
    }),
  );
  await context.route("**/api/torrent/downloads/record/epubs/8", (r) =>
    r.fulfill({
      contentType: "application/epub+zip",
      headers: { "Content-Disposition": 'attachment; filename="segundo.epub"' },
      body: "bytes",
    }),
  );
  await page.goto("/downloads");
  const action = page.getByRole("button", {
    name: "Descargar EPUB",
    exact: true,
  });
  await expect(action).toHaveText("");
  const table = page.locator(".download-record-table");
  await expect(table.locator('th[data-table-column="actions"]')).toContainText(
    "Acciones",
  );
  const hashIndex = await table
    .locator("th")
    .evaluateAll((cells) =>
      cells.findIndex(
        (cell) => (cell as HTMLElement).dataset.tableColumn === "hash",
      ),
    );
  await expect(
    table
      .locator("tbody tr")
      .first()
      .locator("td")
      .nth(hashIndex)
      .getByRole("button"),
  ).toHaveCount(0);
  await action.hover();
  await expect(page.getByRole("tooltip")).toHaveText("Descargar EPUB");
  await action.click();
  const dialog = page.getByRole("dialog", { name: "Archivos EPUB" });
  await expect(
    dialog.getByRole("link", { name: "primero.epub" }),
  ).toBeVisible();
  const download = page.waitForEvent("download");
  await dialog.getByRole("link", { name: "segundo.epub" }).click();
  expect((await download).suggestedFilename()).toBe("segundo.epub");
  await expect(
    page.getByRole("heading", { name: "Estado de las descargas" }),
  ).toBeVisible();
});

test("download failures stay in the dialog with a concrete error", async ({
  page,
}) => {
  await records(page);
  await page.route("**/api/torrent/downloads/record/epubs", (r) =>
    r.fulfill({
      status: 409,
      json: {
        status: 409,
        message:
          "El torrent no contiene archivos EPUB completamente descargados",
        details:
          "El torrent no contiene archivos EPUB completamente descargados",
      },
    }),
  );
  await page.goto("/downloads");
  await page.getByRole("button", { name: "Descargar EPUB" }).click();
  await expect(page.getByRole("dialog")).toContainText(
    "El torrent no contiene archivos EPUB completamente descargados",
  );
  await expect(
    page.getByRole("button", { name: "Reintentar", exact: true }),
  ).toBeVisible();
});

test("a single EPUB downloads directly without opening the selector", async ({
  page,
  context,
}) => {
  await records(page);
  await page.route("**/api/torrent/downloads/record/epubs", (r) =>
    r.fulfill({ json: [{ index: 7, name: "unico.epub", size: 5 }] }),
  );
  await context.route("**/api/torrent/downloads/record/epubs/7", (r) =>
    r.fulfill({
      contentType: "application/epub+zip",
      headers: { "Content-Disposition": 'attachment; filename="unico.epub"' },
      body: "bytes",
    }),
  );
  await page.goto("/downloads");
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Descargar EPUB" }).click();
  expect((await download).suggestedFilename()).toBe("unico.epub");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(
    page.getByRole("heading", { name: "Estado de las descargas" }),
  ).toBeVisible();
});

test("a changed EPUB size requires acknowledgement before downloading", async ({
  page,
  context,
}) => {
  await records(page);
  await page.route("**/api/torrent/downloads/record/epubs", (route) =>
    route.fulfill({
      json: [{ index: 4, name: "changed.epub", size: 5, expectedSize: 9 }],
    }),
  );
  await context.route("**/api/torrent/downloads/record/epubs/4", (route) =>
    route.fulfill({
      contentType: "application/epub+zip",
      headers: { "Content-Disposition": 'attachment; filename="changed.epub"' },
      body: "bytes",
    }),
  );
  let downloads = 0;
  page.on("download", () => downloads++);
  await page.goto("/downloads");
  await page
    .getByRole("button", { name: "Descargar EPUB", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByText(/El tamaño del EPUB no coincide/),
  ).toBeVisible();
  expect(downloads).toBe(0);
  const download = page.waitForEvent("download");
  await dialog.getByRole("link", { name: "changed.epub", exact: true }).click();
  await download;
});

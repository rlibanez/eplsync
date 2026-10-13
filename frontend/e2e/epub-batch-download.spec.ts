import { test, expect } from "./fixtures";

async function records(page: import("@playwright/test").Page) {
  await page.route("**/api/torrent/downloads?**", (r) =>
    r.fulfill({
      json: {
        items: [1, 2].map((id) => ({
          id: String(id),
          eplId: id,
          title: `Libro ${id}`,
          hash: "A".repeat(40),
          revision: 1,
          status: "DOWNLOADED",
          client: "qbittorrent",
          clientInstanceId: "server",
          origin: "EPLSYNC",
          coverAvailable: false,
        })),
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
}

test("selected records download together and selection can be cleared", async ({
  page,
  context,
}) => {
  await records(page);
  let selected: string[] = [];
  await page.route("**/api/torrent/downloads/epub-batches", (r) => {
    selected = r.request().postDataJSON().ids;
    return r.fulfill({
      json: {
        url: "/api/torrent/downloads/epub-batches/token",
        fileCount: 2,
        archive: true,
      },
    });
  });
  await context.route("**/api/torrent/downloads/epub-batches/token", (r) =>
    r.fulfill({
      contentType: "application/zip",
      headers: {
        "Content-Disposition": 'attachment; filename="eplsync-epubs.zip"',
      },
      body: "zip",
    }),
  );
  await page.goto("/downloads");
  await expect(
    page.getByRole("button", { name: "Descargar", exact: true }),
  ).toHaveCount(0);
  await page
    .getByRole("checkbox", { name: "Seleccionar registros de esta página" })
    .check();
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Descargar", exact: true }).click();
  expect((await download).suggestedFilename()).toBe("eplsync-epubs.zip");
  expect(selected).toEqual(["1", "2"]);
  await page
    .getByRole("checkbox", { name: "Seleccionar registros de esta página" })
    .uncheck();
  await expect(
    page.getByRole("button", { name: "Descargar", exact: true }),
  ).toHaveCount(0);
});

test("failed preparation identifies unavailable records without starting download", async ({
  page,
}) => {
  await records(page);
  await page.route("**/api/torrent/downloads/epub-batches", (r) =>
    r.fulfill({
      status: 409,
      json: {
        status: 409,
        message: "No se ha iniciado la descarga. EPL ID 2: EPUB inaccesible",
        details: "No se ha iniciado la descarga. EPL ID 2: EPUB inaccesible",
      },
    }),
  );
  await page.goto("/downloads");
  await page
    .getByRole("checkbox", { name: "Seleccionar registros de esta página" })
    .check();
  await page.getByRole("button", { name: "Descargar", exact: true }).click();
  await expect(
    page
      .getByText("No se ha iniciado la descarga. EPL ID 2: EPUB inaccesible", {
        exact: true,
      })
      .first(),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Reintentar", exact: true }),
  ).toBeVisible();
});

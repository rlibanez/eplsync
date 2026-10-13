import { checkTableControls } from "./table-controls";
import { test, expect } from "./fixtures";

const items = [3, 8].map((id) => ({
  calibreId: id,
  title: "Libro",
  authors: "Autor",
  eplId: 1,
  revision: 1,
  catalogRevision: 2,
  comparison: "OUTDATED",
  presence: "PRESENT",
  coverAvailable: false,
}));
async function library(page: import("@playwright/test").Page) {
  await page.route("**/api/calibre/books?**", (r) =>
    r.fulfill({
      json: {
        items,
        meta: {
          page: 0,
          size: 20,
          totalItems: 2,
          totalPages: 1,
          hasPrevious: false,
          hasNext: false,
        },
      },
    }),
  );
  await page.route("**/api/settings/calibre/snapshot", (r) =>
    r.fulfill({ json: { snapshot: null } }),
  );
  await page.route("**/api/catalog/books/1", (r) =>
    r.fulfill({
      json: {
        eplId: 1,
        title: "Libro",
        author: "Autor",
        revision: 2,
        language: "es",
        status: "DISPONIBLE",
        coverAvailable: false,
        calibre: {
          totalItems: 2,
          items: [
            { calibreId: 3, revision: 1 },
            { calibreId: 8, revision: 1 },
          ],
        },
      },
    }),
  );
  await page.route("**/api/catalog/books/1/magnets", (r) =>
    r.fulfill({ json: [] }),
  );
}
for (const path of ["/calibre", "/catalog/1"]) {
  test(`single and multiple Calibre EPUB download from ${path}`, async ({
    page,
    context,
  }) => {
    await library(page);
    const selections: number[][] = [];
    await page.route("**/api/calibre/epub-downloads", (r) => {
      const ids = r.request().postDataJSON().ids as number[];
      selections.push(ids);
      return r.fulfill({
        json: {
          url: `/api/calibre/epub-downloads/${ids.length > 1 ? "zip" : "epub"}`,
          fileCount: ids.length,
          archive: ids.length > 1,
        },
      });
    });
    await context.route("**/api/calibre/epub-downloads/epub", (r) =>
      r.fulfill({
        contentType: "application/epub+zip",
        headers: {
          "Content-Disposition": 'attachment; filename="Libro [3].epub"',
        },
        body: "epub",
      }),
    );
    await context.route("**/api/calibre/epub-downloads/zip", (r) =>
      r.fulfill({
        contentType: "application/zip",
        headers: {
          "Content-Disposition": 'attachment; filename="calibre-epubs.zip"',
        },
        body: "zip",
      }),
    );
    await page.goto(path);
    const section =
      path === "/calibre"
        ? page.locator("section.panel")
        : page.locator("section.book-calibre");
    const row = section.locator("tbody tr").first();
    const downloadAction = row.getByRole("button", {
      name: "Descargar EPUB",
      exact: true,
    });
    await expect(downloadAction).toHaveText("");
    await downloadAction.hover();
    await expect(page.getByRole("tooltip")).toHaveText("Descargar EPUB");
    const openAction = row.getByRole("link", {
      name: "Ver en Calibre",
      exact: true,
    });
    await expect(openAction).toHaveAttribute(
      "href",
      "/api/calibre/books/3/open",
    );
    await expect(openAction).toHaveAttribute("target", "_blank");
    await expect(openAction).toHaveAttribute("rel", "noopener noreferrer");
    await expect(openAction).toHaveText("");
    await openAction.focus();
    await expect(
      page.getByRole("tooltip", { name: "Ver en Calibre", exact: true }),
    ).toBeVisible();
    const single = page.waitForEvent("download");
    await section
      .getByRole("button", { name: "Descargar EPUB", exact: true })
      .first()
      .click();
    expect((await single).suggestedFilename()).toBe("Libro [3].epub");
    expect(selections[0]).toEqual([3]);
    await section
      .getByRole("checkbox", { name: "Seleccionar registros de esta página" })
      .check();
    await expect(
      section.getByText("2 libros seleccionados", { exact: true }),
    ).toBeVisible();
    const multi = page.waitForEvent("download");
    await section
      .getByRole("button", { name: "Descargar", exact: true })
      .click();
    expect((await multi).suggestedFilename()).toBe("calibre-epubs.zip");
    expect(selections[1]).toEqual([3, 8]);
    await section
      .getByRole("checkbox", { name: "Seleccionar registros de esta página" })
      .uncheck();
    await expect(
      section.getByRole("button", { name: "Descargar", exact: true }),
    ).toHaveCount(0);
    await expect(
      section
        .locator('th[data-table-column="actions"]')
        .getByRole("button", { name: "Acciones", exact: true }),
    ).toHaveCount(0);
    await checkTableControls(page, "actions", "Acciones", section);
  });
}
test("missing EPUB reports its Calibre ID before any download", async ({
  page,
}) => {
  await library(page);
  await page.route("**/api/calibre/epub-downloads", (r) =>
    r.fulfill({
      status: 502,
      json: {
        status: 502,
        message: "Calibre ID 8: el libro no existe o no tiene formato EPUB",
        details: "Calibre ID 8: el libro no existe o no tiene formato EPUB",
      },
    }),
  );
  await page.goto("/calibre");
  await page
    .getByRole("checkbox", { name: "Seleccionar registros de esta página" })
    .check();
  await page.getByRole("button", { name: "Descargar", exact: true }).click();
  await expect(
    page
      .getByText("Calibre ID 8: el libro no existe o no tiene formato EPUB", {
        exact: true,
      })
      .first(),
  ).toBeVisible();
  await page.getByLabel("Libro", { exact: true }).fill("otro");
  await expect(
    page.getByRole("button", { name: "Descargar", exact: true }),
  ).toHaveCount(0);
});

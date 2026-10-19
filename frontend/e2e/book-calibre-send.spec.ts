import { test, expect } from "./fixtures";

const book = {
  eplId: 2,
  title: "Libro",
  author: "Autor",
  revision: 1.1,
  language: "es",
  coverAvailable: false,
  download: { items: [], totalItems: 0, statuses: [] },
  calibre: { items: [], totalItems: 0 },
};
const file = {
  recordId: "download-2",
  index: 0,
  name: "Libro.epub",
  size: 200,
  expectedSize: 200,
  eplId: 2,
  revision: 1.1,
};
test.beforeEach(async ({ page }) => {
  await page.route("**/api/catalog/books/2", (r) => r.fulfill({ json: book }));
  await page.route("**/api/catalog/books/2/magnets", (r) =>
    r.fulfill({ json: ["magnet:?xt=urn:btih:" + "A".repeat(40)] }),
  );
});
for (const missing of [false, true]) {
  test(`detail sends its single book with missing EPUB=${missing}`, async ({
    page,
  }) => {
    await page.route("**/api/calibre/transfers/catalog/prepare", (r) => {
      expect(r.request().postDataJSON()).toEqual({
        filters: { selectedIds: [2] },
      });
      return r.fulfill({
        json: {
          library: "main",
          libraries: [{ id: "main", name: "Main" }],
          files: missing ? [] : [file],
          books: [book],
          missing: missing ? [book] : [],
        },
      });
    });
    await page.route("**/api/calibre/transfers/new-job", (r) =>
      r.fulfill({
        json: {
          id: "new-job",
          state: "QUEUED",
          total: 1,
          pending: 1,
          accepted: 0,
          failed: 0,
          skipped: 0,
          uncertain: 0,
          library: "main",
        },
      }),
    );
    await page.route("**/api/calibre/transfers/new-job/items?**", (r) =>
      r.fulfill({
        json: {
          items: [],
          meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
        },
      }),
    );
    let submitted = false;
    await page.route("**/api/calibre/transfers/catalog", (r) => {
      expect(r.request().postDataJSON()).toMatchObject({
        library: "main",
        books: [book],
        files: missing ? [] : [{ recordId: "download-2", index: 0 }],
        download: missing,
        pending: missing ? [2] : [],
      });
      submitted = true;
      return r.fulfill({ json: { id: "new-job" } });
    });
    await page.goto("/catalog/2");
    const actions = page.locator(".book-actions > .action-row");
    await expect(
      actions.getByRole("button", { name: "Enviar a Calibre", exact: true }),
    ).toBeVisible();
    const buttons = await actions.locator(":scope > button").allTextContents();
    expect(buttons.indexOf("Enviar a Calibre")).toBe(
      buttons.indexOf("Enviar a descargar") + 1,
    );
    await actions
      .getByRole("button", { name: "Enviar a Calibre", exact: true })
      .click();
    const dialog = page.getByRole("dialog");
    if (missing) {
      await expect(
        dialog.getByRole("button", { name: "Enviar a Calibre", exact: true }),
      ).toBeDisabled();
      await dialog
        .getByRole("checkbox", {
          name: "Descargar los EPUB no disponibles y enviarlos a Calibre cuando terminen",
          exact: true,
        })
        .check();
    }
    await dialog
      .getByRole("button", { name: "Enviar a Calibre", exact: true })
      .click();
    await expect.poll(() => submitted).toBe(true);
    await expect(page).toHaveURL(/\/calibre\/jobs\/new-job$/);
  });
}
for (const permissions of [
  ["CATALOG_READ", "DOWNLOADS_FILES_READ"],
  ["CATALOG_READ", "CALIBRE_SEND"],
]) {
  test(`detail hides Calibre action without ${permissions.includes("CALIBRE_SEND") ? "file" : "send"} permission`, async ({
    page,
  }) => {
    await page.route("**/api/auth/me", (r) =>
      r.fulfill({
        json: {
          id: "reader",
          username: "reader",
          role: "USER",
          permissions,
          mustChangePassword: false,
        },
      }),
    );
    await page.goto("/catalog/2");
    await expect(
      page.getByRole("heading", { name: "Libro", exact: true }),
    ).toBeVisible();
    await expect(
      page
        .locator(".book-actions")
        .getByRole("button", { name: "Enviar a Calibre", exact: true }),
    ).toHaveCount(0);
  });
}

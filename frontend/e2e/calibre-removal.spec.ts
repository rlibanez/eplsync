import { test, expect } from "./fixtures";
const meta = {
  page: 0,
  size: 20,
  totalItems: 1,
  totalPages: 1,
  hasNext: false,
  hasPrevious: false,
};
for (const inline of [false, true]) {
  test(`Calibre deletion from ${inline ? "row" : "selection"} previews formats and creates a tracked job`, async ({
    page,
  }) => {
    const book = {
      calibreId: 20,
      title: "Entrada seleccionada",
      authors: "Autor",
      eplId: null,
      revision: null,
      catalogRevision: null,
      comparison: "UNKNOWN",
      presence: "UNIDENTIFIED",
      coverUrl: null,
      coverAvailable: false,
    };
    let current = {
      id: "calibre-removal",
      source: "calibre",
      action: "BOOK",
      state: "PREVIEW",
      directRemoval: true,
      result: null,
      message: null,
      library: "main",
      createdAt: 1792140000000,
      total: 1,
      selected: 0,
      ready: 1,
      excluded: 0,
      pending: 0,
      succeeded: 0,
      blocked: 0,
      failed: 0,
      uncertain: 0,
    };
    const item = {
      id: "item",
      eplId: null,
      title: book.title,
      revision: "",
      oldId: "20",
      replacementId: "",
      replacementRevision: "",
      formats: ["EPUB", "PDF"],
      state: "READY",
      message: null,
      dispatched: false,
    };
    let starts = 0;
    await page.route("**/api/calibre/books?**", (r) =>
      r.fulfill({ json: { items: [book], meta } }),
    );
    await page.route("**/api/settings/calibre/snapshot", (r) =>
      r.fulfill({ json: { snapshot: null } }),
    );
    await page.route(
      "**/api/revision-cleanups/calibre-removals/preview",
      (r) => {
        expect(r.request().postDataJSON()).toEqual({ ids: [20] });
        return r.fulfill({ json: current });
      },
    );
    await page.route("**/api/revision-cleanups/calibre-removal/items?**", (r) =>
      r.fulfill({ json: { items: [item], meta } }),
    );
    await page.route("**/api/revision-cleanups/calibre-removal", (r) =>
      r.fulfill({ json: current }),
    );
    await page.route("**/api/revision-cleanups/calibre-removal/start", (r) => {
      starts++;
      expect(r.request().postDataJSON()).toEqual({
        ids: ["item"],
        confirm: true,
        confirmDestructive: true,
      });
      current = { ...current, state: "QUEUED", pending: 1, selected: 1 };
      return r.fulfill({ json: current });
    });
    await page.goto("/calibre");
    if (!inline) await page.getByRole("checkbox", { name: /20/ }).check();
    const target = inline
      ? page.locator("tbody tr").first()
      : page.locator(".selection-toolbar-actions");
    await target.getByRole("button", { name: "Eliminar", exact: true }).click();
    const modal = page.getByRole("dialog");
    await expect(modal).toContainText("todos sus formatos");
    await modal
      .getByRole("button", { name: "Previsualizar", exact: true })
      .click();
    await expect(modal).toContainText("EPUB, PDF");
    expect(starts).toBe(0);
    await modal.getByRole("button", { name: "Siguiente", exact: true }).click();
    for (const checkbox of await modal.getByRole("checkbox").all())
      await checkbox.check();
    await modal.getByRole("button", { name: /Crear trabajo/ }).click();
    await expect(page).toHaveURL(/\/calibre\/jobs\/cleanup\/calibre-removal$/);
    await expect(
      page.getByRole("heading", { name: "Trabajo de borrado de Calibre" }),
    ).toBeVisible();
    expect(starts).toBe(1);
  });
}

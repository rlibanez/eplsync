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
    await expect(
      modal.locator(".mantine-Modal-title .lucide-trash2"),
    ).toHaveCount(1);
    await expect(modal).toContainText("todos sus formatos");
    await expect(modal.getByText("Opciones", { exact: true })).toHaveCount(0);
    await expect(
      modal.getByRole("button", { name: "Anterior", exact: true }),
    ).toHaveCount(0);
    await expect(modal).toContainText("EPUB, PDF");
    expect(starts).toBe(0);
    await modal.getByRole("button", { name: "Siguiente", exact: true }).click();
    await expect(modal).toContainText(
      "Se eliminará 1 libro de Calibre y todos sus formatos.",
    );
    await expect(modal).not.toContainText("revisiones antiguas");
    await expect(modal.getByRole("checkbox")).toHaveCount(1);
    await expect(
      modal.getByRole("checkbox", {
        name: "Confirmo el borrado irreversible de los elementos seleccionados.",
        exact: true,
      }),
    ).toBeVisible();
    await expect(modal.locator(".app-modal-actions")).not.toContainText(
      "seleccionad",
    );
    await expect(modal).not.toContainText("Borrar las entradas completas");
    await expect(
      modal.getByRole("button", { name: /Crear trabajo/ }),
    ).toBeDisabled();
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

test("large Calibre deletion previews paginate with bounded scrolling and preserve selection across pages", async ({
  page,
}) => {
  const books = Array.from({ length: 45 }, (_, i) => ({
    calibreId: i + 1,
    title: `Libro ${i + 1}`,
    authors: "Autor",
    eplId: null,
    revision: null,
    catalogRevision: null,
    comparison: "UNKNOWN",
    presence: "UNIDENTIFIED",
    coverUrl: null,
    coverAvailable: false,
  }));
  const items = books.map((book) => ({
    id: `item-${book.calibreId}`,
    eplId: null,
    title: book.title,
    revision: "",
    oldId: String(book.calibreId),
    replacementId: "",
    replacementRevision: "",
    formats: ["EPUB", "PDF"],
    state: book.calibreId === 23 ? "EXCLUDED" : "READY",
    message:
      book.calibreId === 23 ? "Entrada protegida por un trabajo activo" : null,
    dispatched: false,
  }));
  let current = {
    id: "large-removal",
    source: "calibre",
    action: "BOOK",
    state: "PREVIEW",
    directRemoval: true,
    result: null,
    message: null,
    library: "main",
    createdAt: 1792140000000,
    total: 45,
    selected: 0,
    ready: 44,
    excluded: 1,
    pending: 0,
    succeeded: 0,
    blocked: 0,
    failed: 0,
    uncertain: 0,
  };
  let starts = 0;
  function result<T>(rows: T[], url: string) {
    const params = new URL(url).searchParams;
    const page = Number(params.get("page") ?? 0),
      size = Number(params.get("size") ?? 20),
      pages = Math.ceil(rows.length / size);
    return {
      items: rows.slice(page * size, (page + 1) * size),
      meta: {
        page,
        size,
        totalItems: rows.length,
        totalPages: pages,
        hasNext: page + 1 < pages,
        hasPrevious: page > 0,
      },
    };
  }
  await page.route("**/api/calibre/books?**", (r) =>
    r.fulfill({ json: result(books, r.request().url()) }),
  );
  await page.route("**/api/settings/calibre/snapshot", (r) =>
    r.fulfill({ json: { snapshot: null } }),
  );
  await page.route("**/api/revision-cleanups/calibre-removals/preview", (r) => {
    expect(r.request().postDataJSON()).toEqual({
      ids: books.map((b) => b.calibreId),
    });
    return r.fulfill({ json: current });
  });
  await page.route("**/api/revision-cleanups/large-removal/items?**", (r) =>
    r.fulfill({ json: result(items, r.request().url()) }),
  );
  await page.route("**/api/revision-cleanups/large-removal", (r) =>
    r.fulfill({ json: current }),
  );
  await page.route("**/api/revision-cleanups/large-removal/start", (r) => {
    starts++;
    expect(r.request().postDataJSON()).toEqual({
      ids: items
        .filter((item) => !["item-1", "item-21", "item-23"].includes(item.id))
        .map((item) => item.id),
      confirm: true,
      confirmDestructive: true,
    });
    current = { ...current, state: "QUEUED", pending: 42, selected: 42 };
    return r.fulfill({ json: current });
  });
  await page.goto("/calibre");
  await page
    .getByRole("button", { name: "Seleccionar todo (45)", exact: true })
    .click();
  await page
    .locator(".selection-toolbar-actions")
    .getByRole("button", { name: "Eliminar", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  const pager = dialog.getByRole("navigation", {
    name: "Páginas de la previsualización",
  });
  const checkbox = (n: number) =>
    dialog.getByRole("checkbox", {
      name: `Seleccionar Libro ${n}`,
      exact: true,
    });
  await expect(pager).toContainText("1 / 3");
  await expect(dialog.locator("tbody tr")).toHaveCount(20);
  await expect(
    pager.getByRole("button", { name: "Anterior", exact: true }),
  ).toBeDisabled();
  await expect(dialog.locator("thead th").first()).toHaveCSS(
    "position",
    "sticky",
  );
  expect(
    await dialog
      .locator(".revision-cleanup-table")
      .evaluate((e) => e.scrollHeight > e.clientHeight),
  ).toBe(true);
  await checkbox(1).uncheck();
  await pager.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect(pager).toContainText("2 / 3");
  await checkbox(21).uncheck();
  await expect(checkbox(23)).toBeDisabled();
  await pager.getByRole("button", { name: "Anterior", exact: true }).click();
  await expect(checkbox(1)).not.toBeChecked();
  await expect(checkbox(2)).toBeChecked();
  await pager.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect(checkbox(21)).not.toBeChecked();
  await pager.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect(pager).toContainText("3 / 3");
  await expect(dialog.locator("tbody tr")).toHaveCount(5);
  await expect(
    dialog
      .getByRole("row")
      .filter({ hasText: "Libro 45" })
      .getByRole("cell", { name: "45", exact: true }),
  ).toBeVisible();
  await expect(
    pager.getByRole("button", { name: "Siguiente", exact: true }),
  ).toBeDisabled();
  await dialog.getByRole("button", { name: /Quitar selección/ }).click();
  await expect(checkbox(45)).not.toBeChecked();
  await dialog
    .getByRole("button", { name: "Seleccionar disponibles", exact: true })
    .click();
  await expect(checkbox(45)).toBeChecked();
  await pager.getByRole("button", { name: "Anterior", exact: true }).click();
  await expect(checkbox(21)).toBeChecked();
  await expect(checkbox(23)).not.toBeChecked();
  await checkbox(21).uncheck();
  await pager.getByRole("button", { name: "Anterior", exact: true }).click();
  await checkbox(1).uncheck();
  expect(starts).toBe(0);
  await page.screenshot({
    path: "/tmp/eplsync-calibre-removal-preview-desktop.png",
    animations: "disabled",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: "/tmp/eplsync-calibre-removal-preview-mobile.png",
    animations: "disabled",
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await dialog
    .locator(".app-modal-actions")
    .getByRole("button", { name: "Siguiente", exact: true })
    .click();
  await expect(dialog).toContainText(
    "Se eliminarán 42 libros de Calibre y todos sus formatos.",
  );
  await dialog.getByRole("checkbox").check();
  await dialog.getByRole("button", { name: /Crear trabajo/ }).click();
  await expect(page).toHaveURL(/\/calibre\/jobs\/cleanup\/large-removal$/);
  expect(starts).toBe(1);
});

import { test, expect } from "./fixtures";
const defaults = {
  start: true,
  autoManagement: true,
  savePath: null,
  rename: { enabled: true, pattern: "{author} - {title}" },
  category: "Libros",
  tags: ["EPLSync", "{language}"],
  concurrency: 1,
  batchSize: 100,
  interval: "500ms",
  multipleHashes: "skip",
};
const job = {
  jobId: "selected-job",
  status: "QUEUED",
  selectedBooks: 2,
  selectedItems: 2,
  processedItems: 0,
  accepted: 0,
  alreadyExists: 0,
  skipped: 0,
  failed: 0,
  pending: 2,
  inFlight: 0,
  cancelled: 0,
  concurrency: 1,
  batchSize: 100,
  interval: "500ms",
  multipleHashes: "skip",
  createdAt: "2026-10-03T08:00:00Z",
  updatedAt: "2026-10-03T08:00:00Z",
};
test.beforeEach(async ({ page }) => {
  await page.route("**/api/ui/config", (r) =>
    r.fulfill({ json: { defaultLanguage: "es" } }),
  );
  await page.route("**/api/torrent/client/categories", r => r.fulfill({ json: ["Libros", "Other"] }));
  await page.route("**/api/torrent/options", (r) =>
    r.fulfill({ json: defaults }),
  );
  await page.route("**/api/catalog/books?**", (r) => {
    const page = Number(
      new URL(r.request().url()).searchParams.get("page") ?? 0,
    );
    return r.fulfill({
      json: {
        items: [1, 2].map((n) => ({
          eplId: n + page * 2,
          title: `Book ${n + page * 2}`,
          author: "Author",
          language: "es",
          revision: 1.4,
          download: { items: [] },
        })),
        meta: {
          page,
          size: 20,
          totalItems: 40,
          totalPages: 2,
          hasNext: page === 0,
          hasPrevious: page > 0,
        },
      },
    });
  });
  await page.route("**/api/torrent/jobs/selected-job", (r) =>
    r.fulfill({ json: job }),
  );
  await page.route("**/api/torrent/jobs/selected-job/items?**", (r) =>
    r.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
      },
    }),
  );
});
test("page selection persists across pages, supports hiding and clears on filter changes", async ({
  page,
}) => {
  await page.goto("/catalog");
  await expect(page.locator(".catalog-table th").first()).toHaveAttribute(
    "data-column",
    "selection",
  );
  await expect(
    page.getByRole("link", { name: "Enviar", exact: true }),
  ).toHaveCount(0);
  await page
    .getByRole("checkbox", { name: "Seleccionar Book 1", exact: true })
    .check();
  await expect(
    page.getByRole("checkbox", { name: "Seleccionar esta página" }),
  ).toHaveJSProperty("indeterminate", true);
  await page.getByRole("checkbox", { name: "Seleccionar esta página" }).check();
  await expect(
    page.getByText("2 libros seleccionados", { exact: true }),
  ).toBeVisible();
  await page.screenshot({ path: "test-results/catalog-selection-toolbar.png" });
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect(
    page.getByRole("checkbox", { name: "Seleccionar Book 3" }),
  ).not.toBeChecked();
  await page.getByRole("checkbox", { name: "Seleccionar Book 3" }).check();
  await expect(
    page.getByText("3 libros seleccionados", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Columnas", exact: true }).click();
  await page
    .getByRole("checkbox", { name: "Selección", exact: true })
    .uncheck();
  await page.keyboard.press("Escape");
  await expect(
    page.getByRole("button", { name: "Enviar a descargar", exact: true }),
  ).toBeVisible();
  await page
    .locator(".catalog-value-filter")
    .filter({ hasText: "Author" })
    .first()
    .click();
  await expect(
    page.getByRole("button", { name: "Enviar a descargar", exact: true }),
  ).toHaveCount(0);
});
test("explicit selected IDs create a job with editable defaults and keyboard help", async ({
  page,
}) => {
  let calls = 0;
  await page.route("**/api/torrent/books", (r) => {
    calls++;
    expect(r.request().postDataJSON()).toMatchObject({
      dryRun: false,
      all: false,
      filters: { selectedIds: [1] },
      options: {
        qbittorrent: { category: "", tags: [], autoManagement: false },
        savePath: "/downloads/books",
      },
    });
    return r.fulfill({ status: 202, json: job });
  });
  await page.goto("/catalog");
  await page
    .getByRole("checkbox", { name: "Seleccionar Book 1", exact: true })
    .check();
  await page
    .getByRole("button", { name: "Enviar a descargar", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByLabel("Categoría", { exact: true })).toHaveValue(
    "Libros",
  );
  await expect(dialog.locator(".send-option-modified")).toHaveCount(0);
  await dialog.getByLabel("Categoría", { exact: true }).click();
  await page.getByRole("option", { name: "Other", exact: true }).click();
  await expect(dialog.getByLabel("Categoría", { exact: true })).toHaveClass(/send-option-modified/);
  await dialog
    .getByRole("button", { name: "Restablecer valores predeterminados" })
    .click();
  await expect(dialog.getByLabel("Categoría", { exact: true })).toHaveValue(
    "Libros",
  );
  await expect(dialog.locator(".send-option-modified")).toHaveCount(0);
  const help = dialog.locator('[tabindex="0"][aria-description]');
  await expect(help).toHaveCount(11);
  await help.filter({ hasText: "Patrón de nombre" }).focus();
  await expect(page.getByRole("tooltip")).toContainText("{author} - {title}");
  await expect(
    dialog.getByLabel("Ruta de descarga en el cliente", { exact: true }),
  ).toBeDisabled();
  await dialog
    .getByRole("textbox", { name: "Gestión automática", exact: true })
    .click();
  await page.getByRole("option", { name: "No", exact: true }).click();
  await dialog
    .getByLabel("Ruta de descarga en el cliente", { exact: true })
    .fill("/downloads/books");
  await dialog.getByLabel("Categoría", { exact: true }).click();
  await page.getByRole("option", { name: "Sin categoría", exact: true }).click();
  await dialog.getByLabel("Etiquetas", { exact: true }).fill("");
  await page.screenshot({ path: "test-results/send-options-modified.png" });
  expect(calls).toBe(0);
  await dialog.getByRole("button", { name: "Crear trabajo" }).click();
  await expect(page).toHaveURL(/downloads\/jobs\/selected-job$/);
  expect(calls).toBe(1);
});
test("select all uses filters and exclusions without fetching the full catalog", async ({
  page,
}) => {
  let request: Record<string, unknown> | null = null;
  await page.route("**/api/torrent/books", (r) => {
    request = r.request().postDataJSON();
    return r.fulfill({ status: 202, json: job });
  });
  await page.goto(
    "/catalog?author=Asimov&author=Sanderson&language=es&language=en",
  );
  await page
    .getByRole("button", { name: "Seleccionar todo (40)" })
    .click();
  await page
    .getByRole("checkbox", { name: "Seleccionar Book 1", exact: true })
    .uncheck();
  await expect(
    page.getByText("39 resultados seleccionados"),
  ).toBeVisible();
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect(
    page.getByRole("checkbox", { name: "Seleccionar esta página" }),
  ).toBeChecked();
  await page
    .getByRole("button", { name: "Enviar a descargar", exact: true })
    .click();
  await page.getByRole("button", { name: "Crear trabajo" }).click();
  await expect
    .poll(() => request)
    .toMatchObject({
      all: true,
      filters: {
        author: ["Asimov", "Sanderson"],
        language: ["es", "en"],
        excludedIds: [1],
      },
    });
});
test("export downloads selected IDs directly as magnets.txt without a dialog", async ({
  page,
}) => {
  await page.addInitScript(() => {
    Object.defineProperty(window, "showSaveFilePicker", {
      value: undefined,
      configurable: true,
    });
  });
  await page.route("**/api/catalog/magnets/export", (r) => {
    expect(r.request().postDataJSON()).toEqual({
      filters: { selectedIds: [1, 2] },
    });
    return r.fulfill({
      contentType: "text/plain",
      body: "magnet:?xt=urn:btih:A\nmagnet:?xt=urn:btih:B\n",
    });
  });
  await page.goto("/catalog");
  await page.getByRole("checkbox", { name: "Seleccionar esta página" }).check();
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Exportar magnet links" }).click();
  expect((await download).suggestedFilename()).toBe("magnets.txt");
  await expect(page.getByRole("dialog")).toHaveCount(0);
});
test("native save picker opens directly and writes the exported links", async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(window, "showSaveFilePicker", {
      value: async (options: { suggestedName: string }) => {
        document.documentElement.dataset.suggestedName = options.suggestedName;
        return {
          createWritable: async () => ({
            write: async (blob: Blob) => {
              document.documentElement.dataset.savedMagnets = await blob.text();
            },
            close: async () => { document.documentElement.dataset.saved = "true"; },
            abort: async () => {},
          }),
        };
      },
      configurable: true,
    });
  });
  await page.route("**/api/catalog/magnets/export", r => r.fulfill({
    contentType: "text/plain", body: "magnet:?test",
  }));
  await page.goto("/catalog");
  await page.getByRole("checkbox", { name: "Seleccionar esta página" }).check();
  await page.getByRole("button", { name: "Exportar magnet links" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-suggested-name", "magnets.txt");
  await expect(page.locator("html")).toHaveAttribute("data-saved-magnets", "magnet:?test");
  await expect(page.locator("html")).toHaveAttribute("data-saved", "true");
  await expect(page.getByRole("dialog")).toHaveCount(0);
});
test("native save picker is called before fetching and respects cancellation", async ({
  page,
}) => {
  await page.addInitScript(() => {
    Object.defineProperty(window, "showSaveFilePicker", {
      value: async () => {
        throw new DOMException("Cancelled", "AbortError");
      },
      configurable: true,
    });
  });
  let exports = 0;
  await page.route("**/api/catalog/magnets/export", (r) => {
    exports++;
    return r.fulfill({ body: "magnet:?test" });
  });
  await page.goto("/catalog");
  await page.getByRole("checkbox", { name: "Seleccionar esta página" }).check();
  await page.getByRole("button", { name: "Exportar magnet links" }).click();
  await expect(
    page.getByRole("button", { name: "Exportar magnet links" }),
  ).toBeEnabled();
  expect(exports).toBe(0);
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

test("created jobs still support pause, resume and confirmed cancellation", async ({
  page,
}) => {
  let current = { ...job };
  let cancels = 0;
  await page.route("**/api/torrent/jobs/selected-job", (r) =>
    r.fulfill({ json: current }),
  );
  for (const [action, status] of [
    ["pause", "PAUSED"],
    ["resume", "QUEUED"],
    ["cancel", "CANCELLED"],
  ])
    await page.route(`**/api/torrent/jobs/selected-job/${action}`, (r) => {
      if (action === "cancel") cancels++;
      current = { ...current, status };
      return r.fulfill({ json: current });
    });
  await page.goto("/downloads/jobs/selected-job");
  await page.getByRole("button", { name: "Pausar", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Pausado", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Reanudar", exact: true }).click();
  await page
    .getByRole("button", { name: "Cancelar trabajo", exact: true })
    .click();
  expect(cancels).toBe(0);
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Cancelar trabajo", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Cancelado", exact: true }),
  ).toBeVisible();
  expect(cancels).toBe(1);
});
test("a failed submission is not retried automatically", async ({ page }) => {
  let calls = 0;
  await page.route("**/api/torrent/books", (r) => {
    calls++;
    return r.abort();
  });
  await page.goto("/catalog");
  await page
    .getByRole("button", { name: "Seleccionar todo (40)" })
    .click();
  await page
    .getByRole("button", { name: "Enviar a descargar", exact: true })
    .click();
  await page.screenshot({
    path: "test-results/catalog-send-dialog.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "Crear trabajo" }).click();
  await expect(page.locator(".notification-toasts")).toContainText(
    "podría haberse ejecutado",
  );
  expect(calls).toBe(1);
});

test("column sorting is server-side, resets pagination and preserves selection and filters", async ({ page }) => {
  await page.goto("/catalog?author=Asimov&page=1");
  await page.getByRole("checkbox", { name: "Seleccionar Book 3", exact: true }).check();
  const author = page.locator('th[data-column="author"]');
  const request = page.waitForRequest(r => r.url().includes("/api/catalog/books?") && new URL(r.url()).searchParams.get("sort") === "author,asc");
  await author.getByRole("button", { name: "Autor", exact: true }).click();
  await request;
  await expect(page).toHaveURL(/page=0/);
  expect(new URL(page.url()).searchParams.get("author")).toBe("Asimov");
  await expect(page.getByText("1 libros seleccionados", { exact: true })).toBeVisible();
  await expect(author).toHaveAttribute("aria-sort", "ascending");
  await author.getByRole("button", { name: "Autor", exact: true }).click();
  await expect(author).toHaveAttribute("aria-sort", "descending");
  await page.getByRole("button", { name: "Ordenar", exact: true }).click();
  await expect(page.getByRole("textbox", { name: "Criterio 1", exact: true })).toHaveValue("Autor");
});


test("multiple sort priorities survive pagination and can be reordered or removed", async ({ page }) => {
  await page.goto("/catalog?sort=language,asc");
  await page.locator('th[data-column="title"] .catalog-sort-heading').click({ modifiers: ["Shift"] });
  await expect(page.getByRole("button", { name: "Ordenar (2)", exact: true })).toBeVisible();
  expect(new URL(page.url()).searchParams.getAll("sort")).toEqual(["language,asc", "title,asc"]);
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  expect(new URL(page.url()).searchParams.getAll("sort")).toEqual(["language,asc", "title,asc"]);
  await page.getByRole("button", { name: "Ordenar (2)", exact: true }).click();
  await page.getByRole("button", { name: "Subir criterio 2" }).click();
  expect(new URL(page.url()).searchParams.getAll("sort")).toEqual(["title,asc", "language,asc"]);
  expect(new URL(page.url()).searchParams.get("page")).toBe("0");
  await expect(page.getByRole("textbox", { name: "Criterio 1", exact: true })).toHaveValue("Libro");
  await page.getByRole("button", { name: "Quitar criterio 2" }).click();
  expect(new URL(page.url()).searchParams.getAll("sort")).toEqual(["title,asc"]);
  await page.getByRole("button", { name: "Añadir criterio" }).click();
  expect(new URL(page.url()).searchParams.getAll("sort")).toEqual(["title,asc", "author,asc"]);
  await page.getByRole("button", { name: "Restablecer", exact: true }).click();
  expect(new URL(page.url()).searchParams.getAll("sort")).toEqual(["title,asc"]);
});


test("unknown configured category falls back to no category and query failures can be retried", async ({ page }) => {
  let fail = true;
  await page.route("**/api/torrent/client/categories", r => fail
    ? r.fulfill({ status: 409, json: { details: "La conexión torrent está deshabilitada" } })
    : r.fulfill({ json: ["Other"] }));
  await page.goto("/catalog");
  await page.getByRole("checkbox", { name: "Seleccionar Book 1", exact: true }).check();
  await page.getByRole("button", { name: "Enviar a descargar", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("button", { name: "Crear trabajo" })).toBeDisabled();
  await expect(dialog.getByText("La conexión torrent está deshabilitada", { exact: true })).toBeVisible();
  await expect(dialog.getByText("No se pudo cargar la información", { exact: true })).toHaveCount(0);
  fail = false;
  await dialog.getByRole("button", { name: "Reintentar" }).click();
  await expect(dialog.getByLabel("Categoría", { exact: true })).toHaveValue("Sin categoría");
  await expect(dialog.getByRole("button", { name: "Crear trabajo" })).toBeEnabled();
  await expect(dialog.getByLabel("Categoría", { exact: true })).not.toHaveClass(/send-option-modified/);
});

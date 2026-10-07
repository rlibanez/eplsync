import { test, expect, type Page } from "./fixtures";

async function history(page: Page) {
  const book = {
    eplId: 1,
    title: "Libro de prueba",
    author: "Autora",
    revision: 3,
    language: "es",
    coverUrl: null,
    coverAvailable: false,
    download: { items: [], totalItems: 0, statuses: [] },
  };
  await page.route("**/api/catalog/books/1", (r) => r.fulfill({ json: book }));
  await page.route("**/api/catalog/books/1/magnets", (r) =>
    r.fulfill({ json: [] }),
  );
  let rows = [1, 2].map((revision) => ({
    id: `r${revision}`,
    revision,
    hash: (revision === 1 ? "A" : "B").repeat(40),
    status: "SUBMITTED",
    client: "qbittorrent",
    clientInstanceId: "client",
    origin: "EPLSYNC",
    lastCheckedAt: null,
    completedAt: null,
    lastError: null,
  }));
  await page.route("**/api/catalog/books/1/history?**", (r) =>
    r.fulfill({
      json: {
        items: rows,
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
  return {
    rows,
    setRows: (updated: typeof rows) => {
      rows = updated;
    },
  };
}

test("history refreshes selected records and removes through a confirmed dropdown action", async ({
  page,
}) => {
  const data = await history(page);
  let refreshed: any, removed: any;
  await page.route("**/api/torrent/downloads/refresh-selected", (r) => {
    refreshed = r.request().postDataJSON();
    data.setRows(data.rows.map((row) => ({ ...row, status: "DOWNLOADED" })));
    return r.fulfill({
      json: {
        items: data.rows.map((row) => ({
          id: row.id,
          status: "DOWNLOADED",
          cleanupState: null,
          message: null,
        })),
      },
    });
  });
  await page.route("**/api/torrent/downloads/remove-selected", (r) => {
    removed = r.request().postDataJSON();
    return r.fulfill({
      json: {
        items: [
          {
            id: "r1",
            status: "NOT_FOUND",
            cleanupState: "REMOVED",
            message: "Ausencia confirmada",
          },
          {
            id: "r2",
            status: "DOWNLOADED",
            cleanupState: "BLOCKED",
            message: "Rutas compartidas",
          },
        ],
      },
    });
  });
  await page.goto("/catalog/1");
  const section = page.locator(".book-history");
  await section
    .getByRole("checkbox", { name: "Seleccionar registros de esta página" })
    .check();
  const selection = section.locator(".catalog-selection-bar.has-selection");
  const count = selection.locator(".catalog-selection-count");
  await expect(count).toHaveText("2 registros seleccionados");
  await expect(count).toHaveCSS("font-size", "14px");
  await expect(count).toHaveCSS("font-weight", "600");
  const layout = await selection.evaluate((bar) => {
    const actions = bar.querySelector(".catalog-selection-actions")!;
    return {
      rightGap:
        bar.getBoundingClientRect().right -
        actions.getBoundingClientRect().right,
      leftGap:
        actions.getBoundingClientRect().left - bar.getBoundingClientRect().left,
    };
  });
  expect(layout.rightGap).toBeLessThan(20);
  expect(layout.leftGap).toBeGreaterThan(layout.rightGap);
  await section
    .getByRole("button", { name: "Actualizar estado", exact: true })
    .click();
  const result = page.getByRole("dialog");
  await expect(result.getByText("Descargado", { exact: true })).toHaveCount(2);
  expect(refreshed.eplId).toBe(1);
  expect(refreshed.ids.sort()).toEqual(["r1", "r2"]);
  await result.getByRole("button", { name: "Cerrar", exact: true }).click();
  await section
    .getByRole("checkbox", { name: "Seleccionar registros de esta página" })
    .check();
  await section
    .getByRole("button", { name: "Eliminar del cliente", exact: true })
    .click();
  await page
    .getByRole("menuitem", { name: "Torrent y archivos", exact: true })
    .click();
  const confirm = page.getByRole("dialog");
  await expect(
    confirm.getByRole("button", { name: "Eliminar del cliente", exact: true }),
  ).toBeDisabled();
  await confirm
    .getByRole("checkbox", {
      name: "Entiendo que el borrado de archivos es irreversible.",
    })
    .check();
  await confirm
    .getByRole("button", { name: "Eliminar del cliente", exact: true })
    .click();
  await expect(
    result.getByText("Eliminado del cliente", { exact: true }),
  ).toBeVisible();
  await expect(
    result.getByText("Eliminación bloqueada", { exact: true }),
  ).toBeVisible();
  expect(removed).toEqual({
    eplId: 1,
    ids: ["r1", "r2"],
    deleteFiles: true,
    confirmFiles: true,
  });
});

test("history actions respect permissions and show errors inside the confirmation", async ({
  page,
}) => {
  await history(page);
  await page.route("**/api/auth/me", (r) =>
    r.fulfill({
      json: {
        id: "reader",
        username: "reader",
        email: "r@example.org",
        role: "USER",
        mustChangePassword: false,
        permissions: ["CATALOG_READ", "BOOK_HISTORY_READ", "TORRENT_CLEANUP"],
      },
    }),
  );
  await page.route("**/api/torrent/downloads/remove-selected", (r) =>
    r.fulfill({
      status: 409,
      json: { details: "Los registros pertenecen a otro cliente" },
    }),
  );
  await page.goto("/catalog/1");
  const section = page.locator(".book-history");
  await section
    .getByRole("checkbox", { name: "Seleccionar revisión 1", exact: true })
    .check();
  await expect(
    section.getByRole("button", { name: "Actualizar estado", exact: true }),
  ).toHaveCount(0);
  await section
    .getByRole("button", { name: "Eliminar del cliente", exact: true })
    .click();
  await expect(
    page.getByRole("menuitem", { name: "Torrent y archivos", exact: true }),
  ).toHaveCount(0);
  await page
    .getByRole("menuitem", { name: "Solo torrent", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog
    .getByRole("button", { name: "Eliminar del cliente", exact: true })
    .click();
  await expect(dialog.getByRole("alert")).toContainText(
    "Los registros pertenecen a otro cliente",
  );
  await expect(section.getByRole("alert")).toHaveCount(0);
});

test("job detail identifies updates and explains cleanup policy and pending counts", async ({
  page,
}) => {
  await page.route("**/api/torrent/jobs/example", (r) =>
    r.fulfill({
      json: {
        jobId: "example",
        status: "COMPLETED",
        type: "UPDATE",
        previousVersions: "removeTorrentAndFiles",
        cleanup: {
          waiting: 3,
          blocked: 1,
          requested: 0,
          removed: 2,
          cancelled: 0,
        },
        selectedBooks: 6,
        selectedItems: 6,
        processedItems: 6,
        selectedTorrents: 6,
        processedTorrents: 6,
        accepted: 6,
        alreadyExists: 0,
        skipped: 0,
        failed: 0,
        pending: 0,
        inFlight: 0,
        cancelled: 0,
        batchSize: 100,
        concurrency: 1,
        interval: "500ms",
        multipleHashes: "all",
        createdAt: "2026-10-07T10:00:00Z",
        updatedAt: "2026-10-07T10:01:00Z",
      },
    }),
  );
  await page.route("**/api/torrent/jobs/example/items?**", (r) =>
    r.fulfill({
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
  await page.goto("/downloads/jobs/example");
  await page.locator(".report-collapse > summary").click();
  await expect(page.getByText("Actualización", { exact: true })).toBeVisible();
  await expect(
    page.getByText("Eliminar torrents y archivos", { exact: true }),
  ).toBeVisible();
  const pending = page
    .locator(".import-summary > div")
    .filter({ has: page.getByText("Limpiezas pendientes", { exact: true }) });
  await expect(pending.locator("dd")).toHaveText("3");
});

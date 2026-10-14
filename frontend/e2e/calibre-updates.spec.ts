import { test, expect } from "./fixtures";

test("Calibre updates compare the snapshot and refresh it without sending files", async ({
  page,
}) => {
  await page.route("**/api/settings/calibre/snapshot", (r) =>
    r.fulfill({
      json: {
        snapshot: {
          library: "main",
          syncedAt: "2026-10-13T12:00:00Z",
          total: 1,
          identified: 1,
        },
      },
    }),
  );
  await page.route("**/api/settings/calibre/sync", (r) =>
    r.fulfill({
      json: {
        library: "main",
        syncedAt: "2026-10-13T13:00:00Z",
        total: 1,
        identified: 1,
      },
    }),
  );
  await page.route("**/api/calibre/books?**", (r) => {
    expect(new URL(r.request().url()).searchParams.get("updatesOnly")).toBe(
      "true",
    );
    return r.fulfill({
      json: {
        items: [
          {
            calibreId: 2,
            eplId: 1,
            title: "Libro antiguo",
            authors: "Autor",
            revision: 1.2,
            catalogRevision: 2,
            comparison: "OUTDATED",
            presence: "PRESENT",
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
    });
  });
  await page.route("**/api/calibre/updates/availability", (r) =>
    r.fulfill({
      json: r
        .request()
        .postDataJSON()
        .ids.map((id: number) => ({
          calibreId: id,
          state: id === 1 ? "READY" : "MISSING",
        })),
    }),
  );
  await page.goto("/calibre/updates");
  await expect(
    page.getByRole("heading", { name: "Actualizaciones de Calibre" }),
  ).toBeVisible();
  await expect(page.locator("tbody")).toContainText(
    "No hay actualizaciones pendientes.",
  );
  await expect(page.getByLabel("Comparación", { exact: true })).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Descargar EPUB", exact: true }),
  ).toHaveCount(0);
  const sync = page.waitForRequest(
    (r) =>
      r.url().endsWith("/api/settings/calibre/sync") && r.method() === "POST",
  );
  await page
    .getByRole("button", { name: "Buscar actualizaciones", exact: true })
    .click();
  await sync;
  await expect(page.locator("tbody")).toContainText("Libro antiguo");
  await page.reload();
  await expect(page.locator("tbody")).toContainText("Libro antiguo");
});

test("Downloads state opens the existing update search dialog", async ({
  page,
}) => {
  await page.route("**/api/torrent/downloads?**", (r) =>
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
  await page.route("**/api/settings/downloads/updates", (r) =>
    r.fulfill({ json: { states: ["DOWNLOADED"] } }),
  );
  await page.goto("/downloads");
  await page
    .getByRole("link", { name: "Buscar actualizaciones", exact: true })
    .click();
  await expect(page).toHaveURL(/\/downloads\/updates/);
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(
    page
      .getByRole("dialog")
      .getByRole("button", { name: "Buscar", exact: true }),
  ).toBeVisible();
});

test("Mixed Calibre updates offer deferred sending and confirmed per-book cleanup", async ({
  page,
}) => {
  await page.route("**/api/settings/calibre/sync", (r) =>
    r.fulfill({
      json: {
        library: "main",
        syncedAt: "2026-10-13T13:00:00Z",
        total: 2,
        identified: 2,
      },
    }),
  );
  await page.route("**/api/settings/calibre/snapshot", (r) =>
    r.fulfill({
      json: {
        snapshot: {
          library: "main",
          syncedAt: "2026-10-13T12:00:00Z",
          total: 2,
          identified: 2,
        },
      },
    }),
  );
  await page.route("**/api/calibre/books?**", (r) =>
    r.fulfill({
      json: {
        items: [1, 2].map((id) => ({
          calibreId: id,
          eplId: id,
          title: `Libro ${id}`,
          authors: "Autor",
          revision: 1,
          catalogRevision: 2,
          comparison: "OUTDATED",
          presence: "PRESENT",
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
  await page.route("**/api/calibre/updates/prepare", (r) =>
    r.fulfill({
      json: [1, 2].map((id) => ({
        calibreId: id,
        eplId: id,
        title: `Libro ${id}`,
        revision: 2,
        previous: [{ id, revision: "1" }],
        files:
          id === 1
            ? [
                {
                  recordId: "ready",
                  index: 0,
                  name: "book.epub",
                  size: 100,
                  expectedSize: 100,
                },
              ]
            : [],
        problem: null,
      })),
    }),
  );
  const requests: unknown[] = [];
  await page.route("**/api/calibre/updates", (r) => {
    requests.push(r.request().postDataJSON());
    return r.fulfill({
      json: { downloadJobId: "download", calibreJobId: "calibre" },
    });
  });
  await page.route("**/api/calibre/updates/availability", (r) =>
    r.fulfill({
      json: r
        .request()
        .postDataJSON()
        .ids.map((id: number) => ({
          calibreId: id,
          state: id === 1 ? "READY" : "MISSING",
        })),
    }),
  );
  await page.goto("/calibre/updates");
  await page
    .getByRole("button", { name: "Buscar actualizaciones", exact: true })
    .click();
  await expect(
    page.getByRole("columnheader", { name: /Nueva revisión descargada/ }),
  ).toBeVisible();
  const availability = page.getByRole("textbox", {
    name: "Nueva revisión descargada",
    exact: true,
  });
  await availability.click();
  await page.getByRole("option", { name: "Sí", exact: true }).click();
  await expect(page.locator("tbody tr")).toHaveCount(1);
  await page.getByRole("button", { name: "Limpiar", exact: true }).click();
  await expect(page.locator("tbody tr")).toHaveCount(2);
  await page.locator("thead input[type=checkbox]").check();
  await page
    .getByRole("button", { name: "Enviar actualizaciones (2)" })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByText("1 actualización disponible para enviar a Calibre"),
  ).toBeVisible();
  await expect(
    dialog.getByText("1 actualización aún no está descargada"),
  ).toBeVisible();
  await dialog.getByLabel("Personalizar por libro").check();
  await dialog.getByLabel("Enviar los EPUB no disponibles a descargar").check();
  await dialog.getByLabel("Cuando estén descargados", { exact: true }).click();
  await page
    .getByRole("option", { name: "Enviar a Calibre cuando estén descargados" })
    .click();
  await dialog
    .getByLabel("Acción sobre las revisiones anteriores")
    .nth(1)
    .click();
  await page
    .getByRole("option", { name: "Eliminar el libro completo de Calibre" })
    .click();
  await expect(
    dialog.getByRole("button", { name: "Crear trabajos" }),
  ).toBeDisabled();
  await dialog
    .getByLabel(
      "Confirmo que se eliminará el libro completo, incluidos otros formatos y sus datos asociados.",
    )
    .check();
  await dialog.getByRole("button", { name: "Crear trabajos" }).click();
  await expect(
    dialog.getByRole("link", { name: "Ver trabajo de descargas" }),
  ).toHaveAttribute("href", "/downloads/jobs/download");
  await expect(
    dialog.getByRole("link", { name: "Ver trabajo de Calibre" }),
  ).toHaveAttribute("href", "/calibre/jobs/calibre");
  expect(requests).toHaveLength(1);
  expect(requests[0]).toMatchObject({
    download: true,
    deferred: true,
    books: [
      { send: true, cleanup: "BOOK", previous: [1], confirmBookDelete: true },
      { send: true, cleanup: "KEEP" },
    ],
  });
});

test("library shortcut searches once and reload only restores the result", async ({
  page,
}) => {
  let syncs = 0;
  await page.route("**/api/settings/calibre/snapshot", (r) =>
    r.fulfill({ json: { snapshot: null } }),
  );
  await page.route("**/api/settings/calibre/sync", (r) => {
    syncs++;
    return r.fulfill({
      json: {
        library: "main",
        syncedAt: "2026-10-14T12:00:00Z",
        total: 0,
        identified: 0,
      },
    });
  });
  await page.route("**/api/calibre/books?**", (r) =>
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
  await page.goto("/calibre");
  await page
    .getByRole("link", { name: "Buscar actualizaciones", exact: true })
    .click();
  await expect(page).toHaveURL(/\/calibre\/updates$/);
  await expect(page.getByText(/Última búsqueda:/)).toBeVisible();
  expect(syncs).toBe(1);
  await page.reload();
  await expect(page.getByText(/Última búsqueda:/)).toBeVisible();
  expect(syncs).toBe(1);
});

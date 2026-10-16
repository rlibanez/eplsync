import { fulfillSavedSearch } from "./saved-search-response";
import { test, expect } from "./fixtures";
import { checkTableControls } from "./table-controls";

test("revision search shows the torrent connection error and can retry", async ({
  page,
}) => {
  await page.route("**/api/settings/downloads/updates", (route) =>
    fulfillSavedSearch(route, { json: { states: ["DOWNLOADED"] } }),
  );
  const details =
    "No se puede conectar con el cliente torrent; revisa la URL y que el cliente esté disponible";
  let attempts = 0;
  let persistedSearch: Record<string, unknown> | null = null;
  await page.route("**/api/settings/tasks/searches/downloads**", (route) => {
    if (route.request().method() === "GET")
      return fulfillSavedSearch(route, { json: { search: persistedSearch } });

    attempts++;
    return attempts === 1
      ? fulfillSavedSearch(route, { status: 502, json: { details } })
      : fulfillSavedSearch(route, {
          json: (persistedSearch = {
            rows: [],
            searchedAt: Date.now(),
            states: route.request().postDataJSON().states,
            origin: "MANUAL",
            criteria: { states: route.request().postDataJSON().states },
          }),
        });
  });
  await page.goto("/downloads/updates");
  const subtitle = page.getByText(
    "Compara las revisiones registradas de tus descargas con el Catálogo. Busca actualizaciones para sincronizar con el cliente torrent y consultar las nuevas revisiones disponibles.",
    { exact: true },
  );
  await expect(subtitle).not.toBeVisible();
  await page
    .getByRole("heading", {
      name: "Actualizaciones",
      exact: true,
    })
    .focus();
  await expect(page.getByRole("tooltip")).toContainText(
    "Compara las revisiones registradas",
  );
  await page
    .getByRole("heading", {
      name: "Actualizaciones",
      exact: true,
    })
    .press("Escape");
  await page
    .getByRole("button", { name: "Buscar actualizaciones", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect(dialog.getByRole("alert")).toContainText(details);
  await expect(dialog).not.toContainText("Se ha producido un error inesperado");
  await dialog.getByRole("button", { name: "Reintentar" }).click();
  await expect(dialog).not.toBeVisible();
  expect(attempts).toBe(2);
  await expect(subtitle).not.toBeVisible();
  await expect(page.getByText(/Última búsqueda:/)).toBeVisible();
});

test("revision search synchronizes, uses temporary options, sorts and sends selected books", async ({
  page,
}) => {
  const queries: { params: URLSearchParams; states: string[] }[] = [];
  let writes = 0;
  await page.route("**/api/settings/downloads/updates", (route) => {
    if (route.request().method() === "PUT") writes++;
    return fulfillSavedSearch(route, {
      json: { states: ["DOWNLOADED", "NOT_FOUND"] },
    });
  });
  let persistedSearch: Record<string, unknown> | null = null;
  await page.route("**/api/settings/tasks/searches/downloads**", (route) => {
    if (route.request().method() === "GET")
      return fulfillSavedSearch(route, { json: { search: persistedSearch } });

    const params = new URL(route.request().url()).searchParams;
    queries.push({ params, states: route.request().postDataJSON().states });
    return fulfillSavedSearch(route, {
      json: (persistedSearch = {
        rows: Array.from({ length: 40 }, (_, index) => {
          const offset = Number(params.get("page") ?? 0) * 20 + index;
          return {
            eplId: 123 + offset,
            title: offset === 0 ? "A Test book" : `Other book ${offset}`,
            registeredRevision: 1.1,
            availableRevision: 1.2,
            status: offset === 0 ? "NOT_FOUND" : "DOWNLOADED",
          };
        }),
        searchedAt: Date.now(),
        states: route.request().postDataJSON().states,
        origin: "MANUAL",
        criteria: { states: route.request().postDataJSON().states },
      }),
    });
  });
  await page.route("**/api/torrent/options", (route) =>
    fulfillSavedSearch(route, {
      json: {
        start: true,
        autoManagement: false,
        savePath: "",
        rename: { enabled: true, pattern: "{title}" },
        category: "",
        tags: [],
        concurrency: 2,
        batchSize: 20,
        interval: "0ms",
        multipleHashes: "all",
      },
    }),
  );
  await page.route("**/api/torrent/client/categories", (route) =>
    fulfillSavedSearch(route, { json: [] }),
  );
  let submission: any;
  await page.route("**/api/torrent/revision-updates/send", (route) => {
    submission = route.request().postDataJSON();
    return fulfillSavedSearch(route, { json: { jobId: "update-job" } });
  });
  await page.route("**/api/torrent/jobs/update-job**", (route) =>
    fulfillSavedSearch(route, {
      json: { items: [], jobId: "update-job", status: "COMPLETED" },
    }),
  );
  await page.goto("/downloads/updates");
  await expect(
    page.locator(".sidebar").locator('a[href="/downloads/updates"]'),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Buscar actualizaciones", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  const actionBoxes = await Promise.all(
    ["Restaurar configuración guardada", "Cancelar", "Buscar"].map((name) =>
      dialog.getByRole("button", { name, exact: true }).boundingBox(),
    ),
  );
  expect(actionBoxes.every((box) => box !== null)).toBe(true);
  expect(
    Math.max(...actionBoxes.map((box) => box!.y)) -
      Math.min(...actionBoxes.map((box) => box!.y)),
  ).toBeLessThan(2);

  const boxes = dialog.locator("input[type=checkbox]");
  await expect(boxes).toHaveCount(10);
  await boxes.first().check();
  await dialog
    .getByRole("button", { name: "Restaurar configuración guardada" })
    .click();
  await expect(boxes.first()).not.toBeChecked();
  await boxes.first().check();
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect(
    page.getByRole("cell", { name: "A Test book", exact: true }),
  ).toBeVisible();
  expect(
    (await page.locator(".updates-selection").first().boundingBox())!.width,
  ).toBeLessThanOrEqual(50);
  const header = page.locator('th[data-update-column="availableRevision"]');
  const before = (await header.boundingBox())!.width;
  await header.locator(".column-resizer").focus();
  await page.keyboard.press("ArrowRight");
  await expect
    .poll(async () => (await header.boundingBox())!.width)
    .toBeGreaterThan(before + 15);
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          Object.keys(
            JSON.parse(
              localStorage.getItem(
                "eplsync.updates.columnWidths.v3.settings.v1",
              ) || "{}",
            ).widths || {},
          ).length,
      ),
    )
    .toBe(5);
  await checkTableControls(page, "title", "Libro");
  expect(queries).toHaveLength(1);
  expect(queries[0].params.toString()).toBe("");
  expect(queries[0].states).toContain("SUBMITTED");
  expect(writes).toBe(0);
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect(page.locator("tbody tr")).toHaveCount(20);
  await page
    .getByRole("button", { name: "Revisión disponible", exact: true })
    .click();
  expect(queries).toHaveLength(1);
  expect(queries).toHaveLength(1);
  const statusFilter = page.getByRole("textbox", {
    name: "Estado registrado",
    exact: true,
  });
  expect((await statusFilter.boundingBox())!.width).toBeLessThanOrEqual(360);
  await statusFilter.click();
  await page
    .getByRole("option", { name: "No encontrado", exact: true })
    .click();
  await expect(page.locator("tbody tr")).toHaveCount(1);
  expect(queries).toHaveLength(1);
  await statusFilter.press("Escape");
  await page.getByRole("checkbox", { name: "EPL 123", exact: true }).check();
  await page
    .getByRole("button", { name: "Enviar actualizaciones (1)", exact: true })
    .click();
  const wizard = page.getByRole("dialog");
  const deleteHistory = wizard.getByRole("checkbox", {
    name: "Borrar también el registro de EPL Sync",
  });
  await expect(deleteHistory).not.toBeChecked();
  await deleteHistory.check();
  await wizard.getByRole("textbox", { name: "Acción", exact: true }).click();
  await page
    .getByRole("option", { name: "Eliminar torrents y archivos", exact: true })
    .click();
  await expect(
    wizard.getByRole("textbox", { name: "Momento del borrado", exact: true }),
  ).toHaveValue("Cuando la nueva revisión esté descargada");
  await wizard
    .getByRole("textbox", { name: "Momento del borrado", exact: true })
    .click();
  await page
    .getByRole("option", { name: "Inmediatamente", exact: true })
    .click();
  await expect(
    wizard.getByRole("checkbox", { name: /Entiendo que se eliminarán/ }),
  ).toHaveCount(0);
  await expect(wizard).not.toContainText(
    "La limpieza se realizará automáticamente cuando la nueva revisión esté completamente descargada.",
  );
  await page.screenshot({
    path: "/tmp/eplsync-send-two-columns.png",
    fullPage: true,
  });
  await wizard.getByRole("button", { name: /Crear trabajo/ }).click();
  await expect.poll(() => submission?.ids).toEqual([123]);
  expect(submission.previousVersions).toBe("removeTorrentAndFiles");
  expect(submission.confirmFiles).toBe(true);
  expect(submission.deleteHistory).toBe(true);
  expect(submission.cleanupTiming).toBe("immediate");
  expect(submission.states).toContain("NOT_FOUND");
});

test("failed synchronization does not show results or change saved settings", async ({
  page,
}) => {
  await page.route("**/api/settings/downloads/updates", (route) =>
    fulfillSavedSearch(route, { json: { states: ["DOWNLOADED"] } }),
  );
  let persistedSearch: Record<string, unknown> | null = null;
  await page.route("**/api/settings/tasks/searches/downloads**", (route) => {
    if (route.request().method() === "GET")
      return fulfillSavedSearch(route, { json: { search: persistedSearch } });
    return fulfillSavedSearch(route, {
      status: 503,
      json: {
        code: "TORRENT_CONNECTION_FAILED",
        details: "No se puede conectar con el cliente torrent",
      },
    });
  });
  await page.goto("/downloads/updates");
  await page
    .getByRole("button", { name: "Buscar actualizaciones", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Buscar", exact: true })
    .click();
  await expect(
    page
      .getByRole("dialog")
      .getByRole("button", { name: "Reintentar", exact: true }),
  ).toBeVisible();
  await expect(page.locator("tbody")).toContainText(
    "No hay actualizaciones pendientes.",
  );
});

test("last search, page, filter and ordering survive route changes and covers match catalog", async ({
  page,
}) => {
  let requests = 0;
  await page.route("**/api/settings/downloads/updates", (route) =>
    fulfillSavedSearch(route, {
      json: { states: ["DOWNLOADED", "NOT_FOUND"] },
    }),
  );
  await page.route("https://covers.example/123.jpg", (route) =>
    fulfillSavedSearch(route, {
      contentType: "image/svg+xml",
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="44" height="64"><rect width="44" height="64" fill="navy"/></svg>',
    }),
  );
  let persistedSearch: Record<string, unknown> | null = null;
  await page.route("**/api/settings/tasks/searches/downloads**", (route) => {
    if (route.request().method() === "GET")
      return fulfillSavedSearch(route, { json: { search: persistedSearch } });

    requests++;
    const params = new URL(route.request().url()).searchParams;
    const current = Number(params.get("page") ?? 0);
    return fulfillSavedSearch(route, {
      json: (persistedSearch = {
        rows: Array.from({ length: 40 }, (_, index) => ({
          eplId: 123 + current * 20 + index,
          title:
            index % 20 === 0
              ? "A Test book"
              : `Other book ${current * 20 + index}`,
          registeredRevision: 1.1,
          availableRevision: 1.2,
          status: "NOT_FOUND",
          coverUrl: "https://covers.example/123.jpg",
          coverAvailable: true,
        })),
        searchedAt: Date.now(),
        states: route.request().postDataJSON().states,
        origin: "MANUAL",
        criteria: { states: route.request().postDataJSON().states },
      }),
    });
  });
  await page.goto("/downloads/updates");
  await page
    .getByRole("button", { name: "Buscar actualizaciones", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Buscar", exact: true })
    .click();
  await expect(page.locator("th[data-update-column]").first()).toHaveAttribute(
    "data-update-column",
    "eplId",
  );
  const title = page
    .getByRole("link", { name: "A Test book", exact: true })
    .first();
  await expect(title.locator(".mini-book img")).toHaveAttribute(
    "src",
    "https://covers.example/123.jpg",
  );
  expect((await title.locator(".mini-book").boundingBox())!.width).toBe(44);
  await page
    .getByRole("button", { name: "Revisión disponible", exact: true })
    .click();
  const filter = page.getByRole("textbox", {
    name: "Estado registrado",
    exact: true,
  });
  await filter.click();
  await page
    .getByRole("option", { name: "No encontrado", exact: true })
    .click();
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Siguiente", exact: true }),
  ).toBeDisabled();
  await expect(title).toBeVisible();
  const previous = requests;
  await page
    .locator(".sidebar")
    .getByRole("link", { name: "Ajustes", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Ajustes", exact: true }),
  ).toBeVisible();
  await page
    .locator(".sidebar")
    .locator('a[href="/downloads/updates"]')
    .click();
  await expect(title).toBeVisible();
  await expect(
    page
      .locator(".filters .mantine-Pill-label")
      .filter({ hasText: "No encontrado" }),
  ).toBeVisible();
  await expect(
    page.getByRole("columnheader", { name: /Revisión disponible/ }),
  ).toHaveAttribute("aria-sort", "ascending");
  await expect(
    page.getByRole("button", { name: "Siguiente", exact: true }),
  ).toBeDisabled();
  await expect(page.getByText(/^Última búsqueda:/)).toBeVisible();
  expect(requests).toBe(previous);
});

test("opens the last scheduled catalog update search without synchronizing again", async ({
  page,
}) => {
  await page.route("**/api/settings/downloads/updates", (r) =>
    fulfillSavedSearch(r, { json: { states: ["DOWNLOADED"] } }),
  );
  await page.route("**/api/settings/tasks/searches/downloads**", (r) =>
    fulfillSavedSearch(r, {
      json: {
        search: {
          searchedAt: Date.now(),
          states: ["DOWNLOADED"],
          rows: [
            {
              eplId: 77182,
              title: "Actualización programada",
              registeredRevision: 1,
              availableRevision: 2,
              status: "DOWNLOADED",
              coverAvailable: false,
            },
          ],
        },
      },
    }),
  );
  await page.goto("/downloads/updates");
  await expect(
    page.getByRole("link", { name: "Actualización programada", exact: true }),
  ).toBeVisible();
  await expect(page.getByText(/Última búsqueda:/)).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole("link", { name: "Actualización programada", exact: true }),
  ).toBeVisible();
});

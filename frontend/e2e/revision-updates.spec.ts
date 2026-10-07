import { test, expect } from "./fixtures";
import { checkTableControls } from "./table-controls";

test("revision search synchronizes, uses temporary options, sorts and sends selected books", async ({
  page,
}) => {
  const queries: { params: URLSearchParams; states: string[] }[] = [];
  let writes = 0;
  await page.route("**/api/settings/downloads/updates", (route) => {
    if (route.request().method() === "PUT") writes++;
    return route.fulfill({ json: { states: ["DOWNLOADED", "NOT_FOUND"] } });
  });
  await page.route("**/api/torrent/revision-updates/search?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    queries.push({ params, states: route.request().postDataJSON().states });
    return route.fulfill({
      json: {
        items: [
          {
            eplId: 123,
            title: "Test book",
            registeredRevision: 1.1,
            availableRevision: 1.2,
            status: "NOT_FOUND",
          },
        ],
        meta: {
          page: Number(params.get("page") ?? 0),
          size: 20,
          totalItems: 40,
          totalPages: 2,
          hasNext: params.get("page") !== "1",
          hasPrevious: params.get("page") === "1",
        },
      },
    });
  });
  await page.route("**/api/torrent/options", (route) =>
    route.fulfill({
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
    route.fulfill({ json: [] }),
  );
  let submission: any;
  await page.route("**/api/torrent/revision-updates/send", (route) => {
    submission = route.request().postDataJSON();
    return route.fulfill({ json: { jobId: "update-job" } });
  });
  await page.route("**/api/torrent/jobs/update-job**", (route) =>
    route.fulfill({
      json: { items: [], jobId: "update-job", status: "COMPLETED" },
    }),
  );
  await page.goto("/downloads/updates");
  await expect(
    page
      .locator(".sidebar")
      .getByRole("link", { name: "Actualizaciones", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Buscar actualizaciones", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  const boxes = dialog.locator("input[type=checkbox]");
  await expect(boxes).toHaveCount(10);
  await boxes.first().check();
  await dialog
    .getByRole("button", { name: "Restaurar configuración guardada" })
    .click();
  await expect(boxes.first()).not.toBeChecked();
  await boxes.first().check();
  await dialog
    .getByRole("button", { name: "Buscar actualizaciones", exact: true })
    .click();
  await expect(
    page.getByRole("cell", { name: "Test book", exact: true }),
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
  expect(queries.some((q) => q.params.getAll("sort").length === 2)).toBe(true);
  expect(queries[0].params.get("synchronize")).toBe("true");
  expect(queries[0].states).toContain("SUBMITTED");
  expect(writes).toBe(0);
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect
    .poll(() => queries.some((q) => q.params.get("page") === "1"))
    .toBe(true);
  await page
    .getByRole("button", { name: "Revisión disponible", exact: true })
    .click();
  await expect
    .poll(() =>
      queries.some(
        (q) =>
          q.params.get("sort") === "availableRevision,asc" &&
          q.params.get("page") === "0",
      ),
    )
    .toBe(true);
  expect(
    queries.filter((q) => q.params.get("synchronize") === "true"),
  ).toHaveLength(1);
  const statusFilter = page.getByRole("textbox", {
    name: "Estado registrado",
    exact: true,
  });
  expect((await statusFilter.boundingBox())!.width).toBeLessThanOrEqual(360);
  await statusFilter.click();
  await page
    .getByRole("option", { name: "No encontrado", exact: true })
    .click();
  await expect
    .poll(() =>
      queries.some(
        (q) =>
          q.params.get("status") === "NOT_FOUND" &&
          q.params.get("page") === "0",
      ),
    )
    .toBe(true);
  await page.getByRole("checkbox", { name: "EPL 123", exact: true }).check();
  await page
    .getByRole("button", { name: "Enviar actualizaciones (1)", exact: true })
    .click();
  const wizard = page.getByRole("dialog");
  await wizard
    .getByRole("textbox", { name: "Versiones anteriores", exact: true })
    .click();
  await page
    .getByRole("option", { name: "Eliminar torrents y archivos", exact: true })
    .click();
  await expect(
    wizard.getByRole("button", { name: /Crear trabajo/ }),
  ).toBeDisabled();
  await wizard
    .getByRole("checkbox", { name: /Entiendo que se eliminarán/ })
    .check();
  await wizard.getByRole("button", { name: /Crear trabajo/ }).click();
  await expect.poll(() => submission?.ids).toEqual([123]);
  expect(submission.previousVersions).toBe("removeTorrentAndFiles");
  expect(submission.confirmFiles).toBe(true);
  expect(submission.states).toContain("NOT_FOUND");
});

test("failed synchronization does not show results or change saved settings", async ({
  page,
}) => {
  await page.route("**/api/settings/downloads/updates", (route) =>
    route.fulfill({ json: { states: ["DOWNLOADED"] } }),
  );
  await page.route("**/api/torrent/revision-updates/search?**", (route) =>
    route.fulfill({
      status: 503,
      json: {
        code: "TORRENT_CONNECTION_FAILED",
        details: "No se puede conectar con el cliente torrent",
      },
    }),
  );
  await page.goto("/downloads/updates");
  await page
    .getByRole("button", { name: "Buscar actualizaciones", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Buscar actualizaciones", exact: true })
    .click();
  await expect(
    page
      .getByRole("dialog")
      .getByRole("button", { name: "Reintentar", exact: true }),
  ).toBeVisible();
  await expect(page.locator("table")).toHaveCount(0);
});

test("last search, page, filter and ordering survive route changes and covers match catalog", async ({
  page,
}) => {
  let requests = 0;
  await page.route("**/api/settings/downloads/updates", (route) =>
    route.fulfill({ json: { states: ["DOWNLOADED", "NOT_FOUND"] } }),
  );
  await page.route("https://covers.example/123.jpg", (route) =>
    route.fulfill({
      contentType: "image/svg+xml",
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="44" height="64"><rect width="44" height="64" fill="navy"/></svg>',
    }),
  );
  await page.route("**/api/torrent/revision-updates/search?**", (route) => {
    requests++;
    const params = new URL(route.request().url()).searchParams;
    const current = Number(params.get("page") ?? 0);
    return route.fulfill({
      json: {
        items: [
          {
            eplId: 123,
            title: "Test book",
            registeredRevision: 1.1,
            availableRevision: 1.2,
            status: "NOT_FOUND",
            coverUrl: "https://covers.example/123.jpg",
            coverAvailable: true,
          },
        ],
        meta: {
          page: current,
          size: 20,
          totalItems: 40,
          totalPages: 2,
          hasNext: current === 0,
          hasPrevious: current === 1,
        },
      },
    });
  });
  await page.goto("/downloads/updates");
  await page
    .getByRole("button", { name: "Buscar actualizaciones", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Buscar actualizaciones", exact: true })
    .click();
  await expect(page.locator("th[data-update-column]").first()).toHaveAttribute(
    "data-update-column",
    "eplId",
  );
  const title = page.getByRole("link", { name: "Test book", exact: true });
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
  await page
    .locator(".sidebar")
    .getByRole("link", { name: "Actualizaciones", exact: true })
    .click();
  await expect(title).toBeVisible();
  await expect(filter).toHaveValue("No encontrado");
  await expect(
    page.getByRole("columnheader", { name: /Revisión disponible/ }),
  ).toHaveAttribute("aria-sort", "ascending");
  await expect(
    page.getByRole("button", { name: "Siguiente", exact: true }),
  ).toBeDisabled();
  await expect(page.getByText(/^Última búsqueda:/)).toBeVisible();
  expect(requests).toBe(previous);
});

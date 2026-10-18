import { test, expect, type Page } from "./fixtures";
const meta = {
  page: 0,
  size: 20,
  totalItems: 2,
  totalPages: 1,
  hasNext: false,
  hasPrevious: false,
};
const rows = [1, 2].map((id) => ({
  id: String(id),
  eplId: id,
  title: `Libro ${id}`,
  hash: String(id).repeat(40),
  revision: 1.1,
  status: "DOWNLOADED",
  client: "qbittorrent",
  clientInstanceId: "server",
  origin: "EPLSYNC",
  coverAvailable: false,
}));
async function records(page: Page) {
  await page.route("**/api/torrent/downloads?**", (r) =>
    r.fulfill({ json: { items: rows, meta } }),
  );
}
async function jobRoutes(page: Page) {
  await page.route("https://example.org/custom-cover.jpg", (r) =>
    r.fulfill({
      contentType: "image/svg+xml",
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="20" height="30"><rect width="20" height="30" fill="green"/></svg>',
    }),
  );
  let state = "QUEUED";
  const job = () => ({
    id: "job",
    createdAt: "2026-10-13T10:00:00Z",
    updatedAt: "2026-10-13T10:00:00Z",
    username: "Owner",
    library: "main",
    state,
    message: null,
    total: 1,
    pending: 1,
    accepted: 0,
    skipped: 0,
    failed: 0,
    uncertain: 0,
  });
  await page.route("**/api/calibre/transfers/job", (r) =>
    r.fulfill({ json: job() }),
  );
  await page.route("**/api/calibre/transfers/job/items?**", (r) =>
    r.fulfill({
      json: {
        items: [
          {
            id: "item",
            position: 24,
            recordId: "1",
            index: 0,
            name: "Libro.epub",
            coverUrl: "https://example.org/custom-cover.jpg",
            coverAvailable: true,
            size: 200,
            expectedSize: 250,
            eplId: 1,
            revision: "1.1",
            state: "PENDING",
            calibreId: null,
            message: null,
          },
        ],
        meta: { ...meta, totalItems: 1 },
      },
    }),
  );
  await page.route("**/api/calibre/transfers/job/pause", (r) => {
    state = "PAUSED";
    return r.fulfill({ json: job() });
  });
  await page.route("**/api/calibre/transfers/job/resume", (r) => {
    state = "QUEUED";
    return r.fulfill({ json: job() });
  });
  await page.route("**/api/calibre/transfers/job/cancel", (r) => {
    state = "CANCELLED";
    return r.fulfill({ json: job() });
  });
}
test("sends selected EPUB files to a library and controls its job", async ({
  page,
}) => {
  await records(page);
  await jobRoutes(page);
  await page.route("**/api/calibre/transfers/prepare", (r) => {
    expect(r.request().postDataJSON()).toEqual({ ids: ["1", "2"] });
    return r.fulfill({
      json: {
        library: "main",
        libraries: [{ id: "main", name: "Biblioteca principal" }],
        files: [
          {
            recordId: "1",
            index: 0,
            name: "Libro.epub",
            size: 200,
            expectedSize: 250,
            eplId: 1,
            revision: 1.1,
          },
          {
            recordId: "2",
            index: 3,
            name: "Otro.epub",
            size: 300,
            eplId: 2,
            revision: 1.1,
          },
        ],
      },
    });
  });
  await page.route("**/api/calibre/transfers", (r) => {
    expect(r.request().method()).toBe("POST");
    expect(r.request().postDataJSON()).toEqual({
      id: expect.any(String),
      library: "main",
      files: [{ recordId: "1", index: 0 }],
    });
    return r.fulfill({ json: { id: "job" } });
  });
  await page.goto("/downloads");
  await page
    .getByRole("checkbox", { name: "Seleccionar registros de esta página" })
    .check();
  await page
    .getByRole("button", { name: "Enviar a Calibre", exact: true })
    .first()
    .click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByText("Los archivos originales se conservan.", { exact: false }),
  ).toBeVisible();
  await expect(
    dialog.getByText(/El tamaño del EPUB no coincide/),
  ).toBeVisible();
  await expect(dialog.locator("legend", { hasText: "Libros" })).toBeVisible();
  await expect(dialog.locator(".app-modal-actions")).toContainText(
    "2 libros seleccionados",
  );
  await dialog.getByRole("checkbox", { name: /Otro.epub/ }).uncheck();
  await expect(dialog.locator(".app-modal-actions")).toContainText(
    "1 libro seleccionado",
  );
  await dialog
    .getByRole("button", { name: "Enviar a Calibre", exact: true })
    .click();
  await expect(page).toHaveURL(/\/calibre\/jobs\/job$/);
  await expect(page.getByRole("table")).toContainText("Libro.epub");
  await page.getByRole("button", { name: "Pausar", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Reanudar", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Reanudar", exact: true }).click();
  await page.getByRole("button", { name: "Cancelar", exact: true }).click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Cancelar trabajo", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Pausar", exact: true }),
  ).toHaveCount(0);
});
test("row sends only that record and reports preparation errors", async ({
  page,
}) => {
  await records(page);
  await page.route("**/api/calibre/transfers/prepare", (r) => {
    expect(r.request().postDataJSON()).toEqual({ ids: ["2"] });
    return r.fulfill({
      status: 409,
      json: { details: "El EPUB no está accesible" },
    });
  });
  await page.goto("/downloads");
  await page
    .getByRole("row")
    .filter({ hasText: "Libro 2" })
    .getByRole("button", { name: "Enviar a Calibre", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText(
    "El EPUB no está accesible",
  );
  await expect(
    page
      .getByRole("dialog")
      .getByRole("button", { name: "Enviar a Calibre", exact: true }),
  ).toBeDisabled();
});
test("source read without Calibre send permission cannot send or manage jobs", async ({
  page,
}) => {
  await page.route("**/api/auth/me", (r) =>
    r.fulfill({
      json: {
        id: "reader",
        username: "reader",
        role: "USER",
        mustChangePassword: false,
        permissions: ["TORRENT_SYNC", "DOWNLOADS_FILES_READ", "CALIBRE_READ"],
      },
    }),
  );
  await records(page);
  await page.goto("/downloads");
  await expect(
    page.getByRole("row").filter({ hasText: "Libro 1" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Enviar a Calibre", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("link", { name: "Trabajos de Calibre", exact: true }),
  ).toHaveCount(0);
  await page.goto("/calibre/jobs");
  await expect(
    page.getByText("No tienes permiso", { exact: false }),
  ).toBeVisible();
});

test("job validation errors stop polling and do not offer a useless retry", async ({
  page,
}) => {
  let requests = 0;
  await page.route("**/api/calibre/transfers?**", (route) => {
    requests++;
    return route.fulfill({
      status: 400,
      json: {
        status: 400,
        message: "Solicitud inválida",
        details: "Ordenación inválida",
      },
    });
  });
  await page.goto("/calibre/jobs");
  await expect(
    page.getByText("Ordenación inválida", { exact: true }),
  ).toBeVisible();
  // The stream handshake may invalidate queries once during initial navigation.
  await page.waitForTimeout(1200);
  const initialRequests = requests;
  await page.waitForTimeout(6200);
  expect(requests).toBe(initialRequests);
  await expect(
    page.getByRole("button", { name: "Reintentar", exact: true }),
  ).toHaveCount(0);
});

test("Calibre jobs filter by clicking state and user", async ({ page }) => {
  await page.route("**/api/calibre/transfers?**", (r) =>
    r.fulfill({
      json: {
        items: [
          {
            id: "job",
            createdAt: "2026-10-13T10:00:00Z",
            username: "Roberto",
            library: "main",
            state: "COMPLETED",
            total: 1,
            accepted: 1,
            failed: 0,
          },
        ],
        meta: { ...meta, totalItems: 1 },
      },
    }),
  );
  await page.goto("/calibre/jobs");
  await expect(
    page.getByRole("heading", { name: "Trabajos de Calibre", exact: true }),
  ).toBeVisible();
  await expect(page.locator('th[data-table-column="id"]')).toHaveText(
    /Trabajo/,
  );
  await expect(
    page.locator("tbody").getByRole("link", { name: "job", exact: true }),
  ).toHaveAttribute("href", "/calibre/jobs/job");
  const sorted = page.waitForRequest(
    (r) =>
      r.url().includes("/api/calibre/transfers?") &&
      new URL(r.url()).searchParams.getAll("sort").includes("id,asc"),
  );
  await page
    .locator("thead")
    .getByRole("button", { name: "Trabajo", exact: true })
    .click();
  await sorted;
  let request = page.waitForRequest(
    (r) =>
      r.url().includes("/api/calibre/transfers?") &&
      new URL(r.url()).searchParams.get("state") === "COMPLETED",
  );
  await page
    .locator("tbody")
    .getByRole("button", { name: "Terminado", exact: true })
    .click();
  await request;
  request = page.waitForRequest(
    (r) =>
      r.url().includes("/api/calibre/transfers?") &&
      new URL(r.url()).searchParams.get("username") === "Roberto",
  );
  await page
    .locator("tbody")
    .getByRole("button", { name: "Roberto", exact: true })
    .click();
  await request;
  await expect(page.getByLabel("Usuario", { exact: true })).toHaveValue(
    "Roberto",
  );
});

test("job rows use the catalog cover URL instead of assuming the default EPUBLibre path", async ({
  page,
}) => {
  await jobRoutes(page);
  await page.goto("/calibre/jobs/job");
  const image = page.locator(
    'tbody img[src="https://example.org/custom-cover.jpg"]',
  );
  await expect(image).toBeVisible();
  await expect(image).toHaveJSProperty("naturalWidth", 20);
});

test("send order is optional, hidden by default even with saved columns, and persists when enabled", async ({
  page,
}) => {
  await page.addInitScript(() => {
    if (!localStorage.getItem("eplsync.calibre.job.items.columns.settings.v1"))
      localStorage.setItem(
        "eplsync.calibre.job.items.columns.settings.v1",
        JSON.stringify({
          order: ["eplId", "name", "revision", "state", "calibreId", "message"],
          hidden: [],
          widths: {},
        }),
      );
  });
  await jobRoutes(page);
  await page.goto("/calibre/jobs/job");
  await expect(page.locator('th[data-table-column="position"]')).toHaveCount(0);
  await page.getByRole("button", { name: "Columnas", exact: true }).click();
  const toggle = page.getByRole("checkbox", {
    name: "Orden de envío",
    exact: true,
  });
  await expect(toggle).not.toBeChecked();
  await toggle.check();
  await page.keyboard.press("Escape");
  await expect(page.locator('th[data-table-column="position"]')).toBeVisible();
  await expect(page.getByRole("cell", { name: "25", exact: true })).toHaveText(
    "25",
  );
  await page.reload();
  await expect(page.locator('th[data-table-column="position"]')).toBeVisible();
});

test("filtra los elementos del trabajo por estado y detalle pulsando sus valores", async ({
  page,
}) => {
  await jobRoutes(page);
  await page.route("**/api/calibre/transfers/job/items?**", (r) =>
    r.fulfill({
      json: {
        items: [
          {
            id: "filtered",
            position: 0,
            eplId: 1,
            name: "Libro.epub",
            revision: "1",
            state: "ACCEPTED",
            message: "EPUB enviado y verificado",
          },
        ],
        meta: { ...meta, totalItems: 1 },
      },
    }),
  );
  await page.goto("/calibre/jobs/job");
  await page.getByRole("button", { name: "Enviado", exact: true }).click();
  await expect(
    page.getByRole("textbox", { name: "Estado", exact: true }),
  ).toHaveValue("");
  const request = page.waitForRequest(
    (r) =>
      r.url().includes("/job/items?") &&
      new URL(r.url()).searchParams.get("detail") ===
        "EPUB enviado y verificado" &&
      new URL(r.url()).searchParams.get("state") === "ACCEPTED",
  );
  await page
    .getByRole("button", { name: "EPUB enviado y verificado", exact: true })
    .click();
  await request;
  await expect(
    page.getByRole("textbox", { name: "Detalle contiene" }),
  ).toHaveValue("EPUB enviado y verificado");
  const waiting = page.waitForRequest(
    (r) =>
      r.url().includes("/job/items?") &&
      new URL(r.url()).searchParams.get("detail") === "esperando" &&
      new URL(r.url()).searchParams.get("page") === "0",
  );
  await page
    .getByRole("textbox", { name: "Detalle contiene" })
    .fill("esperando");
  await waiting;
  await page
    .getByRole("button", { name: "Limpiar: Detalle contiene", exact: true })
    .click();
  await expect(
    page.getByRole("textbox", { name: "Detalle contiene" }),
  ).toHaveValue("");
  await expect(
    page.getByRole("textbox", { name: "Estado", exact: true }),
  ).toHaveValue("");
  await page
    .getByRole("textbox", { name: "Detalle contiene" })
    .fill("verificado");
  const cleared = page.waitForRequest(
    (r) =>
      r.url().includes("/job/items?") &&
      new URL(r.url()).searchParams.get("detail") === "" &&
      new URL(r.url()).searchParams.get("state") === "" &&
      new URL(r.url()).searchParams.get("page") === "0",
  );
  await page.getByRole("button", { name: "Limpiar", exact: true }).click();
  await cleared;
  await expect(
    page.getByRole("textbox", { name: "Detalle contiene" }),
  ).toHaveValue("");
  await expect(
    page.getByRole("textbox", { name: "Estado", exact: true }),
  ).toHaveValue("");
  const labels = await page.locator(".jobs-filters label").allTextContents();
  expect(labels).toEqual(["EPL ID", "Archivo", "Estado", "Detalle contiene"]);
});

test("repeating a direct send after a lost response keeps its request identifier", async ({
  page,
}) => {
  await records(page);
  await jobRoutes(page);
  await page.route("**/api/calibre/transfers/prepare", (route) =>
    route.fulfill({
      json: {
        library: "main",
        libraries: [{ id: "main", name: "Main" }],
        files: [
          {
            recordId: "1",
            index: 0,
            name: "Book.epub",
            size: 200,
            expectedSize: 200,
            eplId: 1,
            revision: 1.1,
          },
        ],
      },
    }),
  );
  const ids: string[] = [];
  await page.route("**/api/calibre/transfers", (route) => {
    ids.push(route.request().postDataJSON().id);
    return ids.length === 1
      ? route.fulfill({ status: 503, json: { details: "Respuesta perdida" } })
      : route.fulfill({ json: { id: "job" } });
  });
  await page.goto("/downloads");
  await page
    .getByRole("checkbox", { name: "Seleccionar registros de esta página" })
    .check();
  await page
    .getByRole("button", { name: "Enviar a Calibre", exact: true })
    .first()
    .click();
  const modal = page.getByRole("dialog");
  await modal
    .getByRole("button", { name: "Enviar a Calibre", exact: true })
    .click();
  await expect(modal).toContainText("Respuesta perdida");
  await modal
    .getByRole("button", { name: "Enviar a Calibre", exact: true })
    .click();
  await expect(page).toHaveURL(/\/calibre\/jobs\/job$/);
  expect(ids).toHaveLength(2);
  expect(ids[0]).toBeTruthy();
  expect(ids[1]).toBe(ids[0]);
});

for (const allUnavailable of [false, true])
  test(`unavailable EPUBs stay disabled without blocking available sends (all=${allUnavailable})`, async ({
    page,
  }) => {
    await jobRoutes(page);
    const rows = Array.from({ length: 22 }, (_, index) => ({
      id: String(index + 1),
      eplId: index + 1,
      title: `Libro ${index + 1}`,
      hash: String(index + 1).padStart(40, "A"),
      revision: 1.1,
      status: "DOWNLOADED",
      client: "qbittorrent",
      clientInstanceId: "server",
      origin: "EPLSYNC",
      coverAvailable: false,
    }));
    await page.route("**/api/torrent/downloads?**", (route) => {
      const params = new URL(route.request().url()).searchParams,
        size = Number(params.get("size") ?? 20),
        p = Number(params.get("page") ?? 0);
      return route.fulfill({
        json: {
          items: rows.slice(p * size, (p + 1) * size),
          meta: {
            page: p,
            size,
            totalItems: 22,
            totalPages: Math.ceil(22 / size),
            hasNext: (p + 1) * size < 22,
            hasPrevious: p > 0,
          },
        },
      });
    });
    const available = allUnavailable
      ? []
      : rows.slice(0, 21).map((row) => ({
          recordId: row.id,
          index: 0,
          name: `Libro ${row.id}.epub`,
          size: 100,
          eplId: row.eplId,
          revision: row.revision,
        }));
    const unavailable = (allUnavailable ? rows : rows.slice(21)).map((row) => ({
      recordId: row.id,
      name: `EPL ID ${row.eplId}`,
      eplId: row.eplId,
      revision: row.revision,
      message: "Los archivos aún no están descargados",
    }));
    await page.route("**/api/calibre/transfers/prepare", (route) => {
      expect(route.request().postDataJSON().ids).toHaveLength(22);
      return route.fulfill({
        json: {
          libraries: [{ id: "main", name: "Main" }],
          library: "main",
          files: available,
          unavailable,
        },
      });
    });
    let submitted = 0;
    await page.route("**/api/calibre/transfers", (route) => {
      submitted++;
      expect(route.request().postDataJSON().files).toEqual(
        available.map((file) => ({ recordId: file.recordId, index: 0 })),
      );
      return route.fulfill({ json: { id: "job" } });
    });
    await page.goto("/downloads");
    await page
      .getByRole("button", { name: "Seleccionar todo (22)", exact: true })
      .click();
    await page
      .locator(".selection-toolbar-actions")
      .getByRole("button", { name: "Enviar a Calibre", exact: true })
      .click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.locator("tbody tr")).toHaveCount(20);
    await dialog.getByRole("button", { name: "EPL ID", exact: true }).click();
    await dialog.getByRole("button", { name: "EPL ID", exact: true }).click();
    const first = dialog.locator("tbody tr").first();
    await expect(first).toContainText("EPL ID 22");
    await expect(first).toContainText("Los archivos aún no están descargados");
    await expect(first.getByRole("checkbox")).toBeDisabled();
    await expect(first.getByRole("checkbox")).not.toBeChecked();
    const count = allUnavailable ? 0 : 21;
    await expect(dialog.locator(".app-modal-actions")).toContainText(
      `${count} libros seleccionados`,
    );
    await dialog
      .getByRole("button", { name: `Quitar selección (${count})`, exact: true })
      .click();
    await dialog
      .getByRole("button", { name: "Seleccionar disponibles", exact: true })
      .click();
    await expect(first.getByRole("checkbox")).not.toBeChecked();
    await dialog
      .getByRole("navigation", { name: "Páginas de la previsualización" })
      .getByRole("button", { name: "Siguiente", exact: true })
      .click();
    await expect(dialog.locator("tbody tr")).toHaveCount(2);
    const send = dialog.getByRole("button", {
      name: "Enviar a Calibre",
      exact: true,
    });
    if (allUnavailable) {
      await expect(send).toBeDisabled();
      expect(submitted).toBe(0);
    } else {
      await expect(send).toBeEnabled();
      await send.click();
      await expect(page).toHaveURL(/\/calibre\/jobs\/job$/);
      expect(submitted).toBe(1);
    }
  });

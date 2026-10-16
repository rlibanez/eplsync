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
  await page.route("https://example.org/custom-cover.jpg",r=>r.fulfill({contentType:"image/svg+xml",body:'<svg xmlns="http://www.w3.org/2000/svg" width="20" height="30"><rect width="20" height="30" fill="green"/></svg>'}));
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
  await dialog.getByRole("checkbox", { name: /Otro.epub/ }).uncheck();
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
    .getByRole("button", { name: "Cancelar", exact: true })
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

test("job validation errors stop polling until the user retries", async ({
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
  await page.getByRole("button", { name: "Reintentar", exact: true }).click();
  await expect.poll(() => requests).toBe(initialRequests + 1);
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
  await expect(page.locator('th[data-table-column="id"]')).toHaveText(/Trabajo/);
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

test("job rows use the catalog cover URL instead of assuming the default EPUBLibre path", async ({page}) => {
  await jobRoutes(page);
  await page.goto("/calibre/jobs/job");
  const image=page.locator('tbody img[src="https://example.org/custom-cover.jpg"]');
  await expect(image).toBeVisible();
  await expect(image).toHaveJSProperty("naturalWidth",20);
});

test("send order is optional, hidden by default even with saved columns, and persists when enabled", async ({page}) => {
  await page.addInitScript(() => { if (!localStorage.getItem("eplsync.calibre.job.items.columns.settings.v1")) localStorage.setItem("eplsync.calibre.job.items.columns.settings.v1",JSON.stringify({order:["eplId","name","revision","state","calibreId","message"],hidden:[],widths:{}})); });
  await jobRoutes(page);
  await page.goto("/calibre/jobs/job");
  await expect(page.locator('th[data-table-column="position"]')).toHaveCount(0);
  await page.getByRole("button",{name:"Columnas",exact:true}).click();
  const toggle=page.getByRole("checkbox",{name:"Orden de envío",exact:true});
  await expect(toggle).not.toBeChecked();await toggle.check();await page.keyboard.press("Escape");
  await expect(page.locator('th[data-table-column="position"]')).toBeVisible();
  await expect(page.getByRole("cell",{name:"25",exact:true})).toHaveText("25");
  await page.reload();
  await expect(page.locator('th[data-table-column="position"]')).toBeVisible();
});

test("filtra los elementos del trabajo por estado y detalle pulsando sus valores", async ({ page }) => {
  await jobRoutes(page);
  await page.route("**/api/calibre/transfers/job/items?**", r => r.fulfill({json: {
    items: [{id:"filtered",position:0,eplId:1,name:"Libro.epub",revision:"1",state:"ACCEPTED",message:"EPUB enviado y verificado"}],
    meta: {...meta,totalItems:1}
  }}));
  await page.goto("/calibre/jobs/job");
  await page.getByRole("button", { name: "Enviado", exact:true }).click();
  await expect(page.getByRole("textbox", {name:"Estado",exact:true})).toHaveValue("Enviado");
  const request = page.waitForRequest(r => r.url().includes("/job/items?") && new URL(r.url()).searchParams.get("detail") === "EPUB enviado y verificado" && new URL(r.url()).searchParams.get("state") === "ACCEPTED");
  await page.getByRole("button", {name:"EPUB enviado y verificado",exact:true}).click();
  await request;
  await expect(page.getByRole("textbox", {name:"Detalle contiene"})).toHaveValue("EPUB enviado y verificado");
  const waiting = page.waitForRequest(r => r.url().includes("/job/items?") && new URL(r.url()).searchParams.get("detail") === "esperando" && new URL(r.url()).searchParams.get("page") === "0");
  await page.getByRole("textbox", {name:"Detalle contiene"}).fill("esperando");
  await waiting;
  await page.getByRole("button", {name:"Limpiar: Detalle contiene",exact:true}).click();
  await expect(page.getByRole("textbox", {name:"Detalle contiene"})).toHaveValue("");
  await expect(page.getByRole("textbox", {name:"Estado",exact:true})).toHaveValue("Enviado");
  await page.getByRole("textbox", {name:"Detalle contiene"}).fill("verificado");
  const cleared = page.waitForRequest(r => r.url().includes("/job/items?") && new URL(r.url()).searchParams.get("detail") === "" && new URL(r.url()).searchParams.get("state") === "" && new URL(r.url()).searchParams.get("page") === "0");
  await page.getByRole("button", {name:"Limpiar",exact:true}).click();
  await cleared;
  await expect(page.getByRole("textbox", {name:"Detalle contiene"})).toHaveValue("");
  await expect(page.getByRole("textbox", {name:"Estado",exact:true})).toHaveValue("");
  const labels = await page.locator(".jobs-filters label").allTextContents();
  expect(labels).toEqual(["Estado", "Detalle contiene"]);
});

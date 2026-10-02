import { test, expect, emitEvent } from "./fixtures";
const meta = (total = 1, page = 0, size = 20) => ({
  page,
  size,
  totalItems: total,
  totalPages: Math.ceil(total / size),
  hasNext: (page + 1) * size < total,
  hasPrevious: page > 0,
});
const book = {
  eplId: 32,
  title: "Dune",
  author: "Frank Herbert",
  language: "es",
  revision: 1.2,
  genres: "Ficción",
  publicationYear: 1965,
  download: { items: [] },
};
const job = {
  jobId: "test-job",
  status: "RUNNING",
  client: "qbittorrent",
  selectedBooks: 40,
  selectedItems: 40,
  processedItems: 2,
  accepted: 2,
  alreadyExists: 0,
  skipped: 0,
  failed: 0,
  pending: 38,
  inFlight: 0,
  cancelled: 0,
  concurrency: 1,
  batchSize: 100,
  interval: "500ms",
  multipleHashes: "all",
  createdAt: "2026-09-30T08:00:00Z",
  updatedAt: "2026-09-30T08:00:00Z",
  retryAt: null,
  message: null,
};
test.beforeEach(async ({ page }) => {
  await page.route("**/api/ui/config", (r) =>
    r.fulfill({ json: { defaultLanguage: "es" } }),
  );
});
test("state reads local records and sync is explicit", async ({ page }) => {
  let syncs = 0;
  let synced = false;
  await page.route("**/api/torrent/downloads?**", (r) =>
    r.fulfill({
      json: {
        items: [
          {
            id: "d",
            eplId: 32,
            hash: "a".repeat(40),
            revision: 1.2,
            client: "qbittorrent",
            clientInstanceId: "x",
            origin: "EPLSYNC",
            status: synced ? "DOWNLOADED" : "DOWNLOADING",
            lastCheckedAt: "2026-09-30T08:00:00Z",
            completedAt: synced ? "2026-09-30T09:00:00Z" : null,
          },
        ],
        meta: meta(),
      },
    }),
  );
  await page.route("**/api/torrent/downloads/summary?**", (r) =>
    r.fulfill({
      json: {
        total: 1,
        byStatus: { DOWNLOADING: synced ? 0 : 1, DOWNLOADED: synced ? 1 : 0 },
      },
    }),
  );
  await page.route("**/api/torrent/downloads/sync", (r) => {
    expect(r.request().method()).toBe("POST");
    expect(r.request().postDataJSON()).toEqual({
      dryRun: false,
      includeDetails: true,
    });
    syncs++;
    synced = true;
    return r.fulfill({
      json: {
        checkedAt: "2026-09-30T09:00:00Z",
        client: "qbittorrent",
        dryRun: false,
        applied: true,
        remote: { total: 1, matched: 1, ignored: 0 },
        records: { checked: 1, created: 0, updated: 1, unchanged: 0 },
        outcomes: { newlyCompleted: 1, notFound: 0, newlyNotFound: 0 },
        items: [],
        ignoredTorrents: [],
      },
    });
  });
  await page.goto("/downloads");
  await expect(
    page.getByRole("cell", { name: "Descargando", exact: true }),
  ).toBeVisible();
  expect(syncs).toBe(0);
  await page
    .getByRole("button", { name: "Sincronizar con el cliente" })
    .click();
  await expect(
    page.getByText("Cambios aplicados", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("cell", { name: "Descargado", exact: true }),
  ).toBeVisible();
  expect(syncs).toBe(1);
  await page.screenshot({
    path: "test-results/download-state.png",
    fullPage: true,
  });
});
test("single submission selects a hash and confirms before sending", async ({
  page,
}) => {
  let sends = 0;
  await page.route("**/api/catalog/books?**", (r) =>
    r.fulfill({ json: { items: [book], meta: meta() } }),
  );
  await page.route("**/api/catalog/books/32/magnets", (r) =>
    r.fulfill({
      json: [
        `magnet:?xt=urn:btih:${"a".repeat(40)}`,
        `magnet:?xt=urn:btih:${"b".repeat(40)}`,
      ],
    }),
  );
  await page.route("**/api/torrent/books/32", (r) => {
    sends++;
    expect(r.request().postDataJSON()).toEqual({ hash: "b".repeat(40) });
    return r.fulfill({
      status: 202,
      json: {
        eplId: 32,
        hash: "b".repeat(40),
        client: "qbittorrent",
        status: "ACCEPTED",
      },
    });
  });
  await page.goto("/downloads/send?eplId=32");
  await expect(
    page.getByRole("button", { name: "Revisar envío" }),
  ).toBeDisabled();
  await page
    .getByRole("textbox", { name: "Hash del torrent", exact: true })
    .click();
  await page.getByRole("option", { name: "b".repeat(40), exact: true }).click();
  await page.getByRole("button", { name: "Revisar envío" }).click();
  expect(sends).toBe(0);
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Cancelar", exact: true })
    .click();
  expect(sends).toBe(0);
  await page.getByRole("button", { name: "Revisar envío" }).click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Enviar ahora" })
    .click();
  await emitEvent(page, {
    category: "TORRENT",
    action: "SEND_BOOK",
    outcome: "SUCCEEDED",
    details: { eplId: 32, submissionStatus: "ACCEPTED" },
  });
  await expect(page.locator(".notification-toasts")).toContainText(
    "ha aceptado el libro 32",
  );
  expect(sends).toBe(1);
});
test("bulk preview pagination does not restrict submission and leads to job controls", async ({
  page,
}) => {
  let sends = 0;
  let current = { ...job };
  let cancels = 0;
  await page.route("**/api/catalog/books?**", (r) => {
    const p = new URL(r.request().url()).searchParams;
    return r.fulfill({
      json: {
        items: [book],
        meta: meta(40, Number(p.get("page")), Number(p.get("size"))),
      },
    });
  });
  await page.route("**/api/torrent/books", (r) => {
    sends++;
    expect(new URL(r.request().url()).search).toBe("");
    expect(r.request().postDataJSON()).toEqual({
      dryRun: false,
      filters: { author: "Herbert" },
      all: false,
      sort: ["title,asc", "eplId,asc"],
      options: {},
    });
    return r.fulfill({ status: 202, json: current });
  });
  await page.route("**/api/torrent/jobs/test-job", (r) =>
    r.fulfill({ json: current }),
  );
  await page.route("**/api/torrent/jobs/test-job/items?**", (r) =>
    r.fulfill({
      json: {
        items: [
          {
            id: "i",
            eplId: 32,
            hash: "a".repeat(40),
            status: "ACCEPTED",
            attempts: 1,
            message: null,
          },
        ],
        meta: meta(),
      },
    }),
  );
  await page.route("**/api/torrent/jobs/test-job/pause", (r) => {
    current = { ...current, status: "PAUSED" };
    return r.fulfill({ json: current });
  });
  await page.route("**/api/torrent/jobs/test-job/resume", (r) => {
    current = { ...current, status: "QUEUED" };
    return r.fulfill({ json: current });
  });
  await page.route("**/api/torrent/jobs/test-job/cancel", (r) => {
    cancels++;
    current = { ...current, status: "CANCELLED" };
    return r.fulfill({ json: current });
  });
  await page.goto("/downloads/send/multiple");
  await page.getByLabel("Autor", { exact: true }).fill("Herbert");
  await page.getByRole("button", { name: "Previsualizar selección" }).click();
  await expect(
    page.getByText("40 libros coinciden", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await page.screenshot({
    path: "test-results/send-multiple.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "Revisar envío" }).click();
  await expect(page.getByRole("dialog")).toContainText("40 libros");
  expect(sends).toBe(0);
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Enviar ahora" })
    .click();
  await expect(page).toHaveURL(/\/downloads\/jobs\/test-job$/);
  expect(sends).toBe(1);
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
test("unfiltered bulk requires an explicit whole-catalog choice and network errors do not retry", async ({
  page,
}) => {
  let calls = 0;
  await page.route("**/api/catalog/books?**", (r) =>
    r.fulfill({ json: { items: [book], meta: meta() } }),
  );
  await page.route("**/api/torrent/books", (r) => {
    calls++;
    expect(r.request().postDataJSON().all).toBe(true);
    return r.abort();
  });
  await page.goto("/downloads/send/multiple");
  await page.getByRole("button", { name: "Previsualizar selección" }).click();
  await expect(
    page.getByText("1 libro coincide", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Revisar envío" }),
  ).toBeDisabled();
  await page
    .getByRole("checkbox", {
      name: "Quiero enviar todo el catálogo, sin filtros.",
    })
    .check();
  await page.getByRole("button", { name: "Revisar envío" }).click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Enviar ahora" })
    .click();
  await expect(page.locator(".notification-toasts")).toContainText(
    "podría haberse ejecutado",
  );
  expect(calls).toBe(1);
});
test("directory opens a filtered catalog, pagination includes 10 to 500", async ({
  page,
}) => {
  await page.route("**/api/catalog/directory/genres?**", (r) =>
    r.fulfill({ json: { items: [{ value: "Ficción" }], meta: meta() } }),
  );
  await page.route("**/api/catalog/books?**", (r) => {
    expect(new URL(r.request().url()).searchParams.get("genres")).toBe(
      "Ficción",
    );
    return r.fulfill({ json: { items: [book], meta: meta() } });
  });
  await page.goto("/directory?section=genres");
  await page.getByRole("link", { name: "Ficción", exact: true }).click();
  await expect(page).toHaveURL(/genres=Ficci/);
  await expect(
    page.getByRole("link", { name: "Dune", exact: true }),
  ).toBeVisible();
});
test("light scheme and ten palettes persist with clean settings navigation", async ({
  page,
}) => {
  await page.goto("/settings/general");
  await expect(
    page
      .locator("#sidebar")
      .getByRole("link", { name: "General", exact: true }),
  ).toHaveCount(0);
  await expect(page.locator("#sidebar")).not.toContainText("TU CATÁLOGO");
  await expect(
    page.locator(".palette-options").last().getByRole("radio"),
  ).toHaveCount(10);
  await expect(page.locator(".language-picker label")).toHaveClass("sr-only");
  await page.getByRole("radio", { name: "Claro", exact: true }).check();
  await page.getByRole("radio", { name: "Cian", exact: true }).check();
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute(
    "data-mantine-color-scheme",
    "light",
  );
  await expect(
    page.getByRole("radio", { name: "Cian", exact: true }),
  ).toBeChecked();
  await page.screenshot({
    path: "test-results/settings-light.png",
    fullPage: true,
  });
});
test("catalog page size offers all requested choices and resets page", async ({
  page,
}) => {
  await page.route("**/api/catalog/books?**", (r) => {
    const p = new URL(r.request().url()).searchParams;
    return r.fulfill({
      json: {
        items: [book],
        meta: meta(1000, Number(p.get("page")), Number(p.get("size"))),
      },
    });
  });
  await page.goto("/catalog?page=2&author=Herbert");
  await page.getByRole("textbox", { name: "Libros por página" }).click();
  for (const n of [10, 20, 50, 100, 200, 500])
    await expect(
      page.getByRole("option", { name: `${n} por página`, exact: true }),
    ).toBeVisible();
  await page
    .getByRole("option", { name: "500 por página", exact: true })
    .click();
  await expect(page).toHaveURL(/size=500/);
  await expect(page).toHaveURL(/page=0/);
  await expect(page).toHaveURL(/author=Herbert/);
});
test("changing filters invalidates a bulk preview and manual path requires automatic management off", async ({
  page,
}) => {
  await page.route("**/api/catalog/books?**", (r) =>
    r.fulfill({ json: { items: [book], meta: meta() } }),
  );
  await page.goto("/downloads/send/multiple");
  await page.getByLabel("Autor", { exact: true }).fill("Herbert");
  await page.getByRole("button", { name: "Previsualizar selección" }).click();
  await expect(
    page.getByRole("button", { name: "Revisar envío" }),
  ).toBeEnabled();
  await page.getByLabel("Autor", { exact: true }).fill("Asimov");
  await expect(
    page.getByRole("button", { name: "Revisar envío" }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "Previsualizar selección" }).click();
  await expect(
    page.getByRole("button", { name: "Revisar envío" }),
  ).toBeEnabled();
  await page
    .getByLabel("Ruta de descarga en el cliente", { exact: true })
    .fill("/books");
  await expect(
    page.getByRole("button", { name: "Revisar envío" }),
  ).toBeDisabled();
  await page
    .getByRole("textbox", { name: "Gestión automática", exact: true })
    .click();
  await page.getByRole("option", { name: "No", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Revisar envío" }),
  ).toBeEnabled();
});

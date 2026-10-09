import { test, expect, emitEvent } from "./fixtures";
const hash = "a".repeat(40);
const magnet = `magnet:?xt=urn:btih:${hash}&dn=Dune&tr=udp%3A%2F%2Ftracker.example%3A80`;
const book = {
  eplId: 32,
  title: "Dune",
  author: "Frank Herbert",
  revision: 1,
  language: "es",
  genres: null,
  publicationYear: 1965,
  pages: 600,
  rating: null,
  votesCount: null,
  volume: null,
  collection: null,
  publicationDate: null,
  insertDate: null,
  lastModifiedDate: null,
  publicationStatus: "PUBLISHED",
  status: "DISPONIBLE",
  synopsis: "Arrakis",
  download: { items: [] },
};
test.beforeEach(async ({ context }) => {
  await context.route("**/api/ui/config", (r) =>
    r.fulfill({ json: { defaultLanguage: "es" } }),
  );
  await context.route("**/api/catalog/books/32", (r) =>
    r.fulfill({ json: book }),
  );
  await context.route("**/api/catalog/books/32/magnets", (r) =>
    r.fulfill({ json: [magnet] }),
  );
});
const defaults = {
  start: true,
  autoManagement: true,
  savePath: null,
  rename: { enabled: true, pattern: "{author} - {title}" },
  category: "Libros",
  tags: ["EPLSync"],
  concurrency: 1,
  batchSize: 100,
  interval: "500ms",
  multipleHashes: "skip",
};
test.beforeEach(async ({ page }) => {
  await page.route("**/api/torrent/jobs/single-job", (r) =>
    r.fulfill({
      json: {
        jobId: "single-job",
        status: "COMPLETED",
        selectedBooks: 1,
        selectedItems: 1,
        processedItems: 1,
        accepted: 1,
        alreadyExists: 0,
        skipped: 0,
        failed: 0,
        pending: 0,
        inFlight: 0,
        cancelled: 0,
        concurrency: 1,
        batchSize: 100,
        interval: "500ms",
        multipleHashes: "skip",
        createdAt: "2026-10-09T08:00:00Z",
        updatedAt: "2026-10-09T08:00:01Z",
      },
    }),
  );
  await page.route("**/api/torrent/jobs/single-job/items?**", (r) =>
    r.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
      },
    }),
  );
  await page.route("**/api/torrent/options", (r) =>
    r.fulfill({ json: defaults }),
  );
  await page.route("**/api/torrent/client/categories", (r) =>
    r.fulfill({ json: ["Libros"] }),
  );
});
async function openSend(page: import("@playwright/test").Page) {
  await page
    .getByRole("button", { name: "Enviar a descargar", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByRole("button", { name: "Crear trabajo" }),
  ).toBeEnabled();
  return dialog;
}
test("detail opens the send wizard and submits its defaults once", async ({
  page,
}) => {
  let calls = 0;
  let release: () => void = () => {};
  await page.route("**/api/torrent/books/32", async (r) => {
    calls++;
    expect(r.request().method()).toBe("POST");
    expect(r.request().postDataJSON()).toEqual({
      dryRun: false,
      concurrency: 1,
      batchSize: 100,
      interval: "500ms",
      options: {
        start: true,
        savePath: "",
        rename: defaults.rename,
        qbittorrent: {
          autoManagement: true,
          category: "Libros",
          tags: ["EPLSync"],
        },
        hash,
      },
    });
    await new Promise<void>((resolve) => (release = resolve));
    return r.fulfill({
      status: 202,
      json: { jobId: "single-job", status: "QUEUED", selectedBooks: 1 },
    });
  });
  await page.goto("/catalog/32");
  await expect(
    page.getByRole("link", { name: "Abrir magnet", exact: true }),
  ).toHaveAttribute("href", magnet);
  const dialog = await openSend(page);
  expect(calls).toBe(0);
  await dialog.getByRole("button", { name: "Crear trabajo" }).click();
  await expect(
    dialog.getByRole("button", { name: "Crear trabajo" }),
  ).toBeDisabled();
  await expect.poll(() => calls).toBe(1);
  release();
  await expect(dialog).toHaveCount(0);
  await expect(page).toHaveURL(/\/downloads\/jobs\/single-job$/);
});
test("multiple hashes select the intended torrent before opening the wizard", async ({
  page,
}) => {
  const second = "b".repeat(40);
  await page.route("**/api/catalog/books/32/magnets", (r) =>
    r.fulfill({ json: [magnet, `magnet:?xt=urn:btih:${second}`] }),
  );
  let body: Record<string, unknown> | undefined;
  await page.route("**/api/torrent/books/32", (r) => {
    body = r.request().postDataJSON();
    return r.fulfill({
      json: { jobId: "single-job", status: "QUEUED", selectedBooks: 1 },
    });
  });
  await page.goto("/catalog/32");
  await page
    .getByRole("button", { name: "Enviar a descargar", exact: true })
    .click();
  await page.getByRole("menuitem", { name: new RegExp(second) }).click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByRole("button", { name: "Crear trabajo" }),
  ).toBeEnabled();
  await dialog.getByRole("button", { name: "Crear trabajo" }).click();
  await expect
    .poll(() => body)
    .toMatchObject({
      options: {
        hash: second,
        rename: defaults.rename,
        qbittorrent: { category: "Libros" },
      },
    });
  await expect(dialog).toHaveCount(0);
  await expect(page).toHaveURL(/\/downloads\/jobs\/single-job$/);
  await page.goBack();
  await expect(
    page.getByRole("heading", { name: "Dune", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Abrir magnet", exact: true }).click();
  await expect(page.getByRole("menuitem").first()).toHaveAttribute(
    "href",
    magnet,
  );
});
test("failed send explains uncertainty without retrying", async ({ page }) => {
  let calls = 0;
  await page.route("**/api/torrent/books/32", (r) => {
    calls++;
    return r.abort();
  });
  await page.goto("/catalog/32");
  const dialog = await openSend(page);
  await dialog.getByRole("button", { name: "Crear trabajo" }).click();
  await expect(page.locator(".notification-toasts")).toContainText(
    "podría haberse ejecutado",
  );
  expect(calls).toBe(1);
});
test("table title opens the book in the current tab", async ({
  page,
  context,
}) => {
  await context.route("**/api/catalog/books?**", (r) =>
    r.fulfill({
      json: {
        items: [book],
        meta: {
          page: 0,
          size: 20,
          totalItems: 1,
          totalPages: 1,
          hasNext: false,
          hasPrevious: false,
        },
      },
    }),
  );
  await page.goto("/catalog");
  await expect(
    page.getByRole("link", { name: "Abrir «Dune» en una nueva pestaña" }),
  ).toHaveCount(0);
  await page.getByRole("link", { name: "Dune", exact: true }).click();
  await expect(page).toHaveURL(/\/catalog\/32$/);
});

test("backend submission failure produces only one notification", async ({
  page,
}) => {
  await page.route("**/api/torrent/books/32", (r) =>
    r.fulfill({
      status: 502,
      headers: { "X-EPLSync-Operation-Id": "single-failure" },
      json: { details: "Torrent client unavailable" },
    }),
  );
  await page.goto("/catalog/32");
  const dialog = await openSend(page);
  await dialog.getByRole("button", { name: "Crear trabajo" }).click();
  await expect(
    dialog.getByRole("button", { name: "Crear trabajo" }),
  ).toBeEnabled();
  await emitEvent(page, {
    category: "TORRENT",
    action: "SEND_BOOK",
    outcome: "FAILED",
    operationId: "single-failure",
    details: { eplId: 32 },
  });
  await expect(page.locator(".notification-toasts [role=alert]")).toHaveCount(
    1,
  );
});

test("detail has a compact heading and filters by each author and publication year", async ({
  page,
}) => {
  await page.route("**/api/catalog/books/32", (r) =>
    r.fulfill({ json: { ...book, author: "Frank Herbert & Brian Herbert" } }),
  );
  await page.route("**/api/catalog/books?**", (r) =>
    r.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
      },
    }),
  );
  await page.goto("/catalog/32");
  await expect(page.locator(".detail-heading .eyebrow")).toHaveCount(0);
  await expect(page.locator(".detail-heading .tags .badge")).toHaveText([
    "EPL 32",
    "Revisión 1",
  ]);
  await expect(page.locator(".author")).toHaveText(
    "Frank Herbert · Brian Herbert",
  );
  await page.locator(".author button").last().click();
  await expect
    .poll(() => new URL(page.url()).searchParams.get("author"))
    .toBe("Brian Herbert");
  await page.goBack();
  await page.locator("dd button").filter({ hasText: "1965" }).click();
  await expect
    .poll(() => new URL(page.url()).searchParams.get("publicationYearFrom"))
    .toBe("1965");
  await expect
    .poll(() => new URL(page.url()).searchParams.get("publicationYearTo"))
    .toBe("1965");
});

test("detail navigates across filtered catalog pages and returns with filters", async ({
  page,
}) => {
  await page.route("**/api/catalog/books?*", (r) => {
    const url = new URL(r.request().url());
    expect(url.searchParams.get("author")).toBe("Frank Herbert");
    const p = Number(url.searchParams.get("page") ?? 0);
    return r.fulfill({
      json: {
        items: [{ ...book, eplId: p === 0 ? 32 : 33 }],
        meta: { page: p, size: 1, totalItems: 2, totalPages: 2 },
      },
    });
  });
  await page.route("**/api/catalog/books/33/magnets", (r) =>
    r.fulfill({ json: [] }),
  );
  await page.route("**/api/catalog/books/33", (r) =>
    r.fulfill({ json: { ...book, eplId: 33 } }),
  );
  await page.goto("/catalog/32");
  await page.evaluate(() =>
    history.replaceState(
      {
        ...history.state,
        usr: {
          catalogSearch: "author=Frank+Herbert&size=1&page=0&sort=title,asc",
        },
      },
      "",
    ),
  );
  await page.reload();
  await expect(
    page
      .locator('.book-navigation [aria-disabled="true"]')
      .filter({ hasText: "Anterior" }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Siguiente", exact: true }).click();
  await expect(page).toHaveURL(/\/catalog\/33$/);
  await page.getByRole("link", { name: "Anterior", exact: true }).click();
  await expect(page).toHaveURL(/\/catalog\/32$/);
  await page
    .getByRole("link", { name: "Volver al catálogo", exact: true })
    .click();
  await expect
    .poll(() => new URL(page.url()).searchParams.get("author"))
    .toBe("Frank Herbert");
});

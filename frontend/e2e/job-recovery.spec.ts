import { test, expect } from "./fixtures";
for (const kind of ["downloads", "calibre"])
  test(`${kind}: cancelled sends keep refreshing until retry controls become available`, async ({
    page,
  }) => {
    let settled = false,
      historyReads = 0,
      jobReads = 0;
    const endpoint =
      kind === "downloads" ? "torrent/jobs" : "calibre/transfers";
    await page.route(`**/api/${endpoint}/**`, (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path.endsWith("/items"))
        return route.fulfill({
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
        });
      jobReads++;
      return route.fulfill({
        json: {
          id: "parent",
          jobId: "parent",
          type: "DOWNLOAD",
          state: "CANCELLED",
          status: "CANCELLED",
          client: "qbittorrent",
          username: "Owner",
          library: "main",
          createdAt: "2026-10-15T10:00:00Z",
          updatedAt: "2026-10-15T10:00:00Z",
          total: 1,
          selectedBooks: 1,
          selectedItems: 1,
          processedItems: settled ? 1 : 0,
          processedBooks: 0,
          selectedTorrents: 1,
          processedTorrents: 0,
          pending: 0,
          inFlight: settled ? 0 : 1,
          accepted: 0,
          alreadyExists: 0,
          skipped: 0,
          failed: settled ? 1 : 0,
          cancelled: 0,
          uncertain: 0,
          batchSize: 10,
          concurrency: 2,
          interval: "500ms",
        },
      });
    });
    await page.route(`**/api/job-retries/${kind}/parent`, (route) => {
      historyReads++;
      return route.fulfill({
        json: {
          source: null,
          retries: [],
          terminal: settled,
          settling: !settled,
          failed: settled ? 1 : 0,
          reason: null,
          attemptCount: 0,
        },
      });
    });
    await page.goto(`/${kind}/jobs/parent`);
    await expect.poll(() => historyReads).toBeGreaterThan(0);
    await page.waitForTimeout(500);
    const initialHistory = historyReads,
      initialJobs = jobReads;
    settled = true;
    await page.waitForTimeout(6500);
    await expect(
      page.getByRole("button", {
        name: "Reintentar fallidos (1)",
        exact: true,
      }),
    ).toBeVisible();
    expect(historyReads).toBeGreaterThan(initialHistory);
    if (kind === "downloads") expect(jobReads).toBeGreaterThan(initialJobs);
    await page.reload();
    await expect(
      page.getByRole("button", {
        name: "Reintentar fallidos (1)",
        exact: true,
      }),
    ).toBeVisible();
  });
test("unconfirmed Calibre upload can be verified explicitly without submitting another EPUB", async ({
  page,
}) => {
  let resolved = false;
  const meta = {
    page: 0,
    size: 20,
    totalItems: 1,
    totalPages: 1,
    hasNext: false,
    hasPrevious: false,
  };
  await page.route("**/api/calibre/transfers/parent", (route) =>
    route.fulfill({
      json: {
        id: "parent",
        state: "PAUSED",
        library: "main",
        username: "Owner",
        total: 1,
        pending: 0,
        accepted: resolved ? 1 : 0,
        failed: 0,
        skipped: 0,
        uncertain: resolved ? 0 : 1,
        createdAt: "2026-10-15T10:00:00Z",
        updatedAt: "2026-10-15T10:00:00Z",
      },
    }),
  );
  await page.route("**/api/calibre/transfers/parent/items?**", (route) =>
    route.fulfill({
      json: {
        items: [
          {
            id: "item",
            eplId: 1,
            recordId: "record",
            index: 0,
            position: 0,
            name: "Book.epub",
            revision: "1.1",
            state: resolved ? "ACCEPTED" : "UNCERTAIN",
            message: "No se ha confirmado el envío",
            calibreId: resolved ? 9 : null,
          },
        ],
        meta,
      },
    }),
  );
  await page.route("**/api/calibre/transfers/parent/resolution", (route) => {
    expect(route.request().postDataJSON()).toEqual({
      itemId: "item",
      action: "verify",
      calibreId: 9,
      confirm: true,
    });
    resolved = true;
    return route.fulfill({
      json: { id: "parent", state: "PAUSED", accepted: 1, uncertain: 0 },
    });
  });
  await page.goto("/calibre/jobs/parent");
  await page
    .getByRole("button", { name: "Resolver envío sin confirmar", exact: true })
    .click();
  const modal = page.getByRole("dialog");
  await expect(modal.getByText("Acción", { exact: true })).toBeVisible();
  await expect(
    modal.getByRole("button", { name: "Cancelar", exact: true }),
  ).toBeVisible();
  await expect(
    modal.getByRole("button", { name: "Aplicar resolución" }),
  ).toBeDisabled();
  await modal.getByLabel("ID de la entrada en Calibre").fill("9");
  await modal.getByRole("checkbox").check();
  await modal.getByRole("button", { name: "Aplicar resolución" }).click();
  await expect(modal).toHaveCount(0);
  await expect(
    page.locator("tbody").getByRole("button", { name: "Enviado", exact: true }),
  ).toBeVisible();
});

test("failed replacement offers retry without pretending cleanup is progressing", async ({
  page,
}) => {
  await page.route("**/api/torrent/jobs/parent", (route) =>
    route.fulfill({
      json: {
        jobId: "parent",
        type: "UPDATE",
        status: "PAUSED",
        client: "qbittorrent",
        selectedBooks: 1,
        selectedItems: 1,
        processedItems: 1,
        processedBooks: 1,
        selectedTorrents: 1,
        processedTorrents: 1,
        pending: 0,
        inFlight: 0,
        accepted: 0,
        alreadyExists: 0,
        skipped: 0,
        failed: 1,
        cancelled: 0,
        cleanup: {
          waiting: 1,
          blocked: 0,
          requested: 0,
          removed: 0,
          cancelled: 0,
        },
        createdAt: "2026-10-15T10:00:00Z",
        updatedAt: "2026-10-15T10:00:00Z",
        batchSize: 10,
        concurrency: 1,
        interval: "0ms",
      },
    }),
  );
  await page.route("**/api/torrent/jobs/parent/items?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
      },
    }),
  );
  await page.route("**/api/job-retries/downloads/parent", (route) =>
    route.fulfill({
      json: {
        source: null,
        retries: [],
        terminal: true,
        settling: false,
        failed: 1,
        attemptCount: 0,
      },
    }),
  );
  await page.goto("/downloads/jobs/parent");
  await expect(
    page.getByRole("button", { name: "Reintentar fallidos (1)", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Reanudar", exact: true }),
  ).toHaveCount(0);
});

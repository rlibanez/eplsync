import { test, expect } from "./fixtures";
for (const kind of ["downloads", "calibre"] as const) {
  test(`${kind} names can be edited, searched, sorted and cleared`, async ({
    page,
  }) => {
    const endpoint =
      kind === "downloads" ? "torrent/jobs" : "calibre/transfers";
    const meta = {
      page: 0,
      size: 20,
      totalItems: 1,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false,
    };
    let name: string | null = null;
    const job = () => ({
      id: "parent",
      jobId: "parent",
      name,
      type: kind === "downloads" ? "DOWNLOAD" : "SEND",
      status: "COMPLETED",
      state: "COMPLETED",
      client: "qbittorrent",
      library: "library",
      selectedBooks: 0,
      selectedItems: 0,
      processedItems: 0,
      total: 0,
      accepted: 0,
      failed: 0,
      pending: 0,
      inFlight: 0,
      skipped: 0,
      alreadyExists: 0,
      cancelled: 0,
      uncertain: 0,
      createdAt: "2026-11-21T10:00:00Z",
      updatedAt: "2026-11-21T10:00:00Z",
    });
    await page.route(`**/api/${endpoint}**`, (route) => {
      const url = new URL(route.request().url());
      if (url.pathname.endsWith("/items"))
        return route.fulfill({
          json: { items: [], meta: { ...meta, totalItems: 0 } },
        });
      return route.fulfill({
        json: url.pathname.endsWith(endpoint)
          ? { items: [job()], meta }
          : job(),
      });
    });
    await page.route(`**/api/settings/work/${kind}/parent/name`, (route) => {
      if (route.request().method() === "POST")
        name = route.request().postDataJSON().name.trim() || null;
      return route.fulfill({ json: { name } });
    });
    await page.goto(`/${kind}/jobs/parent`);
    await page.getByRole("button", { name: "Editar nombre" }).click();
    await page
      .getByRole("dialog")
      .getByLabel("Nombre", { exact: true })
      .fill("Catálogo completo 20261120");
    await page
      .getByRole("dialog")
      .getByRole("button", { name: "Guardar", exact: true })
      .click();
    await expect(
      page.getByRole("heading", {
        name: "Catálogo completo 20261120",
        exact: true,
      }),
    ).toBeVisible();
    await page.goto(`/${kind}/jobs`);
    await expect(
      page.getByRole("link", {
        name: "Catálogo completo 20261120",
        exact: true,
      }),
    ).toBeVisible();
    const search = page.waitForRequest(
      (request) =>
        new URL(request.url()).pathname.endsWith(endpoint) &&
        new URL(request.url()).searchParams.get("name") === "Catálogo",
    );
    await page.getByLabel("Nombre", { exact: true }).fill("Catálogo");
    await search;
    const sort = page.waitForRequest(
      (request) =>
        new URL(request.url()).pathname.endsWith(endpoint) &&
        (new URL(request.url()).searchParams.get("sort") ?? "").includes(
          "name",
        ),
    );
    await page.getByRole("button", { name: "Nombre", exact: true }).click();
    await sort;
    await page
      .getByRole("link", { name: "Catálogo completo 20261120", exact: true })
      .click();
    await page.getByRole("button", { name: "Editar nombre" }).click();
    await page
      .getByRole("dialog")
      .getByLabel("Nombre", { exact: true })
      .fill("");
    await page
      .getByRole("dialog")
      .getByRole("button", { name: "Guardar", exact: true })
      .click();
    await expect(page.getByRole("dialog")).toHaveCount(0);
    expect(name).toBeNull();
  });
}

for (const source of ["downloads", "calibre"] as const) {
  test(`${source} cleanup jobs can be renamed`, async ({ page }) => {
    const meta = {
      page: 0,
      size: 20,
      totalItems: 0,
      totalPages: 0,
      hasNext: false,
      hasPrevious: false,
    };
    let name: string | null = null;
    const job = () => ({
      id: "cleanup-test",
      jobId: "cleanup-test",
      name,
      source,
      type: "CLEANUP",
      action: source === "downloads" ? "FILES" : "BOOK",
      state: "COMPLETED",
      status: "COMPLETED",
      result: "PARTIAL",
      selected: 1,
      total: 1,
      selectedBooks: 1,
      selectedItems: 1,
      processedItems: 1,
      succeeded: 0,
      accepted: 0,
      blocked: 1,
      failed: 0,
      pending: 0,
      skipped: 0,
      uncertain: 0,
      library: "main",
      createdAt: "2026-11-22T09:00:00Z",
      updatedAt: "2026-11-22T09:00:00Z",
    });
    await page.route("**/api/revision-cleanups/cleanup-test", (route) =>
      route.fulfill({ json: job() }),
    );
    await page.route(
      "**/api/revision-cleanups/cleanup-test/items?**",
      (route) => route.fulfill({ json: { items: [], meta } }),
    );
    await page.route(
      "**/api/settings/work/cleanup/cleanup-test/name",
      (route) => {
        if (route.request().method() === "POST")
          name = route.request().postDataJSON().name.trim() || null;
        return route.fulfill({ json: { name } });
      },
    );
    const endpoint =
      source === "downloads" ? "torrent/jobs" : "calibre/transfers";
    await page.route(`**/api/${endpoint}?**`, (route) =>
      route.fulfill({
        json: {
          items: [job()],
          meta: { ...meta, totalItems: 1, totalPages: 1 },
        },
      }),
    );
    await page.goto(`/${source}/jobs/cleanup/cleanup-test`);
    await page
      .getByRole("button", { name: "Editar nombre del trabajo", exact: true })
      .click();
    const input = page
      .getByRole("dialog")
      .getByRole("textbox", { name: "Nombre", exact: true });
    await expect(input).toBeFocused();
    await input.fill("Limpieza de revisiones antiguas");
    await page
      .getByRole("dialog")
      .getByRole("button", { name: "Guardar", exact: true })
      .click();
    await expect(
      page.getByRole("heading", {
        name: "Limpieza de revisiones antiguas",
        exact: true,
      }),
    ).toBeVisible();
    await page.goto(`/${source}/jobs`);
    await expect(
      page.getByRole("link", {
        name: "Limpieza de revisiones antiguas",
        exact: true,
      }),
    ).toBeVisible();
  });
}

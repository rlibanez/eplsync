import { test, expect } from "./fixtures";
for (const kind of ["downloads", "calibre"] as const) {
  test(`${kind} retries failed items in place with confirmation, idempotent recovery and history`, async ({
    page,
  }) => {
    const meta = {
      page: 0,
      size: 20,
      totalItems: 1,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false,
    };
    const tokens: string[] = [];
    let attempts = 0;
    const endpoint =
      kind === "downloads" ? "torrent/jobs" : "calibre/transfers";
    await page.route(`**/api/${endpoint}/**`, (route) => {
      const path = new URL(route.request().url()).pathname;
      const child = path.includes("/child");
      if (path.endsWith("/items"))
        return route.fulfill({
          json: {
            items: [
              {
                id: child ? "child-item" : "item",
                eplId: 1,
                title: "Libro",
                name: "Libro.epub",
                hash: "A".repeat(40),
                revision: 1.1,
                status: "FAILED",
                state: "FAILED",
                attempts: 1,
                message: "Fallo de prueba",
                coverAvailable: false,
                calibreId: null,
              },
            ],
            meta,
          },
        });
      return route.fulfill({
        json: {
          jobId: child ? "child" : "parent",
          id: child ? "child" : "parent",
          type: "DOWNLOAD",
          status: "COMPLETED",
          state: "COMPLETED",
          client: "qbittorrent",
          username: "Owner",
          library: "main",
          selectedBooks: 1,
          selectedItems: 1,
          processedItems: 1,
          processedBooks: 1,
          selectedTorrents: 1,
          processedTorrents: 1,
          total: 1,
          pending: 0,
          inFlight: 0,
          accepted: 0,
          alreadyExists: 0,
          skipped: 0,
          failed: 1,
          cancelled: 0,
          uncertain: 0,
          concurrency: 2,
          batchSize: 10,
          interval: "500ms",
          createdAt: "2026-10-15T10:00:00Z",
          updatedAt: "2026-10-15T10:00:00Z",
        },
      });
    });
    await page.route(`**/api/job-retries/${kind}/**`, (route) => {
      const path = new URL(route.request().url()).pathname;
      if (route.request().method() === "GET" && path.endsWith("/attempts"))
        return route.fulfill({
          json: {
            items: [
              {
                id: "same-token",
                createdAt: 1792248000000,
                finishedAt: 1792248001000,
                username: "Owner",
                state: "COMPLETED",
                total: 1,
              },
            ],
            meta,
          },
        });
      if (route.request().method() === "GET" && path.endsWith("/items"))
        return route.fulfill({
          json: {
            items: [
              {
                id: "item",
                eplId: 1,
                name: "Libro.epub",
                phase: "SEND",
                beforeState: "FAILED",
                state: "ACCEPTED",
                beforeMessage: "Fallo de prueba",
                message: "Envío verificado",
              },
            ],
            meta,
          },
        });
      if (route.request().method() === "GET")
        return route.fulfill({
          json: {
            source: path.endsWith("child")
              ? { kind, jobId: "child", parentId: "parent", rootId: "parent" }
              : null,
            retries: [],
            terminal: true,
            failed: path.endsWith("child") ? 0 : 1,
            reason: null,
            attemptCount: attempts > 1 ? 1 : 0,
          },
        });
      if (path.endsWith("/preview")) {
        expect(route.request().postDataJSON()).toEqual({
          items: null,
          failedOnly: true,
        });
        return route.fulfill({
          json: {
            token: "same-token",
            send: 1,
            verify: 0,
            waiting: 0,
            cleanup: 0,
            excluded: 1,
            issues: [
              { id: "other", detail: "Este elemento ya terminó correctamente" },
            ],
            changes: ["El tamaño del archivo ha cambiado"],
            library: kind === "calibre" ? "main" : "",
          },
        });
      }
      const body = route.request().postDataJSON();
      tokens.push(body.token);
      expect(body.confirmChanges).toBe(true);
      if (++attempts === 1)
        return route.fulfill({
          status: 503,
          json: { details: "Respuesta perdida; vuelve a intentarlo" },
        });
      return route.fulfill({
        json: { kind, jobId: "parent", parentId: "parent", rootId: "parent" },
      });
    });
    await page.goto(`/${kind}/jobs/parent`);
    await expect(
      page
        .locator(".job-report-panel .job-controls-heading .action-row")
        .getByRole("button", { name: "Reintentar fallidos (1)", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", {
        name: "Reintentar fallidos (1)",
        exact: true,
      }),
    ).toHaveCount(1);
    await page
      .getByRole("button", { name: "Reintentar fallidos (1)", exact: true })
      .click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toContainText(
      "Este elemento ya terminó correctamente",
    );
    await expect(
      dialog.getByRole("button", { name: "Iniciar reintento", exact: true }),
    ).toBeDisabled();
    await dialog.getByRole("checkbox").check();
    await dialog
      .getByRole("button", { name: "Iniciar reintento", exact: true })
      .click();
    await expect(dialog).toContainText("Respuesta perdida");
    await dialog
      .getByRole("button", { name: "Reintentar", exact: true })
      .click();
    await expect(page).toHaveURL(new RegExp(`/${kind}/jobs/parent$`));
    await page
      .getByText("Historial de reintentos (1)", { exact: true })
      .click();
    await page.locator("details details > summary").click();
    await expect(
      page.getByText("Detalle anterior: Fallo de prueba"),
    ).toBeVisible();
    await expect(page.getByText("Resultado: Envío verificado")).toBeVisible();
    expect(tokens).toEqual(["same-token", "same-token"]);
    await page.locator("tbody").getByRole("checkbox").first().check();
    await expect(
      page.getByRole("button", {
        name: "Reintentar seleccionados (1)",
        exact: true,
      }),
    ).toBeVisible();
    await page
      .getByRole("button", { name: "Quitar selección (1)", exact: true })
      .click();
    await expect(
      page.getByRole("button", { name: /Reintentar seleccionados/ }),
    ).toHaveCount(0);
    await page.goto(`/${kind}/jobs/child`);
    await expect(
      page.getByRole("link", { name: "parent", exact: true }),
    ).toBeVisible();
  });
}

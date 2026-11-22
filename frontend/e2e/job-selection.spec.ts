import { test, expect } from "./fixtures";

for (const source of ["downloads", "calibre"] as const) {
  for (const individual of [false, true]) {
    test(`${source} ${individual ? "items" : "jobs"}: selects all filtered pages`, async ({
      page,
    }) => {
      const base =
        source === "downloads" ? "torrent/jobs" : "calibre/transfers";
      const requests: URLSearchParams[] = [];
      const job = {
        id: "job",
        jobId: "job",
        type: source === "downloads" ? "DOWNLOAD" : "SEND",
        state: "COMPLETED",
        status: "COMPLETED",
        total: 3,
        selectedItems: 3,
        selectedBooks: 3,
        processedItems: 3,
        accepted: 3,
        failed: 0,
        pending: 0,
        inFlight: 0,
        skipped: 0,
        cancelled: 0,
        uncertain: 0,
        alreadyExists: 0,
        client: "qbittorrent",
        library: "main",
        createdAt: "2026-11-22T10:00:00Z",
        updatedAt: "2026-11-22T10:00:00Z",
      };
      await page.route(`**/api/${base}/job`, (r) => r.fulfill({ json: job }));
      await page.route(`**/api/job-retries/${source}/job`, (r) =>
        r.fulfill({
          json: { terminal: true, retries: [], source: null, failed: 0 },
        }),
      );
      let recoverable = false;
      await page.route(`**/api/job-retries/${source}/job/recoverability`, (r) =>
        r.fulfill({
          json: {
            recoverable: recoverable ? 1 : 0,
            items: recoverable ? ["3"] : [],
          },
        }),
      );
      const endpoint = individual ? `${base}/job/items` : base;
      await page.route(`**/api/${endpoint}?**`, (r) => {
        const params = new URL(r.request().url()).searchParams;
        requests.push(params);
        const index = Number(params.get("page") ?? 0);
        const items = (index ? [3] : [1, 2]).map((n) => ({
          ...job,
          id: String(n),
          jobId: String(n),
          eplId: n,
          position: n,
          title: `Libro ${n}`,
          name: `Libro ${n}.epub`,
          author: "Autor",
          status: individual ? "ACCEPTED" : n === 3 ? "RUNNING" : "COMPLETED",
          state: individual ? "ACCEPTED" : "COMPLETED",
          attempts: 1,
        }));
        return r.fulfill({
          json: {
            items,
            meta: {
              page: index,
              size: 2,
              totalItems: 3,
              totalPages: 2,
              hasNext: index === 0,
              hasPrevious: index > 0,
            },
          },
        });
      });
      await page.goto(`/${source}/jobs${individual ? "/job" : ""}`);
      if (individual) {
        const filtered = page.waitForRequest(
          (request) =>
            new URL(request.url()).pathname.endsWith("/items") &&
            new URL(request.url()).searchParams.get("author") === "Autor",
        );
        await page
          .getByRole("textbox", { name: "Autor", exact: true })
          .fill("Autor");
        await filtered;
      } else {
        await page
          .getByRole("textbox", { name: "Nombre", exact: true })
          .fill("prueba");
      }
      await page
        .getByRole("button", { name: "Seleccionar todo (3)", exact: true })
        .click();
      await expect(
        page.getByRole("button", { name: "Quitar selección (3)", exact: true }),
      ).toBeVisible();
      expect(
        requests.some((p) => p.get("size") === "1000" && p.get("page") === "1"),
      ).toBe(true);
      const allRequests = requests.filter((p) => p.get("size") === "1000");
      expect(
        allRequests.every(
          (p) =>
            p.get(individual ? "author" : "name") ===
            (individual ? "Autor" : "prueba"),
        ),
      ).toBe(true);
      if (!individual && source === "downloads") {
        await expect(
          page.getByRole("button", { name: "Pausar (1)", exact: true }),
        ).toBeVisible();
      }
      if (individual) {
        await expect(
          page.getByRole("button", { name: /Reintentar seleccionados/ }),
        ).toHaveCount(0);
        await page
          .getByRole("button", { name: "Quitar selección (3)", exact: true })
          .click();
        recoverable = true;
        await page
          .getByRole("button", { name: "Seleccionar todo (3)", exact: true })
          .click();
        await expect(
          page.getByRole("button", {
            name: "Reintentar seleccionados (1)",
            exact: true,
          }),
        ).toBeVisible();
        await page.route(`**/api/job-retries/${source}/job/preview`, (r) => {
          expect(r.request().postDataJSON()).toEqual({
            items: ["3"],
            failedOnly: false,
          });
          return r.fulfill({
            json: {
              token: "preview",
              send: 1,
              verify: 0,
              waiting: 0,
              cleanup: 0,
              excluded: 0,
              issues: [],
              changes: [],
              library: "",
            },
          });
        });
        await page
          .getByRole("button", {
            name: "Reintentar seleccionados (1)",
            exact: true,
          })
          .click();
        await expect(page.getByRole("dialog")).toBeVisible();
      }
    });
  }
}

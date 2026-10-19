import { test, expect } from "./fixtures";

for (const kind of ["downloads", "calibre"] as const) {
  test(`${kind}: author follows book/file, supports exclusion, paging, sorting and clearing`, async ({
    page,
  }) => {
    const endpoint =
      kind === "downloads"
        ? "/torrent/jobs/author-job"
        : "/calibre/transfers/author-job";
    const storage =
      kind === "downloads"
        ? "eplsync.jobItems.columnWidths.v3"
        : "eplsync.calibre.job.items.columns";
    const preceding = kind === "downloads" ? "title" : "name";
    await page.addInitScript(
      ({ storage, preceding }) => {
        localStorage.setItem(
          `${storage}.settings.v1`,
          JSON.stringify({
            order: [
              "eplId",
              preceding,
              "revision",
              "status",
              "state",
              "message",
            ],
            hidden: [],
            widths: {},
          }),
        );
      },
      { storage, preceding },
    );
    const requests: URLSearchParams[] = [];
    await page.route(`**/api${endpoint}**`, (route) => {
      const params = new URL(route.request().url()).searchParams;
      if (new URL(route.request().url()).pathname.endsWith("/items")) {
        requests.push(params);
        const p = Number(params.get("page") ?? 0);
        return route.fulfill({
          json: {
            items: [
              {
                id: "item",
                position: p * 20,
                eplId: 1,
                title: "Libro",
                name: "Libro.epub",
                author: "Jane Austen",
                hash: "A".repeat(40),
                status: "ACCEPTED",
                state: "ACCEPTED",
                attempts: 1,
                revision: 1,
                size: 20,
                coverAvailable: false,
              },
            ],
            meta: {
              page: p,
              size: 20,
              totalItems: 40,
              totalPages: 2,
              hasPrevious: p > 0,
              hasNext: p === 0,
            },
          },
        });
      }
      return route.fulfill({
        json: {
          id: "author-job",
          jobId: "author-job",
          type: "DOWNLOAD",
          status: "COMPLETED",
          state: "COMPLETED",
          total: 40,
          accepted: 40,
          selectedBooks: 40,
          selectedItems: 40,
          processedItems: 40,
          failed: 0,
          pending: 0,
          skipped: 0,
          uncertain: 0,
          library: "main",
        },
      });
    });
    await page.goto(`/${kind}/jobs/author-job`);
    const author = page.getByRole("textbox", { name: "Autor", exact: true });
    await expect(author).toBeVisible();
    const labels = await page.locator(".jobs-filters label").allTextContents();
    expect(labels.indexOf("Autor")).toBe(
      labels.indexOf(kind === "downloads" ? "Libro" : "Archivo") + 1,
    );
    await expect(
      page.locator(`th[data-table-column="${preceding}"] + th`),
    ).toHaveAttribute("data-table-column", "author");
    await expect(
      page.getByRole("cell", { name: "Jane Austen", exact: true }),
    ).toHaveText("Jane Austen");
    for (const text of ["Jane", "George"]) {
      await author.fill(text);
      await author.press("Enter");
    }
    await expect
      .poll(() => requests.at(-1)?.getAll("author"))
      .toEqual(["Jane", "George"]);
    await page
      .locator(".jobs-filters > div")
      .filter({ has: author })
      .getByRole("button", {
        name: "Incluir valores seleccionados",
        exact: true,
      })
      .click();
    await page.getByRole("button", { name: "Siguiente", exact: true }).click();
    await expect.poll(() => requests.at(-1)?.get("page")).toBe("1");
    expect(requests.at(-1)?.get("authorOperator")).toBe("ne");
    await page
      .getByRole("button", { name: "Jane Austen", exact: true })
      .click();
    await expect
      .poll(() => requests.at(-1)?.getAll("author"))
      .toEqual(["Jane Austen"]);
    expect(requests.at(-1)?.get("page")).toBe("0");
    expect(requests.at(-1)?.has("authorOperator")).toBe(false);

    await page.getByRole("button", { name: "Autor", exact: true }).click();
    await expect.poll(() => requests.at(-1)?.get("sort")).toBe("author,asc");
    expect(requests.at(-1)?.get("page")).toBe("0");
    await page
      .getByRole("button", { name: "Limpiar: Autor", exact: true })
      .click();
    await expect.poll(() => requests.at(-1)?.getAll("author")).toEqual([]);
    expect(requests.at(-1)?.has("authorOperator")).toBe(false);
  });
}

for (const kind of ["downloads", "calibre"] as const) {
  test(`${kind}: author remains hidden without catalog permission`, async ({
    page,
  }) => {
    await page.route("**/api/auth/me", (route) =>
      route.fulfill({
        json: {
          id: "reader",
          username: "reader",
          role: "USER",
          permissions: [
            kind === "downloads"
              ? "TORRENT_JOBS_MANAGE"
              : "CALIBRE_JOBS_MANAGE",
          ],
          mustChangePassword: false,
        },
      }),
    );
    const endpoint =
      kind === "downloads"
        ? "/torrent/jobs/author-job"
        : "/calibre/transfers/author-job";
    await page.route(`**/api${endpoint}**`, (route) =>
      route.fulfill({
        json: new URL(route.request().url()).pathname.endsWith("/items")
          ? {
              items: [
                {
                  id: "item",
                  position: 0,
                  eplId: 1,
                  title: "Libro",
                  name: "Libro.epub",
                  author: "Private author",
                  hash: "A".repeat(40),
                  status: "ACCEPTED",
                  state: "ACCEPTED",
                  revision: 1,
                },
              ],
              meta: { page: 0, size: 20, totalItems: 1, totalPages: 1 },
            }
          : {
              id: "author-job",
              jobId: "author-job",
              status: "COMPLETED",
              state: "COMPLETED",
              total: 1,
              accepted: 1,
              failed: 0,
              selectedItems: 1,
              processedItems: 1,
              library: "main",
            },
      }),
    );
    await page.goto(`/${kind}/jobs/author-job`);
    await expect(
      page
        .getByRole("cell", { name: "Libro.epub", exact: true })
        .or(page.getByRole("cell", { name: "Libro", exact: true })),
    ).toBeVisible();
    await expect(
      page.getByRole("textbox", { name: "Autor", exact: true }),
    ).toHaveCount(0);
    await expect(page.locator('th[data-table-column="author"]')).toHaveCount(0);
    await expect(page.getByText("Private author", { exact: true })).toHaveCount(
      0,
    );
  });
}

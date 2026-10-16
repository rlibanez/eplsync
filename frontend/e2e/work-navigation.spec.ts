import { test, expect } from "./fixtures";

for (const source of ["downloads", "calibre"] as const) {
  test(`${source}: previous and next cross job types and restore the filtered list page`, async ({
    page,
  }) => {
    const row = {
      id: "first",
      jobId: "first",
      type: "CLEANUP",
      state: "COMPLETED",
      status: "COMPLETED",
      total: 1,
      selectedBooks: 1,
      selectedItems: 1,
      processedItems: 1,
      accepted: 1,
      pending: 0,
      failed: 0,
      skipped: 0,
      uncertain: 0,
      username: "Owner",
      library: "main",
      createdAt: "2026-10-16T10:00:00Z",
      updatedAt: "2026-10-16T10:00:00Z",
    };
    const meta = {
      page: 0,
      size: 20,
      totalItems: 21,
      totalPages: 2,
      hasNext: true,
      hasPrevious: false,
    };
    const lists: URLSearchParams[] = [];
    await page.route(
      source === "downloads"
        ? "**/api/torrent/jobs?**"
        : "**/api/calibre/transfers?**",
      (r) => {
        const params = new URL(r.request().url()).searchParams;
        lists.push(params);
        return r.fulfill({
          json: {
            items: [row],
            meta: { ...meta, page: Number(params.get("page")) },
          },
        });
      },
    );
    await page.route("**/api/revision-cleanups/first", (r) =>
      r.fulfill({
        json: {
          ...row,
          source,
          action: source === "downloads" ? "FILES" : "BOOK",
          result: "SUCCEEDED",
          selected: 1,
          succeeded: 1,
          ready: 0,
          blocked: 0,
        },
      }),
    );
    await page.route("**/api/revision-cleanups/first/items?**", (r) =>
      r.fulfill({ json: { items: [], meta } }),
    );
    await page.route(
      source === "downloads"
        ? "**/api/torrent/jobs/second**"
        : "**/api/calibre/transfers/second**",
      (r) => {
        if (new URL(r.request().url()).pathname.endsWith("/items"))
          return r.fulfill({
            json: {
              items: [],
              meta: { ...meta, totalItems: 0, totalPages: 0 },
            },
          });
        return r.fulfill({
          json: {
            ...row,
            id: "second",
            jobId: "second",
            type: source === "downloads" ? "UPDATE" : "SEND",
            alreadyExists: 0,
            inFlight: 0,
            cancelled: 0,
            batchSize: 1,
            concurrency: 1,
            interval: "0ms",
          },
        });
      },
    );
    await page.route(`**/api/settings/work/${source}/*/neighbors?**`, (r) => {
      const url = new URL(r.request().url());
      expect(url.searchParams.get("username")).toBe("Own");
      expect(url.searchParams.get("sort")).toBe("createdAt,desc");
      expect(url.searchParams.has("page")).toBe(false);
      return r.fulfill({
        json: url.pathname.includes("/first/")
          ? {
              previous: null,
              next: { id: "second", cleanup: false, index: 20 },
            }
          : { previous: { id: "first", cleanup: true, index: 19 }, next: null },
      });
    });
    await page.goto(`/${source}/jobs`);
    await page
      .getByRole("textbox", { name: "Usuario", exact: true })
      .fill("Own");
    await expect.poll(() => lists.at(-1)?.get("username")).toBe("Own");
    await page.getByRole("link", { name: "first", exact: true }).click();
    const nav = page.getByRole("navigation", {
      name: "Navegación entre trabajos",
    });
    await expect(nav.getByText("Anterior", { exact: true })).toHaveAttribute(
      "aria-disabled",
      "true",
    );
    await nav.getByRole("link", { name: "Siguiente", exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/${source}/jobs/second$`));
    await expect(nav.getByText("Siguiente", { exact: true })).toHaveAttribute(
      "aria-disabled",
      "true",
    );
    await nav.getByRole("link", { name: "Anterior", exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/${source}/jobs/cleanup/first$`));
    await nav.getByRole("link", { name: "Siguiente", exact: true }).click();
    await page
      .getByRole("link", {
        name:
          source === "downloads"
            ? "Trabajos de descargas"
            : "Trabajos de Calibre",
        exact: true,
      })
      .click();
    await expect(
      page.getByRole("textbox", { name: "Usuario", exact: true }),
    ).toHaveValue("Own");
    await expect.poll(() => lists.at(-1)?.get("page")).toBe("1");
  });
}

import { test, expect } from "./fixtures";

test("book history shares catalog pagination spacing and aligns heading with page size", async ({
  page,
}) => {
  const book = {
    eplId: 1,
    title: "Libro de prueba",
    author: "Autora",
    revision: 1,
    language: "es",
    coverUrl: null,
    coverAvailable: false,
    download: { items: [], totalItems: 0, statuses: [] },
  };
  await page.route("**/api/catalog/books?**", (route) =>
    route.fulfill({
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
  await page.route("**/api/catalog/books/1", (route) =>
    route.fulfill({ json: book }),
  );
  await page.route("**/api/catalog/books/1/magnets", (route) =>
    route.fulfill({ json: [] }),
  );
  await page.route("**/api/catalog/books/1/history?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    const pageNumber = Number(params.get("page"));
    return route.fulfill({
      json: {
        items: [
          {
            id: `history-${pageNumber}`,
            revision: pageNumber + 1,
            status: "DOWNLOADED",
            completed: true,
            hash: "A".repeat(40),
            client: "qbittorrent",
            clientInstanceId: "instance",
            origin: "DISCOVERED",
            lastCheckedAt: "2026-10-06T12:00:00Z",
            completedAt: "2026-10-01T12:00:00Z",
            lastError: null,
          },
        ],
        meta: {
          page: pageNumber,
          size: Number(params.get("size")),
          totalItems: 40,
          totalPages: 2,
          hasNext: pageNumber === 0,
          hasPrevious: pageNumber > 0,
        },
      },
    });
  });
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 1000 });
    await page.goto("/catalog");
    const catalogPadding = await page
      .locator(".pagination")
      .evaluate((element) => {
        const style = getComputedStyle(element);
        return {
          top: style.paddingTop,
          bottom: style.paddingBottom,
          left: style.paddingLeft,
          right: style.paddingRight,
          gap: style.gap,
        };
      });
    await page.goto("/catalog/1");
    const history = page.locator(".book-history");
    await expect(
      history.getByRole("heading", { name: "Historial asociado", exact: true }),
    ).toBeVisible();
    const paging = history.locator(".pagination");
    await expect(
      paging.getByRole("button", { name: "Siguiente", exact: true }),
    ).toBeVisible();
    const padding = await paging.evaluate((element) => {
      const style = getComputedStyle(element);
      return {
        top: style.paddingTop,
        bottom: style.paddingBottom,
        left: style.paddingLeft,
        right: style.paddingRight,
        gap: style.gap,
      };
    });
    expect(padding).toEqual(catalogPadding);
    expect(
      await history.evaluate((element) => getComputedStyle(element).padding),
    ).toBe("0px");
    const titleBox = (await history
      .getByRole("heading", { name: "Historial asociado", exact: true })
      .boundingBox())!;
    const sizeBox = (await paging.getByRole("textbox").first().boundingBox())!;
    expect(Math.abs(titleBox.x - sizeBox.x)).toBeLessThan(1);
    await paging
      .getByRole("button", { name: "Siguiente", exact: true })
      .click();
    await expect(history.locator("tbody tr td").nth(1)).toHaveText("2");
    await paging.getByRole("button", { name: "Anterior", exact: true }).click();
    await expect(history.locator("tbody tr td").nth(1)).toHaveText("1");
    await expect(history.getByRole("columnheader")).toHaveCount(7);
    await expect(
      history.getByRole("columnheader", { name: "Revisión", exact: true }),
    ).toHaveAttribute("aria-sort", "descending");
    await expect(history.locator("tbody tr td").first()).toHaveText(
      "A".repeat(40),
    );
    await expect(
      history.getByText("DISCOVERED", { exact: true }),
    ).toBeVisible();
    const request = page.waitForRequest((request) => {
      const url = new URL(request.url());
      return (
        url.pathname.endsWith("/1/history") &&
        url.searchParams.get("sort") === "revision,asc" &&
        url.searchParams.get("page") === "0"
      );
    });
    await history
      .getByRole("button", { name: "Revisión", exact: true })
      .click();
    await request;
    await expect(
      history.getByRole("columnheader", { name: "Revisión", exact: true }),
    ).toHaveAttribute("aria-sort", "ascending");
    await page.screenshot({
      path: `/tmp/eplsync-book-history-${width}.png`,
      fullPage: true,
    });
  }
});

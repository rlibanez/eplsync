import { test, expect } from "./fixtures";
for (const calibre of [false, true]) {
  test(`selects every filtered page in ${calibre ? "Calibre" : "download state"}`, async ({
    page,
  }) => {
    if (calibre)
      await page.route("**/api/settings/calibre/snapshot", (route) =>
        route.fulfill({ json: { snapshot: null } }),
      );
    const requested: number[] = [];
    const endpoint = calibre ? "calibre/books" : "torrent/downloads";
    await page.route(`**/api/${endpoint}?**`, (route) => {
      const params = new URL(route.request().url()).searchParams;
      const index = Number(params.get("page"));
      if (params.get("size") === "1000") requested.push(index);
      const items = [1, 2].map((n) => {
        const id = index * 2 + n;
        return calibre
          ? {
              calibreId: id,
              title: `Libro ${id}`,
              authors: "Autor",
              eplId: id,
              revision: 1.4,
              catalogRevision: 1.4,
              presence: "PRESENT",
              comparison: "CURRENT",
            }
          : {
              id: String(id),
              eplId: id,
              title: `Libro ${id}`,
              hash: String(id).repeat(40),
              revision: 1.4,
              status: "DOWNLOADED",
              client: "qbittorrent",
              origin: "EPLSYNC",
              coverAvailable: false,
            };
      });
      return route.fulfill({
        json: {
          items,
          meta: {
            page: index,
            size: Number(params.get("size")),
            totalItems: 4,
            totalPages: 2,
            hasNext: index === 0,
            hasPrevious: index > 0,
          },
        },
      });
    });
    await page.goto(calibre ? "/calibre" : "/downloads");
    await page
      .getByRole("button", { name: "Seleccionar todo (4)", exact: true })
      .click();
    await expect.poll(() => requested).toEqual([0, 1]);
    await expect(
      page.getByRole("button", { name: "Quitar selección (4)", exact: true }),
    ).toBeVisible();
    await page.getByRole("button", { name: "Siguiente", exact: true }).click();
    await expect(
      page.getByRole("button", { name: "Quitar selección (4)", exact: true }),
    ).toBeVisible();
  });
}

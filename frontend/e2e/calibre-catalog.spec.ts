import { test, expect } from "./fixtures";
const book = {
  eplId: 80899,
  title: "El reino del lenguaje",
  author: "Tom Wolfe",
  revision: 2,
  language: "es",
  genres: null,
  collection: null,
  volume: null,
  publicationYear: 2016,
  synopsis: null,
  pages: 200,
  publicationStatus: "UPDATED",
  publicationDate: null,
  insertDate: null,
  lastModifiedDate: null,
  status: "DISPONIBLE",
  rating: null,
  votesCount: null,
  links: null,
  coverUrl: null,
  coverAvailable: null,
  download: { items: [] },
  calibre: {
    library: "main",
    syncedAt: "2026-10-12T12:00:00Z",
    totalItems: 2,
    revision: 1.5,
    items: [
      { calibreId: 3, revision: 1.5 },
      { calibreId: 8, revision: 1 },
    ],
  },
};
test("shows Calibre copies in the individual book", async ({ page }) => {
  await page.route("**/api/catalog/books/80899", (route) =>
    route.fulfill({ json: book }),
  );
  await page.route("**/api/catalog/books/80899/magnets", (route) =>
    route.fulfill({ json: [] }),
  );
  const queries: URL[] = [];
  await page.route("**/api/calibre/books?**", (route) => {
    const url = new URL(route.request().url());
    queries.push(url);
    expect(url.searchParams.get("eplId")).toBe("80899");
    return route.fulfill({
      json: {
        items: book.calibre.items.map((item) => ({
          ...item,
          eplId: 80899,
          title: book.title,
          authors: book.author,
          catalogRevision: 2,
          comparison: "OUTDATED",
          presence: "PRESENT",
          coverUrl: null,
          coverAvailable: false,
        })),
        meta: {
          page: 0,
          size: 20,
          totalItems: 2,
          totalPages: 1,
          hasPrevious: false,
          hasNext: false,
        },
      },
    });
  });
  await page.goto("/catalog/80899");
  const section = page.locator("section").filter({
    has: page.getByRole("heading", { name: "Calibre", exact: true }),
  });
  await expect(section).not.toContainText("Biblioteca: main");
  await expect(section).toContainText("Revisión anterior al catálogo");
  await expect(section.locator("tbody tr")).toHaveCount(2);
  await expect(section.getByRole("link",{name:"3",exact:true})).toHaveAttribute("href","/api/calibre/books/3/open");
  await page.getByRole("button",{name:"Ver en Calibre",exact:true}).click();
  await expect(page.getByRole("menuitem").filter({hasText:"Calibre ID 3"})).toHaveAttribute("href","/api/calibre/books/3/open");
  await expect(page.getByRole("menuitem").filter({hasText:"Calibre ID 8"})).toHaveAttribute("target","_blank");
  await page.keyboard.press("Escape");
  await expect(section.getByRole("columnheader")).toHaveCount(8);
  await expect(section.getByRole("columnheader").first()).toContainText(
    "Calibre ID",
  );
  await expect(
    section.getByRole("button", { name: "Ordenar", exact: true }),
  ).toBeVisible();
  await expect(
    section.getByRole("button", { name: "Columnas", exact: true }),
  ).toBeVisible();
  const history = page.getByRole("heading", {
    name: "Historial de descargas",
    exact: true,
  });
  const calibre = section.getByRole("heading", {
    name: "Calibre",
    exact: true,
  });
  expect((await history.boundingBox())!.y).toBeLessThan(
    (await calibre.boundingBox())!.y,
  );
  await section
    .locator('th[data-table-column="revision"] button')
    .first()
    .click();
  await expect
    .poll(() => queries.at(-1)?.searchParams.get("sort"))
    .toBe("revision,asc");
});

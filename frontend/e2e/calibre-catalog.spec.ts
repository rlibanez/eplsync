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
test("shows Calibre copies in the individual book", async ({page}) => {
  await page.route("**/api/catalog/books/80899", route => route.fulfill({json: book}));
  await page.route("**/api/catalog/books/80899/magnets", route => route.fulfill({json: []}));
  await page.goto("/catalog/80899");
  const section = page.locator("section").filter({
    has: page.getByRole("heading", { name: "Calibre", exact: true }),
  });
  await expect(section).toContainText("main");
  await expect(section).toContainText("Revisión anterior al catálogo");
  await expect(section.locator("tbody tr")).toHaveCount(2);
});
test("synchronizes the saved library and displays its timestamp and totals", async ({
  page,
}) => {
  await page.route("**/api/settings/calibre", (route) =>
    route.fulfill({
      json: {
        url: "http://localhost:8080",
        username: "",
        passwordConfigured: false,
        library: "main",
      },
    }),
  );
  await page.route("**/api/settings/calibre/snapshot", (route) =>
    route.fulfill({ json: { snapshot: null } }),
  );
  await page.route("**/api/settings/calibre/sync", (route) =>
    route.fulfill({
      json: {
        library: "main",
        syncedAt: "2026-10-12T12:00:00Z",
        total: 20,
        identified: 15,
      },
    }),
  );
  await page.goto("/settings/calibre");
  await expect(
    page.getByText("La biblioteca de Calibre todavía no se ha sincronizado.", {
      exact: true,
    }),
  ).toBeVisible();
  await expect(page.getByRole("alert")).toHaveCount(0);
  await page
    .getByRole("button", { name: "Sincronizar biblioteca", exact: true })
    .click();
  await expect(
    page.getByRole("status").filter({ hasText: "Biblioteca sincronizada." }),
  ).toBeVisible();
  await expect(
    page.getByText(/20 libros; 15 con identificador EPL/),
  ).toBeVisible();
});

import { test, expect } from "./fixtures";
test.beforeEach(async ({ page }) => {
  await page.route("**/api/catalog/suggestions/**", (r) =>
    r.fulfill({ json: { items: [], total: 0, nextOffset: null } }),
  );
  await page.route("**/api/catalog/books/32/magnets", (r) =>
    r.fulfill({ json: [] }),
  );
});
const book = {
  eplId: 32,
  title: "Dune",
  author: "Frank Herbert",
  revision: 1.2,
  language: "es",
  genres: "Ciencia ficción",
  collection: "Dune",
  volume: 1,
  publicationYear: 1965,
  synopsis: "Una historia entre las arenas de Arrakis.",
  pages: 600,
  publicationStatus: "PUBLISHED",
  publicationDate: "2026-09-01",
  insertDate: "2026-09-01T12:00:00Z",
  lastModifiedDate: null,
  status: "DISPONIBLE",
  rating: 9,
  votesCount: 100,
  links: null,
  download: { items: [] },
};
test("filters, pagination, detail and back preserve the catalog context", async ({
  page,
}) => {
  await page.route("**/api/catalog/books?**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/magnets")) return route.fulfill({ json: [] });
    if (url.pathname.endsWith("/32")) return route.fulfill({ json: book });
    const current = Number(url.searchParams.get("page"));
    const size = Number(url.searchParams.get("size"));
    expect(size).toBe(20);
    expect(url.searchParams.getAll("sort")).toEqual(["title,asc", "eplId,asc"]);
    return route.fulfill({
      json: {
        items: Array.from({ length: 20 }, (_, i) => ({
          ...book,
          eplId: i === 19 ? 32 : 100 + i,
          title: i === 19 ? "Dune" : `Libro ${i + 1}`,
        })),
        meta: {
          page: current,
          size,
          totalItems: 40,
          totalPages: 2,
          hasNext: current === 0,
          hasPrevious: current > 0,
        },
      },
    });
  });
  await page.route("**/api/catalog/books/32", (r) => r.fulfill({ json: book }));
  await page.goto("/catalog");
  await page.locator(".catalog-search summary").click();
  await page.getByLabel("Título", { exact: true }).fill("Dune");
  await page.getByLabel("Autor", { exact: true }).fill("Herbert");
  await page.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect(page).toHaveURL(/title=Dune/);
  await expect(page).toHaveURL(/author=Herbert/);
  await expect(page.locator(".catalog-search summary")).toHaveClass(
    /filters-active/,
  );
  await page.getByRole("button", { name: "Siguiente" }).click();
  await expect(page).toHaveURL(/page=1/);
  await expect(
    page.getByRole("spinbutton", { name: "Página", exact: true }),
  ).toHaveValue("2");
  await page.getByRole("link", { name: "Dune", exact: true }).click();
  await expect(page).toHaveURL(/\/catalog\/32$/);
  await expect(
    page.getByRole("heading", { name: "Dune", exact: true }),
  ).toBeVisible();
  await expect(page.getByText(book.synopsis)).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Dune", exact: true }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Volver al catálogo" }).click();
  await expect(page).toHaveURL(/page=1/);
  await expect(
    page
      .locator(".catalog-search .mantine-Pill-label")
      .filter({ hasText: "Dune" }),
  ).toHaveCount(1);
  await expect
    .poll(() => page.evaluate(() => window.scrollY))
    .toBeGreaterThan(0);
  await page.screenshot({
    path: "test-results/catalog-desktop.png",
    fullPage: true,
  });
});
test("empty results, error retry and missing book are explained", async ({
  page,
}) => {
  let failed = true;
  await page.route("**/api/catalog/books?**", (route) => {
    if (route.request().url().includes("/999"))
      return route.fulfill({ status: 404 });
    if (failed) return route.fulfill({ status: 503 });
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
  });
  await page.goto("/catalog");
  await expect(page.getByRole("alert")).toContainText("No se pudo consultar");
  failed = false;
  await page.getByRole("button", { name: "Reintentar" }).click();
  await expect(
    page.getByRole("heading", { name: "No hay libros para mostrar" }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Siguiente" })).toBeDisabled();
  await page.route("**/api/catalog/books/999", (r) =>
    r.fulfill({ status: 404 }),
  );
  await page.route("**/api/catalog/books/999/magnets", (r) =>
    r.fulfill({ json: [] }),
  );
  await page.goto("/catalog/999");
  await expect(page.getByRole("alert")).toContainText(
    "No se ha encontrado este libro.",
  );
});
test("mobile navigation closes after selection and home is responsive", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.route("**/api/catalog/books?**", (r) =>
    r.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 10, totalItems: 0, totalPages: 0 },
      },
    }),
  );
  await page.route("**/api/torrent/downloads/summary", (r) =>
    r.fulfill({ json: { total: 0, byStatus: {} } }),
  );
  await page.route("**/api/torrent/jobs?**", (r) =>
    r.fulfill({ json: { items: [], meta: { totalItems: 0 } } }),
  );
  await page.route("**/api/events/operations?**", (r) =>
    r.fulfill({ json: { items: [], total: 0, page: 0, size: 10, cursor: 0 } }),
  );
  await page.goto("/");
  await expect(
    page.getByRole("heading", {
      name: /Todos tus libros\.\s*Un mismo lugar\./,
    }),
  ).toBeVisible();
  await page.screenshot({
    path: "test-results/home-mobile.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "Abrir menú" }).click();
  await expect(
    page.locator("#sidebar").getByRole("navigation"),
  ).toBeInViewport();
  await page
    .locator("#sidebar")
    .getByRole("link", { name: "EPL Sync", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Abrir menú" }),
  ).toHaveAttribute("aria-expanded", "false");
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(
    390,
  );
});

test("direct book URLs have no breadcrumbs and return to the default catalog", async ({
  page,
}) => {
  await page.route("**/api/catalog/books/32", (route) =>
    route.fulfill({ json: book }),
  );
  await page.goto("/catalog/32");
  await expect(
    page.getByRole("heading", { name: "Dune", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".topline, .breadcrumbs")).toHaveCount(0);
  await expect(
    page.getByRole("link", { name: "Volver al catálogo" }),
  ).toHaveAttribute("href", "/catalog");
});

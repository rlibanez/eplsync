import { test, expect } from "./fixtures";

test("catalog views preserve filters and selection, persist layout and open import", async ({ page }) => {
  await page.route("https://images.epublibre.org/**", r => r.abort());
  await page.route("**/api/catalog/import/source", r => r.fulfill({ json: { defaultUrl: "https://example.org/catalog.zip", archive: null } }));
  await page.route("**/api/catalog/books?**", r => r.fulfill({ json: {
    items: [1, 2].map(eplId => ({ eplId, title: "Libro " + eplId, author: "Autor", language: "es",
      collection: "Colección", volume: 2, genres: "Fantasía", pages: 300, publicationYear: 2020,
      download: { items: [{ status: "DOWNLOADED", completed: true }] } })),
    meta: { page: 0, size: 20, totalItems: 2, totalPages: 1, hasNext: false, hasPrevious: false },
  } }));
  await page.goto("/catalog?language=es&sort=author,desc");
  await expect(page.locator(".catalog-table")).toBeVisible();
  await expect(page.locator(".catalog-selection-bar")).toHaveCount(0);
  await expect(page.locator(".table-toolbar").getByRole("button", { name: "Seleccionar página", exact: true })).toBeVisible();
  await expect(page.locator(".table-toolbar").getByRole("button", { name: "Seleccionar todo (2)", exact: true })).toBeVisible();
  await page.getByRole("checkbox", { name: "Seleccionar Libro 1", exact: true }).check();
  await page.getByRole("button", { name: "Vista del catálogo" }).click();
  await page.getByRole("menuitem", { name: "Rejilla" }).click();
  await expect(page.locator(".catalog-cards-grid .catalog-card")).toHaveCount(2);
  await expect(page.getByRole("checkbox", { name: "Seleccionar Libro 1", exact: true })).toBeChecked();
  await expect(page.getByRole("button", { name: "Columnas", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "Vista del catálogo" }).click();
  await page.getByRole("menuitem", { name: "Mosaico" }).click();
  await expect(page.locator(".catalog-cards-mosaic")).toBeVisible();
  await expect(page.locator(".catalog-card-facts").first()).toContainText("300 páginas");
  await expect(page).toHaveURL(/language=es&sort=author,desc/);
  await page.reload();
  await expect(page.locator(".catalog-cards-mosaic")).toBeVisible();
  await page.screenshot({ path: "test-results/catalog-mosaic.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.getByRole("button", { name: "Importar catálogo", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
});

test("only truncated card titles show their full text on hover or focus", async ({ page }) => {
  const title = "Un título muy largo que continúa durante muchas palabras y no cabe en la tarjeta del catálogo ".repeat(5);
  await page.route("**/api/catalog/books?**", r => r.fulfill({ json: {
    items: [title, "Breve"].map((title, index) => ({ eplId: index + 1, title, author: "Autor", download: { items: [] } })),
    meta: { page: 0, size: 20, totalItems: 2, totalPages: 1, hasNext: false, hasPrevious: false },
  } }));
  await page.goto("/catalog");
  for (const view of ["Rejilla", "Mosaico"]) {
    await page.getByRole("button", { name: "Vista del catálogo" }).click();
    await page.getByRole("menuitem", { name: view }).click();
    const titles = page.locator(".catalog-card-title");
    await titles.first().hover();
    await expect(page.getByRole("tooltip").filter({ hasText: title.trim() })).toHaveText(title.trim());
    await page.getByRole("heading", { name: "Catálogo", exact: true }).hover();
    await titles.last().hover();
    await expect(page.getByRole("tooltip").filter({ hasText: title.trim() })).toHaveCount(0);
    await page.keyboard.press("Tab");
    await titles.first().focus();
    await expect(page.getByRole("tooltip").filter({ hasText: title.trim() })).toHaveText(title.trim());
    await titles.last().focus();
    await expect(page.getByRole("tooltip").filter({ hasText: title.trim() })).toHaveCount(0);
  }
});

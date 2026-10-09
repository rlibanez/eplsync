import { test, expect, type Page } from "./fixtures";
async function changeLanguage(page: Page, language: string) {
  const previous = page.url();
  await page.goto("/settings/general");
  await page.locator("main select").selectOption(language);
  await page.goto(previous);
}
const book = {
  eplId: 32,
  title: "El libro de arena",
  author: "Jorge Luis Borges",
  revision: 1.2,
  language: "es",
  genres: "Ficción",
  collection: null,
  volume: null,
  publicationYear: 1975,
  synopsis: "Una sinopsis que conserva el idioma original.",
  pages: 12345,
  publicationStatus: "PUBLISHED",
  publicationDate: "2026-09-01",
  insertDate: "2026-09-01T12:00:00Z",
  lastModifiedDate: null,
  status: "VERIFICADO",
  rating: 9.5,
  votesCount: 100,
  links: null,
  download: {
    items: [
      { id: "one", revision: 1.2, status: "DOWNLOADED", completed: true },
    ],
  },
};
test("changes the entire interface without losing filters, localizes formats and persists", async ({
  page,
}) => {
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
  await page.route("**/api/catalog/books/32", (r) => r.fulfill({ json: book }));
  await page.route("**/api/catalog/books/32/magnets", (r) =>
    r.fulfill({ json: [] }),
  );
  await page.route("**/api/catalog/books/32/history?**", (r) =>
    r.fulfill({
      json: {
        items: book.download.items,
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
  await page.route("**/api/catalog/suggestions/**", (r) =>
    r.fulfill({ json: { items: [], total: 0, nextOffset: null } }),
  );
  await page.goto("/catalog?title=arena&language=es");
  await expect(
    page.getByRole("button", { name: "Seleccionar todo (1)", exact: true }),
  ).toBeVisible();
  await changeLanguage(page, "en");
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await expect(
    page.getByRole("heading", { name: "Catalog", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Select all (1)", exact: true }),
  ).toBeVisible();
  await page.locator(".catalog-search summary").click();
  await expect(page.locator(".catalog-search .mantine-Pill-label")).toHaveText([
    "arena",
    "Spanish",
  ]);
  await expect(page).toHaveURL(/language=es/);
  await page
    .getByRole("link", { name: "El libro de arena", exact: true })
    .click();
  await expect(page.getByText(book.synopsis)).toBeVisible();
  await expect(
    page.getByText("Revision 1.2", { exact: true }).first(),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Filter by Pages: 12345", exact: true }),
  ).toBeVisible();
  await expect(page.getByText("Sep 1, 2026", { exact: true })).toBeVisible();
  await expect(page.getByText("Verified", { exact: true })).toBeVisible();
  await expect(page.getByText("Downloaded", { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await changeLanguage(page, "es");
  await expect(
    page.getByText("Revisión 1.2", { exact: true }).first(),
  ).toBeVisible();
  await expect(
    page.getByRole("button", {
      name: "Filtrar por Páginas: 12345",
      exact: true,
    }),
  ).toBeVisible();
  await expect(page.getByText("1 sept 2026", { exact: true })).toBeVisible();
  await changeLanguage(page, "en");
  await page.screenshot({
    path: "test-results/detail-english.png",
    fullPage: true,
  });
});
test("translates an existing error when switching languages", async ({
  page,
}) => {
  await page.route("**/api/catalog/books/999", (route) =>
    route.fulfill({ status: 404 }),
  );
  await page.goto("/catalog/999");
  await expect(page.getByRole("alert")).toContainText("No se ha encontrado");
  await changeLanguage(page, "en");
  await expect(page.getByRole("alert")).toContainText(
    "This book could not be found.",
  );
  await expect(page.getByRole("button", { name: "Retry" })).toBeVisible();
});
test.describe("English browser", () => {
  test.use({ locale: "en-GB" });
  test("uses browser language and allows mobile language selection", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("/settings/general");
    await expect(page.locator("html")).toHaveAttribute("lang", "en");
    await page.getByRole("button", { name: "Open menu" }).click();
    await page.getByRole("link", { name: "Settings", exact: true }).click();
    await page.getByLabel("Interface language").selectOption("es");
    await page.getByRole("button", { name: "Abrir menú" }).click();
    await expect(
      page.getByRole("navigation", { name: "Navegación principal" }),
    ).toBeVisible();
    await page.reload();
    await expect(page.locator("html")).toHaveAttribute("lang", "es");
  });
});
test.describe("Unsupported browser language", () => {
  test.use({ locale: "fr-FR" });
  test("falls back to English with unavailable storage", async ({ page }) => {
    await page.addInitScript(() => {
      Object.defineProperty(window, "localStorage", {
        get() {
          throw new Error("Storage blocked");
        },
      });
    });
    await page.goto("/settings/general");
    await expect(page.locator("html")).toHaveAttribute("lang", "en");
    await page.getByLabel("Interface language").selectOption("es");
    await expect(page.locator("html")).toHaveAttribute("lang", "es");
  });
});

test("auto detects browser preferences and preserves a manual choice", async ({
  page,
}) => {
  await page.goto("/settings");
  await expect(page.locator("html")).toHaveAttribute("lang", "es");
  await page
    .getByRole("main")
    .getByLabel("Idioma de la interfaz")
    .selectOption("en");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
});

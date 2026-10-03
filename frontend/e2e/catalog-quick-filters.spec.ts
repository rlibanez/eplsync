import { test, expect } from "./fixtures";
test.use({ timezoneId: "Europe/Madrid" });

const book = {
  eplId: 17347,
  title: "$ 1.000.000 de recompensa",
  author: "Kenneth Robeson",
  collection: "Doc Savage",
  genres: "Drama, Aventuras",
  language: "es",
  status: "DISPONIBLE",
  publicationStatus: "UPDATED",
  revision: 1.1,
  publicationYear: 1933,
  publicationDate: "2014-07-29",
  insertDate: "2026-09-30T23:15:00Z",
  download: { items: [] },
};

test("table values apply composable filters and exact local-day ranges", async ({
  page,
}) => {
  await page.route("**/api/ui/config", (r) =>
    r.fulfill({ json: { defaultLanguage: "es" } }),
  );
  const queries: URLSearchParams[] = [];
  await page.route("**/api/catalog/books?**", (r) => {
    queries.push(new URL(r.request().url()).searchParams);
    return r.fulfill({
      json: {
        items: [book],
        meta: { page: 0, size: 20, totalItems: 1, totalPages: 1 },
      },
    });
  });
  await page.goto("/catalog?language=es&page=2&size=50&sort=author,asc");
  const table = page.locator(".catalog-table");
  for (const [field, label, value] of [
    ["author", "Autor", book.author],
    ["collection", "Colección", book.collection],
    ["genres", "Género", "Aventuras"],
    ["language", "Idioma", "Español"],
    ["status", "Estado", "Disponible"],
    ["publicationStatus", "Estado de publicación", "Actualizado"],
    ["eplId", "EPL Id", "17347"],
    ["revision", "Revisión", "1.1"],
  ]) {
    await table
      .getByRole("button", {
        name: `Filtrar por ${label}: ${value}`,
        exact: true,
      })
      .click();
    await expect
      .poll(() => new URL(page.url()).searchParams.get(field))
      .toBe(
        String(
          book[field as keyof typeof book] === book.genres
            ? "Aventuras"
            : book[field as keyof typeof book],
        ),
      );
  }
  await expect.poll(() => queries.at(-1)?.get("revision")).toBe("1.1");
  expect(new URL(page.url()).searchParams.get("page")).toBe("0");
  expect(new URL(page.url()).searchParams.get("size")).toBe("50");
  expect(new URL(page.url()).searchParams.get("author")).toBe(book.author);
  for (const [label, from, to, value] of [
    ["Año de publicación", "publicationYearFrom", "publicationYearTo", "1933"],
    [
      "Publicado en EPL",
      "publicationDateFrom",
      "publicationDateTo",
      "2014-07-29",
    ],
    ["Incorporado a EPL Sync", "addedFrom", "addedTo", "2026-10-01"],
  ]) {
    await table
      .getByRole("button", { name: new RegExp(`^Filtrar por ${label}:`) })
      .click();
    expect(new URL(page.url()).searchParams.get(from)).toBe(value);
    expect(new URL(page.url()).searchParams.get(to)).toBe(value);
    const range = page
      .locator(".catalog-date-range")
      .filter({ has: page.locator("legend", { hasText: label }) });
    await expect(range.locator("input").first()).toHaveValue(value);
    await expect(range.locator("input").last()).toHaveValue(value);
  }
  await expect
    .poll(() => queries.at(-1)?.get("insertDateFrom"))
    .toBe("2026-09-30T22:00:00.000Z");
  expect(queries.at(-1)?.get("insertDateBefore")).toBe(
    "2026-10-01T22:00:00.000Z",
  );
  await page.locator(".catalog-search summary").click();
  await expect(
    page
      .locator(".catalog-search .mantine-Pill-label")
      .filter({ hasText: book.author }),
  ).toBeVisible();
  await expect(
    page
      .locator(".catalog-search .mantine-Pill-label")
      .filter({ hasText: "1.1" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Igualar fechas", pressed: true }),
  ).toHaveCount(3);
  await table.getByRole("link", { name: book.title }).click();
  await expect(page).toHaveURL(/\/catalog\/17347$/);
});

test("coauthors are independently clickable and preserve commas within names", async ({ page }) => {
  await page.route("**/api/ui/config", r => r.fulfill({ json: { defaultLanguage: "es" } }));
  await page.route("**/api/catalog/books?**", r => r.fulfill({ json: {
    items: [{ ...book, author: "Arthur C. Clarke & Doe, Jane & Arthur C. Clarke" }],
    meta: { page: 0, size: 20, totalItems: 1, totalPages: 1 },
  } }));
  await page.goto("/catalog");
  const cell = page.locator('.catalog-table tbody td').filter({ has: page.getByRole("button", { name: "Filtrar por Autor: Doe, Jane", exact: true }) });
  await expect(cell.getByRole("button")).toHaveCount(2);
  await cell.getByRole("button", { name: "Filtrar por Autor: Doe, Jane", exact: true }).click();
  expect(new URL(page.url()).searchParams.getAll("author")).toEqual(["Doe, Jane"]);
});

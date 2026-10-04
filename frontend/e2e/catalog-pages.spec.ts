import { test, expect } from "./fixtures";

test("page range supports equal values, independent clearing and URL restoration", async ({ page }) => {
  await page.route("**/api/ui/config", r => r.fulfill({ json: { defaultLanguage: "es" } }));
  await page.route("**/api/catalog/books?**", r => r.fulfill({ json: {
    items: [], meta: { page: 0, size: 20, totalItems: 0, totalPages: 0, hasNext: false, hasPrevious: false },
  } }));
  await page.goto("/catalog?pagesFrom=100&pagesTo=200");
  await page.locator(".catalog-search summary").click();
  const range = page.getByRole("group", { name: "Páginas", exact: true });
  const minimum = range.getByLabel("Mínimo", { exact: true });
  const maximum = range.getByLabel("Máximo", { exact: true });
  const equal = range.getByRole("button", { name: "Igualar mínimo y máximo" });
  await expect(minimum).toHaveValue("100");
  await expect(maximum).toHaveValue("200");
  await equal.click();
  await expect(maximum).toHaveValue("100");
  await maximum.fill("150");
  await expect(minimum).toHaveValue("150");
  await page.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect(page).toHaveURL(/pagesFrom=150&pagesTo=150/);
  await page.locator(".catalog-search summary").click();
  await expect(equal).toHaveAttribute("aria-pressed", "true");
  await range.getByRole("button", { name: "Limpiar", exact: true }).click();
  await expect(minimum).toHaveValue("");
  await expect(maximum).toHaveValue("");
  await expect(equal).toHaveAttribute("aria-pressed", "false");
  await maximum.fill("300");
  await expect(minimum).toHaveAttribute("max", "300");
  await page.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect(page).toHaveURL(/pagesTo=300/);
  expect(new URL(page.url()).searchParams.has("pagesFrom")).toBe(false);
  await page.getByRole("button", { name: "Limpiar filtros", exact: true }).click();
  expect(new URL(page.url()).searchParams.has("pagesTo")).toBe(false);
  await page.getByRole("button", { name: "Ordenar", exact: true }).click();
  await page.getByRole("textbox", { name: "Criterio 1", exact: true }).click();
  await page.getByRole("option", { name: "Páginas", exact: true }).click();
  await expect(page).toHaveURL(/sort=pages%2Casc/);
  await page.reload();
  await page.getByRole("button", { name: "Ordenar", exact: true }).click();
  await page.getByRole("textbox", { name: "Sentido 1", exact: true }).click();
  await page.getByRole("option", { name: "de más a menos", exact: true }).click();
  await expect(page).toHaveURL(/sort=pages%2Cdesc/);

});

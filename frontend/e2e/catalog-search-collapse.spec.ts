import { test, expect } from "./fixtures";

test("valid searches collapse advanced filters, including unchanged searches, while invalid ranges stay open", async ({ page }) => {
  await page.route("**/api/catalog/books?**", r => r.fulfill({ json: {
    items: [], meta: { page: 0, size: 20, totalItems: 0, totalPages: 0, hasNext: false, hasPrevious: false },
  } }));
  await page.goto("/catalog");
  const search = page.locator(".catalog-search");
  const summary = search.locator("summary");
  await summary.click();
  const pages = page.getByRole("group", { name: "Páginas", exact: true });
  await pages.getByLabel("Mínimo", { exact: true }).fill("100");
  await search.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect(search).not.toHaveAttribute("open", "");
  await expect(summary).toContainText("Filtros activos");
  await summary.click();
  await expect(pages.getByLabel("Mínimo", { exact: true })).toHaveValue("100");
  await search.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect(search).not.toHaveAttribute("open", "");
  await summary.click();
  await pages.getByLabel("Máximo", { exact: true }).fill("50");
  await search.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect(search).toHaveAttribute("open", "");
});

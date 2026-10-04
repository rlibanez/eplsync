import { test, expect } from "./fixtures";

test("century selection filters the server, resets paging and survives back navigation", async ({ page }) => {
  await page.route("**/api/ui/config", r => r.fulfill({ json: { defaultLanguage: "es" } }));
  const requests: URLSearchParams[] = [];
  await page.route("**/api/catalog/directory/years?**", r => {
    const params = new URL(r.request().url()).searchParams;
    requests.push(params);
    return r.fulfill({ json: { items: [{ value: params.get("century") === "0" ? "-2500" : "2001" }],
      meta: { page: Number(params.get("page")), size: 20, totalItems: 1, totalPages: 1 } } });
  });
  await page.route("**/api/catalog/books?**", r => r.fulfill({ json: { items: [], meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 } } }));
  await page.goto("/directory?section=years&page=2&q=20");
  const bar = page.getByRole("navigation", { name: "Filtrar por siglo" });
  await bar.getByRole("button", { name: "XXI", exact: true }).click();
  await expect.poll(() => requests.at(-1)?.get("century")).toBe("21");
  expect(requests.at(-1)?.get("page")).toBe("0");
  expect(requests.at(-1)?.get("q")).toBe("20");
  await bar.getByRole("button", { name: "≤0", exact: true }).click();
  await page.getByRole("link", { name: "-2500", exact: true }).click();
  await expect(page).toHaveURL(/publicationYear=-2500/);
  await page.goBack();
  await expect(bar.getByRole("button", { name: "≤0", exact: true })).toHaveAttribute("aria-pressed", "true");
  await page.reload();
  await expect(bar.getByRole("button", { name: "≤0", exact: true })).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "Limpiar", exact: true }).click();
  await expect.poll(() => requests.at(-1)?.has("century")).toBe(false);
  await expect(bar.getByRole("button", { name: "Todos", exact: true })).toHaveAttribute("aria-pressed", "true");
});

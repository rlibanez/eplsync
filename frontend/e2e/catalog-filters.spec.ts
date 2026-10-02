import { test, expect } from "@playwright/test";
test.use({ timezoneId: "Europe/Madrid" });
test("advanced filters are collapsed and convert complete local days across DST", async ({
  page,
}) => {
  await page.route("**/api/ui/config", (r) =>
    r.fulfill({ json: { defaultLanguage: "es" } }),
  );
  await page.route("**/api/catalog/covers/task", (r) =>
    r.fulfill({ json: { task: null } }),
  );
  const queries: URLSearchParams[] = [];
  await page.route("**/api/catalog/books?**", (r) => {
    queries.push(new URL(r.request().url()).searchParams);
    return r.fulfill({
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
  const heading = page.locator(".catalog-search summary");
  const normalColor = await heading.evaluate(el => getComputedStyle(el).color);
  await expect(page.getByLabel("Colección", { exact: true })).toBeHidden();
  await page.locator("summary").click();
  await page.getByLabel("Título", { exact: true }).fill("Draft");
  await page.locator(".catalog-search .action-row").getByRole("button", { name: "Limpiar", exact: true }).click();
  await expect(page.getByLabel("Título", { exact: true })).toHaveValue("");
  const dates = page.locator("fieldset").filter({has: page.locator("legend", {hasText: "Publicado en EPL"})});
  await dates.getByLabel("Hasta", {exact:true}).fill("2026-01-15");
  await dates.getByRole("button", { name: "Igualar fechas" }).click();
  await expect(dates.getByLabel("Desde", {exact:true})).toHaveValue("2026-01-15");
  await dates.getByLabel("Desde", {exact:true}).fill("2026-02-20");
  await expect(dates.getByLabel("Hasta", {exact:true})).toHaveValue("2026-02-20");
  await dates.getByRole("button", {name:"Limpiar"}).click();
  await expect(dates.getByRole("button", { name: "Igualar fechas" })).toHaveAttribute("aria-pressed", "false");
  await expect(dates.getByLabel("Desde", {exact:true})).toHaveValue("");
  await expect(dates.getByLabel("Hasta", {exact:true})).toHaveValue("");
  await page.getByLabel("Género", { exact: true }).fill("Fantasía");
  await page.getByLabel("Colección", { exact: true }).fill("Discworld");
  const added = page
    .locator("fieldset")
    .filter({ has: page.locator("legend", { hasText: "Incorporado" }) });
  await added.getByLabel("Desde").fill("2026-03-29");
  await expect(added.getByLabel("Hasta")).toHaveAttribute("min", "2026-03-29");
  await added.getByLabel("Hasta").fill("2026-03-29");
  await expect(added.getByLabel("Desde")).toHaveAttribute("max", "2026-03-29");
  await page.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect
    .poll(() => queries.at(-1)?.get("insertDateBefore"))
    .toBe("2026-03-29T22:00:00.000Z");
  expect(queries.at(-1)?.get("insertDateFrom")).toBe(
    "2026-03-28T23:00:00.000Z",
  );
  expect(queries.at(-1)?.get("collection")).toBe("Discworld");
  expect(queries.at(-1)?.get("genres")).toBe("Fantasía");
  expect(queries.at(-1)?.has("addedFrom")).toBe(false);
  await expect(heading).toHaveClass("filters-active");
  await expect.poll(() => heading.evaluate(el => getComputedStyle(el).color)).not.toBe(normalColor);
  await page.reload();
  await page.locator("summary").click();
  await expect(added.getByLabel("Desde")).toHaveValue("2026-03-29");
  await page.locator(".catalog-search .action-row").getByRole("button", { name: "Limpiar", exact: true }).click();
  await expect.poll(() => queries.at(-1)?.has("insertDateFrom")).toBe(false);
  await expect(heading).not.toHaveClass("filters-active");
  await expect.poll(() => heading.evaluate(el => getComputedStyle(el).color)).toBe(normalColor);
});

test("clear applied filters from the heading preserves table preferences and disclosure state", async ({ page }) => {
  await page.route("**/api/ui/config", r => r.fulfill({ json: { defaultLanguage: "es" } }));
  await page.route("**/api/catalog/books?**", r => r.fulfill({ json: {
    items: [], meta: { page: 0, size: 50, totalItems: 0, totalPages: 0, hasNext: false, hasPrevious: false },
  } }));
  for (const expanded of [false, true]) {
    await page.goto("/catalog?author=Author&status=DISPONIBLE&status=VERIFICADO&revision=1.1&addedFrom=2026-01-01&page=3&size=50&sort=author,asc");
    const panel = page.locator(".catalog-search");
    const summary = panel.locator("summary");
    if (expanded) await summary.click();
    const clear = panel.getByRole("button", { name: "Limpiar filtros", exact: true });
    await clear.focus();
    await page.keyboard.press("Enter");
    await expect(clear).toHaveCount(0);
    await expect.poll(() => panel.evaluate(el => (el as HTMLDetailsElement).open)).toBe(expanded);
    const params = new URL(page.url()).searchParams;
    expect(params.get("page")).toBe("0");
    expect(params.get("size")).toBe("50");
    expect(params.getAll("sort")).toEqual(["author,asc", "eplId,asc"]);
    for (const key of ["author", "status", "revision", "addedFrom"]) expect(params.has(key)).toBe(false);
    if (expanded) await expect(page.getByLabel("Autor", { exact: true })).toHaveValue("");
  }
});

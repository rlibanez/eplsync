import { test, expect } from "./fixtures";

test.beforeEach(async ({ page }) => {
  await page.route("**/api/catalog/books?**", r => r.fulfill({ json: {
    items: [], meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
  } }));
});
test("suggestions debounce, append on scroll and select a token without searching", async ({ page }) => {
  const queries: URLSearchParams[] = [];
  await page.route("**/api/catalog/suggestions/authors?**", r => {
    const query = new URL(r.request().url()).searchParams; queries.push(query);
    const offset = Number(query.get("offset"));
    return r.fulfill({ json: { items: Array.from({ length: offset === 0 ? 20 : 5 }, (_, i) => "Autor " + String(offset+i).padStart(2,"0")),
      total: 25, nextOffset: offset === 0 ? 20 : null } });
  });
  await page.goto("/catalog");
  await page.locator(".catalog-search summary").click();
  const input = page.getByRole("textbox", { name: "Autor", exact: true });
  await input.fill("A");
  await page.waitForTimeout(350);
  expect(queries).toHaveLength(0);
  await input.pressSequentially("utor", { delay: 25 });
  await expect(page.getByRole("option")).toHaveCount(20);
  expect(queries).toHaveLength(1);
  await page.getByRole("listbox").evaluate(el => { el.scrollTop = el.scrollHeight; el.dispatchEvent(new Event("scroll")); });
  await expect(page.getByRole("option")).toHaveCount(25);
  expect(queries[1].get("offset")).toBe("20");
  await page.getByRole("option", { name: "Autor 24", exact: true }).click();
  await expect(page.locator(".mantine-Pill-label")).toHaveText(["Autor 24"]);
  await expect(input).toBeFocused();
  expect(new URL(page.url()).searchParams.has("author")).toBe(false);
  await input.fill("Texto libre");
  await input.press("Tab");
  await expect(input).toBeFocused();
  await expect(page.locator(".mantine-Pill-label")).toHaveText(["Autor 24", "Texto libre"]);
  await input.press("Backspace");
  await expect(page.locator(".mantine-Pill-label")).toHaveText(["Autor 24"]);
  await input.press("Enter");
  await expect(page).toHaveURL(/author=Autor\+24/);
});
test("keyboard suggestions preserve free text, accents and stale query isolation", async ({ page }) => {
  await page.route("**/api/catalog/suggestions/authors?**", async r => {
    const q = new URL(r.request().url()).searchParams.get("q");
    if (q === "viejo") await new Promise(resolve => setTimeout(resolve, 700));
    await r.fulfill({ json: { items: [q === "viejo" ? "Antiguo" : q === "corazón" ? "Corazón de prueba" : "García Márquez"], total: 1, nextOffset: null } }).catch(() => {});
  });
  await page.goto("/catalog");
  await page.locator(".catalog-search summary").click();
  const input = page.getByRole("textbox", { name: "Autor", exact: true });
  await input.fill("viejo");
  await page.waitForTimeout(320);
  await input.fill("García");
  await expect(page.getByRole("option", { name: "García Márquez" })).toBeVisible();
  await page.waitForTimeout(750);
  await expect(page.getByRole("option", { name: "Antiguo" })).toHaveCount(0);
  await input.press("ArrowDown");
  await input.press("Enter");
  await expect(page.locator(".mantine-Pill-label")).toHaveText(["García Márquez"]);
  await input.pressSequentially("corazón");
  await expect(input).toHaveValue("corazón");
  await expect(page.getByRole("option")).toHaveCount(1);
  await input.press("Escape");
  await expect(page.getByRole("option")).toHaveCount(0);
  await input.press("Enter");
  await expect(page.locator(".mantine-Pill-label")).toHaveText(["García Márquez", "corazón"]);
});
test("directory uses the same suggestions while search remains explicit", async ({ page }) => {
  await page.route("**/api/catalog/directory/collections?**", r => r.fulfill({ json: { items: [], meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 } } }));
  await page.route("**/api/catalog/suggestions/collections?**", r => r.fulfill({ json: { items: ["Nacidos de la bruma"], total: 1, nextOffset: null } }));
  await page.goto("/directory?section=collections");
  const input = page.getByRole("textbox", { name: "Buscar", exact: true });
  await input.fill("naci");
  await page.getByRole("option", { name: "Nacidos de la bruma" }).click();
  await expect(input).toHaveValue("Nacidos de la bruma");
  expect(new URL(page.url()).searchParams.has("q")).toBe(false);
  await input.press("Enter");
  await expect(page).toHaveURL(/q=Nacidos\+de\+la\+bruma/);
});
test("suggestion failures do not prevent manual search", async ({ page }) => {
  await page.route("**/api/catalog/suggestions/genres?**", r => r.fulfill({ status: 503, json: {} }));
  await page.goto("/catalog");
  await page.locator(".catalog-search summary").click();
  const input = page.getByRole("textbox", { name: "Género", exact: true });
  await input.fill("Fantasía");
  await expect(page.getByText("No se pudieron cargar las sugerencias. Puedes seguir escribiendo.")).toBeVisible();
  await input.press("Enter");
  await input.press("Enter");
  await expect(page).toHaveURL(/genres=Fantas%C3%ADa/);
});
test("title suggestions add the complete title without submitting the search", async ({ page }) => {
  const title = "Corazón, ciencia & ficción";
  await page.route("**/api/catalog/suggestions/titles?**", r => r.fulfill({
    json: { items: [title], total: 1, nextOffset: null },
  }));
  await page.goto("/catalog");
  await page.locator(".catalog-search summary").click();
  const input = page.getByRole("textbox", { name: "Título", exact: true });
  await input.fill("corazon");
  await page.getByRole("option", { name: title, exact: true }).click();
  await expect(page.locator(".mantine-Pill-label")).toHaveText([title]);
  expect(new URL(page.url()).searchParams.has("title")).toBe(false);
  await input.press("Enter");
  await expect.poll(() => new URL(page.url()).searchParams.get("title")).toBe(title);
});

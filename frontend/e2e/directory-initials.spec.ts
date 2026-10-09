import { test, expect } from "./fixtures";
test("directory combines search and initials, groups entries and links collections", async ({
  page,
}) => {
  await page.route("**/api/catalog/books?**", (route) =>
    route.fulfill({
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
    }),
  );
  const requests: URL[] = [];
  await page.route("**/api/catalog/directory/**", (r) => {
    const url = new URL(r.request().url());
    requests.push(url);
    const all = [
      { value: "Álvaro", initial: "A" },
      { value: "Nora", initial: "N" },
      { value: "Ñandú", initial: "Ñ" },
    ];
    const initial = url.searchParams.get("initial");
    const q = url.searchParams.get("q") ?? "";
    const items = all.filter(
      (e) => (!initial || e.initial === initial) && (!q || e.value.includes(q)),
    );
    return r.fulfill({
      json: {
        items,
        meta: { page: 0, size: 20, totalItems: items.length, totalPages: 1 },
      },
    });
  });
  await page.goto("/directory");
  await expect(
    page.getByRole("heading", { name: "A", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Colecciones", exact: true }).click();
  await expect
    .poll(() => requests.at(-1)?.pathname)
    .toBe("/api/catalog/directory/collections");
  const initials = page.getByRole("navigation", {
    name: "Filtrar por inicial",
  });
  await initials.getByRole("button", { name: "Ñ", exact: true }).click();
  await expect(
    initials.getByRole("button", { name: "Ñ", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  await expect(page.locator(".directory-grid a")).toHaveCount(1);
  await page.getByRole("textbox", { name: "Buscar", exact: true }).fill("andú");
  await page.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect.poll(() => requests.at(-1)?.searchParams.get("q")).toBe("andú");
  expect(requests.at(-1)?.searchParams.get("initial")).toBe("Ñ");
  await expect(page.locator(".directory-grid a")).toHaveAttribute(
    "href",
    "/catalog?collection=%C3%91and%C3%BA",
  );
  await page.locator(".directory-grid a").click();
  await expect(page).toHaveURL(/\/catalog\?collection=/);
  await page.goBack();
  await expect(
    initials.getByRole("button", { name: "Ñ", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  await expect(
    page.getByRole("textbox", { name: "Buscar", exact: true }),
  ).toHaveValue("andú");
  await expect(page.locator(".directory-grid a")).toHaveCount(1);
  await page.reload();
  await expect(
    page.getByRole("textbox", { name: "Buscar", exact: true }),
  ).toHaveValue("andú");
  await expect(
    initials.getByRole("button", { name: "Ñ", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "Limpiar", exact: true }).click();
  await expect(page.locator(".directory-grid a")).toHaveCount(3);
  await expect(
    page.getByRole("textbox", { name: "Buscar", exact: true }),
  ).toHaveValue("");
  await page.screenshot({ path: "test-results/directory-alphabet.png" });
  await page.getByRole("button", { name: "Idiomas", exact: true }).click();
  await expect(initials).toHaveCount(0);
});

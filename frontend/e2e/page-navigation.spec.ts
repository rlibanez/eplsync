import { test, expect } from "./fixtures";
test.beforeEach(async ({ page }) => {
  await page.route("**/api/ui/config", (r) =>
    r.fulfill({ json: { defaultLanguage: "es" } }),
  );
});
const book = {
  eplId: 14936,
  title: "Libro",
  author: "Autor",
  revision: 1,
  language: "es",
  genres: null,
  publicationYear: 2000,
  pages: 20,
  rating: null,
  votesCount: null,
  volume: null,
  collection: null,
  publicationDate: null,
  insertDate: null,
  lastModifiedDate: null,
  publicationStatus: "PUBLISHED",
  status: "DISPONIBLE",
  synopsis: "Texto",
  download: { items: [] },
};
test("jump to page 100 keeps filters and supports 1000 books per page", async ({
  page,
}) => {
  await page.route("**/api/catalog/books?**", (r) => {
    const params = new URL(r.request().url()).searchParams;
    const current = Number(params.get("page"));
    const size = Number(params.get("size"));
    return r.fulfill({
      json: {
        items: [book],
        meta: {
          page: current,
          size,
          totalItems: 100000,
          totalPages: 100000 / size,
          hasNext: true,
          hasPrevious: current > 0,
        },
      },
    });
  });
  await page.goto("/catalog?author=Autor");
  const input = page.getByRole("spinbutton", { name: "Página", exact: true });
  await expect(input).toBeEnabled();
  await input.fill("100");
  await page.getByRole("button", { name: "Ir", exact: true }).click();
  await expect(page).toHaveURL(/page=99/);
  await expect(page).toHaveURL(/author=Autor/);
  await expect(input).toHaveValue("100");
  await page
    .getByRole("textbox", { name: "Libros por página", exact: true })
    .click();
  const option = page.getByRole("option", {
    name: "1000 por página",
    exact: true,
  });
  await expect(option).toBeVisible();
  expect(await option.locator("svg").count()).toBe(0);
  await option.click();
  await expect(page).toHaveURL(/size=1000/);
  await expect(input).toHaveValue("1");
  await input.fill("101");
  await page.getByRole("button", { name: "Ir", exact: true }).click();
  await expect(page).toHaveURL(/page=0/);
  expect(
    await input.evaluate(
      (node) => (node as HTMLInputElement).validity.rangeOverflow,
    ),
  ).toBe(true);
});
test("shared pagination jumps in jobs without changing status filter", async ({
  page,
}) => {
  const requests: string[] = [];
  await page.route("**/api/torrent/jobs?**", (r) => {
    const params = new URL(r.request().url()).searchParams;
    requests.push(params.toString());
    return r.fulfill({
      json: {
        items: [],
        meta: {
          page: Number(params.get("page")),
          size: Number(params.get("size")),
          totalItems: 3000,
          totalPages: 150,
          hasNext: true,
          hasPrevious: true,
        },
      },
    });
  });
  await page.goto("/downloads/jobs");
  const input = page.getByRole("spinbutton", { name: "Página", exact: true });
  await expect(input).toBeEnabled();
  await input.fill("100");
  await input.press("Enter");
  await expect
    .poll(() =>
      requests.some((q) => new URLSearchParams(q).get("page") === "99"),
    )
    .toBe(true);
});
test("all sidebar icon centers remain fixed including logo and settings", async ({
  page,
}) => {
  await page.goto("/settings/general");
  const icons = page.locator(
    ".brand-icon svg, #sidebar nav a svg, .settings-link svg, .sidebar-toggle svg",
  );
  await expect(icons).toHaveCount(9);
  const positions = () =>
    icons.evaluateAll((nodes) =>
      nodes.map((node) => {
        const r = node.getBoundingClientRect();
        return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
      }),
    );
  const before = await positions();
  for (const p of before) expect(p.x).toBe(38);
  await page.getByRole("button", { name: "Plegar menú lateral" }).click();
  expect(await positions()).toEqual(before);
  await page.getByRole("button", { name: "Desplegar menú lateral" }).click();
  expect(await positions()).toEqual(before);
  await page.screenshot({
    path: "test-results/aligned-sidebar.png",
    fullPage: true,
  });
});
test("book has direct ePubLibre link and favicon is served", async ({
  page,
}) => {
  await page.route("**/api/catalog/books/14936", (r) =>
    r.fulfill({ json: book }),
  );
  await page.route("**/api/catalog/books/14936/magnets", (r) =>
    r.fulfill({ json: [] }),
  );
  await page.goto("/catalog/14936");
  const link = page.getByRole("link", {
    name: "Ver en ePubLibre",
    exact: true,
  });
  await expect(link).toHaveAttribute(
    "href",
    "https://www.epublibre.org/libro/detalle/14936",
  );
  await expect(link).toHaveAttribute("target", "_blank");
  await expect(link).toHaveAttribute("rel", "noopener noreferrer");
  await expect(page.locator('link[rel="icon"]')).toHaveAttribute(
    "href",
    /^data:image\/svg\+xml/,
  );
  const favicon = await page.request.get("/favicon.svg");
  expect(favicon.ok()).toBe(true);
  expect(await favicon.text()).toContain("<svg");
});

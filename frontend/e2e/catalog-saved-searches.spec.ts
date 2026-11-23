import { test, expect } from "./fixtures";
test("personal searches save drafts, apply, update, rename and delete without table settings", async ({
  page,
}) => {
  type Search = { id: string; name: string; filters: Record<string, string[]> };
  let saved: Search[] = [];
  const bodies: Search[] = [];
  await page.route("**/api/catalog/saved-searches**", async (route) => {
    const request = route.request();
    if (request.method() === "GET") return route.fulfill({ json: saved });
    if (request.method() === "DELETE") {
      saved = [];
      return route.fulfill({ status: 200, body: "" });
    }
    const entry = { ...request.postDataJSON(), id: "personal" };
    bodies.push(entry);
    saved = [entry];
    return route.fulfill({ json: entry });
  });
  await page.route("**/api/catalog/books?**", (route) => {
    const index = Number(
      new URL(route.request().url()).searchParams.get("page") ?? 0,
    );
    return route.fulfill({
      json: {
        items: [
          {
            eplId: 1,
            title: "Book",
            author: "Author",
            revision: 1,
            download: { items: [] },
          },
        ],
        meta: {
          page: index,
          size: 20,
          totalItems: 100,
          totalPages: 5,
          hasNext: index < 4,
          hasPrevious: index > 0,
        },
      },
    });
  });
  await page.goto("/catalog?page=2&size=20&sort=author,desc");
  await page
    .getByRole("checkbox", { name: "Seleccionar Book", exact: true })
    .check();
  await page.locator(".catalog-search summary").click();
  await page.getByLabel("Autor", { exact: true }).fill("Orwell");
  await page
    .getByRole("button", { name: "Guardar búsqueda", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByLabel("Nombre", { exact: true })
    .fill("Mis autores");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Guardar búsqueda", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect(bodies[0].filters).toEqual({ author: ["Orwell"] });
  await page
    .getByRole("textbox", { name: "Búsquedas guardadas", exact: true })
    .click();
  await page
    .getByRole("textbox", { name: "Búsquedas guardadas", exact: true })
    .press("ArrowDown");
  await page
    .getByRole("textbox", { name: "Búsquedas guardadas", exact: true })
    .press("Enter");
  await expect(page).toHaveURL(/author=Orwell/);
  const url = new URL(page.url());
  expect(url.searchParams.get("page")).toBe("0");
  expect(url.searchParams.get("sort")).toBe("author,desc");
  await expect(
    page.getByRole("checkbox", { name: "Seleccionar Book", exact: true }),
  ).not.toBeChecked();
  // Applying the same criteria must also discard unsaved edits.
  await page.getByLabel("Título", { exact: true }).fill("Discard this draft");
  await page
    .getByRole("textbox", { name: "Búsquedas guardadas", exact: true })
    .click();
  await page
    .getByRole("textbox", { name: "Búsquedas guardadas", exact: true })
    .press("ArrowDown");
  await page
    .getByRole("textbox", { name: "Búsquedas guardadas", exact: true })
    .press("Enter");
  await expect(page.getByLabel("Título", { exact: true })).toHaveValue("");
  await page.getByLabel("Título", { exact: true }).fill("1984");
  await page.getByRole("button", { name: "Gestionar", exact: true }).click();
  await page
    .getByRole("menuitem", { name: "Actualizar con los filtros actuales" })
    .click();
  await expect.poll(() => saved[0].filters.title).toEqual(["1984"]);
  await page.getByRole("button", { name: "Gestionar", exact: true }).click();
  await page.getByRole("menuitem", { name: "Renombrar" }).click();
  await page
    .getByRole("dialog")
    .getByLabel("Nombre", { exact: true })
    .fill("Renombrada");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Guardar búsqueda", exact: true })
    .click();
  await expect(
    page.getByRole("textbox", { name: "Búsquedas guardadas", exact: true }),
  ).toHaveValue("Renombrada");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.getByRole("button", { name: "Plegar menú lateral" }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(() => document.body.scrollWidth <= window.innerWidth),
  ).toBe(true);
  await page.screenshot({ path: "/tmp/eplsync-saved-searches-mobile.png" });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.reload();
  await page.locator(".catalog-search summary").click();
  await page
    .getByRole("textbox", { name: "Búsquedas guardadas", exact: true })
    .click();
  await page.getByRole("option", { name: "Renombrada" }).click();
  await page.getByRole("button", { name: "Gestionar", exact: true }).click();
  await page.getByRole("menuitem", { name: "Eliminar búsqueda" }).click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Eliminar búsqueda" })
    .click();
  await expect.poll(() => saved.length).toBe(0);
  await expect(
    page.getByRole("textbox", { name: "Búsquedas guardadas", exact: true }),
  ).toHaveValue("");
  await expect(
    page.getByRole("button", { name: "Gestionar", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "Aplicar", exact: true }),
  ).toHaveCount(0);
});

test("saved searches share the footer on wide screens and wrap below search actions on narrow screens", async ({
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
  await page.goto("/catalog");
  await page.locator(".catalog-search summary").click();
  const actions = page.locator(".catalog-search-footer > .action-row");
  const saved = page.locator(
    ".catalog-search-footer > .saved-catalog-searches",
  );
  const wideActions = (await actions.boundingBox())!;
  const wideSaved = (await saved.boundingBox())!;
  expect(wideSaved.x).toBeGreaterThan(wideActions.x + wideActions.width);
  expect(Math.abs(wideSaved.y - wideActions.y)).toBeLessThan(3);
  await page.setViewportSize({ width: 900, height: 1000 });
  const narrowActions = (await actions.boundingBox())!;
  const narrowSaved = (await saved.boundingBox())!;
  expect(narrowSaved.y).toBeGreaterThanOrEqual(
    narrowActions.y + narrowActions.height,
  );
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(() => document.body.scrollWidth <= window.innerWidth),
  ).toBe(true);
  await expect(
    page.getByRole("button", { name: "Guardar búsqueda", exact: true }),
  ).toBeVisible();
});

for (const button of ["Limpiar", "Limpiar filtros"]) {
  test(`${button} clears the saved search selection without changing the saved search`, async ({
    page,
  }) => {
    const saved = [
      { id: "personal", name: "Mis autores", filters: { author: ["Orwell"] } },
    ];
    const mutations: string[] = [];
    await page.route("**/api/catalog/saved-searches**", (route) => {
      if (route.request().method() !== "GET")
        mutations.push(route.request().method());
      return route.fulfill({ json: saved });
    });
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
    await page.goto("/catalog?page=0&size=20&sort=title%2Casc");
    const heading = page.locator(".catalog-search summary");
    await heading.click();
    const selector = page.getByRole("textbox", {
      name: "Búsquedas guardadas",
      exact: true,
    });
    await selector.click();
    await page
      .getByRole("option", { name: "Mis autores", exact: true })
      .click();
    await expect(page).toHaveURL(/author=Orwell/);
    await expect(selector).toHaveValue("Mis autores");
    await page.getByRole("button", { name: button, exact: true }).click();
    await expect
      .poll(() => new URL(page.url()).searchParams.has("author"))
      .toBe(false);
    if (button === "Limpiar filtros") {
      await expect(selector).toBeHidden();
      await heading.click();
    }
    await expect(selector).toHaveValue("");
    await expect(
      page.getByRole("button", { name: "Gestionar", exact: true }),
    ).toBeDisabled();
    expect(mutations).toEqual([]);
    await selector.click();
    await page
      .getByRole("option", { name: "Mis autores", exact: true })
      .click();
    await expect(page).toHaveURL(/author=Orwell/);
    await expect(selector).toHaveValue("Mis autores");
    expect(mutations).toEqual([]);
  });
}

test("catalog authorship exclusions are saved, restored, applied and cleared", async ({
  page,
}) => {
  type Entry = { id: string; name: string; filters: Record<string, string[]> };
  let saved: Entry[] = [];
  const requests: URLSearchParams[] = [];
  await page.route("**/api/catalog/saved-searches**", (route) => {
    if (route.request().method() === "GET")
      return route.fulfill({ json: saved });
    saved = [{ ...route.request().postDataJSON(), id: "authorship" }];
    return route.fulfill({ json: saved[0] });
  });
  await page.route("**/api/catalog/books?**", (route) => {
    requests.push(new URL(route.request().url()).searchParams);
    return route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
      },
    });
  });
  await page.goto("/catalog");
  await page.locator(".catalog-search summary").click();
  const aa = page.getByRole("checkbox", {
    name: "Excluir libros de «AA. VV.»",
    exact: true,
  });
  const limit = page.getByRole("checkbox", {
    name: "Excluir libros con más de",
    exact: true,
  });
  const max = page.getByRole("textbox", {
    name: "Máximo de autores por libro",
    exact: true,
  });
  await expect(aa).not.toBeChecked();
  await expect(max).toBeDisabled();
  await aa.check();
  await limit.check();
  await max.fill("2");
  await page
    .getByRole("button", { name: "Guardar búsqueda", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog
    .getByRole("textbox", { name: "Nombre", exact: true })
    .fill("Sin antologías");
  await dialog
    .getByRole("button", { name: "Guardar búsqueda", exact: true })
    .click();
  await expect(dialog).toBeHidden();
  expect(saved[0].filters).toEqual({
    excludeAnthologies: ["true"],
    maxAuthors: ["2"],
  });
  await aa.uncheck();
  await limit.uncheck();
  await page
    .getByRole("textbox", { name: "Búsquedas guardadas", exact: true })
    .click();
  await page
    .getByRole("option", { name: "Sin antologías", exact: true })
    .click();
  await expect(aa).toBeChecked();
  await expect(max).toHaveValue("2");
  await expect.poll(() => requests.at(-1)?.get("maxAuthors")).toBe("2");
  expect(requests.at(-1)?.get("excludeAnthologies")).toBe("true");
  await page.reload();
  await page.locator(".catalog-search summary").click();
  await expect(aa).toBeChecked();
  await expect(max).toHaveValue("2");
  await page.screenshot({
    path: "/tmp/eplsync-catalog-authorship.png",
    fullPage: true,
  });
  await page
    .getByRole("button", { name: "Plegar menú lateral", exact: true })
    .click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator(".sidebar")).toHaveAttribute("inert", "");
  await expect(aa).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "/tmp/eplsync-catalog-authorship-mobile.png",
    fullPage: true,
  });
  await page
    .getByRole("button", { name: "Limpiar", exact: true })
    .first()
    .click();
  await expect(aa).not.toBeChecked();
  await expect(max).toBeDisabled();
  expect(new URL(page.url()).searchParams.has("maxAuthors")).toBe(false);
});

test("Authors and ranges share a grid with Authors spanning two columns", async ({
  page,
}) => {
  await page.route("**/api/catalog/books?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
      },
    }),
  );
  await page.goto("/catalog");
  await page.locator(".catalog-search summary").click();
  const frame = page.locator(".catalog-author-filters");
  await expect(frame.locator("legend")).toHaveText("Autores");
  const grid = page.locator(".catalog-range-grid");
  const ranges = grid.locator(".catalog-compact-range");
  expect(
    await grid.locator(":scope > fieldset > legend").allTextContents(),
  ).toEqual([
    "Autores",
    "Páginas",
    "Año de publicación",
    "Publicado en EPL",
    "Añadido a EPL Sync",
  ]);
  for (const width of [2560, 1800, 1440, 900, 390]) {
    await page.setViewportSize({ width, height: 1000 });
    await expect
      .poll(async () => {
        const box = (await frame.boundingBox())!;
        const range = (await ranges.first().boundingBox())!;
        return Math.abs(
          box.width - (width > 600 ? range.width * 2 + 16 : range.width),
        );
      })
      .toBeLessThan(2);
    await expect.poll(async () => {
      const authorsHeight = (await frame.boundingBox())!.height;
      const heights = await ranges.evaluateAll(elements => elements.map(element => element.getBoundingClientRect().height));
      return Math.max(...heights.map(height => Math.abs(height - authorsHeight)));
    }).toBeLessThan(2);
    if (width === 2560) {
      const authorsBox = (await frame.boundingBox())!;
      const pagesBox = (await ranges.nth(0).boundingBox())!;
      const yearBox = (await ranges.nth(1).boundingBox())!;
      const publishedBox = (await ranges.nth(2).boundingBox())!;
      expect(Math.abs(authorsBox.y - pagesBox.y)).toBeLessThan(2);
      expect(Math.abs(authorsBox.y - yearBox.y)).toBeLessThan(2);
      expect(publishedBox.y).toBeGreaterThan(authorsBox.y);
    }
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await expect(
      page.getByRole("checkbox", {
        name: "Excluir libros de «AA. VV.»",
        exact: true,
      }),
    ).toBeVisible();
    const legendBox = (await frame.locator("legend").boundingBox())!;
    const optionsBox = (await frame.locator(".catalog-author-options").boundingBox())!;
    const frameBox = (await frame.boundingBox())!;
    const topSpace = optionsBox.y - (legendBox.y + legendBox.height);
    const bottomSpace = frameBox.y + frameBox.height - optionsBox.y - optionsBox.height;
    expect(Math.abs(topSpace - bottomSpace)).toBeLessThan(2);
    if (width === 2560 || width === 390)
      await page.screenshot({
        path: `/tmp/eplsync-authors-frame-${width}.png`,
        fullPage: true,
      });
  }
});

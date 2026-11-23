import { test, expect, type Page } from "./fixtures";
async function setExclusion(page: Page, name: string, selected: boolean) {
  await page
    .getByRole("textbox", { name: "Excluir presentes en", exact: true })
    .click();
  const option = page.getByRole("option", { name, exact: true });
  if ((await option.getAttribute("aria-selected")) !== String(selected))
    await option.click();
  await page.getByRole("heading", { name: "Descubrir", exact: true }).click();
}
async function expectExclusion(page: Page, name: string, selected: boolean) {
  await page
    .getByRole("textbox", { name: "Excluir presentes en", exact: true })
    .click();
  await expect(page.getByRole("option", { name, exact: true })).toHaveAttribute(
    "aria-selected",
    String(selected),
  );
  await page.getByRole("heading", { name: "Descubrir", exact: true }).click();
}

async function openDetected(page: Page, criterion: string) {
  await page
    .getByRole("rowgroup", { name: criterion, exact: true })
    .getByRole("button")
    .click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  return dialog;
}
async function chooseDetected(page: Page, criterion: string, value: string) {
  const dialog = await openDetected(page, criterion);
  await dialog.getByRole("button", { name: /^Quitar selección \(/ }).click();
  await dialog
    .getByRole("checkbox", { name: `Seleccionar ${value}`, exact: true })
    .check();
  await dialog
    .getByRole("button", { name: "Aplicar selección", exact: true })
    .click();
  await expect(dialog).toBeHidden();
}

test("discovery refreshes suggestions when the author selector is reopened", async ({
  page,
}) => {
  const items = [
    {
      value: "brandon sanderson",
      label: "Brandon Sanderson",
      counts: { DOWNLOADS: 1 },
    },
  ];
  await page.route("**/api/catalog/discovery/values?**", (route) =>
    route.fulfill({
      json: {
        items,
        meta: { page: 0, size: 100, totalItems: items.length, totalPages: 1 },
      },
    }),
  );
  await page.goto("/discover");
  let dialog = await openDetected(page, "Autores");
  await expect(
    dialog.getByText("Brandon Sanderson", { exact: true }),
  ).toBeVisible();
  await page.keyboard.press("Escape");
  items.push({
    value: "terry pratchett",
    label: "Terry Pratchett",
    counts: { DOWNLOADS: 1 },
  });
  dialog = await openDetected(page, "Autores");
  await expect(
    dialog.getByText("Terry Pratchett", { exact: true }),
  ).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(
    page
      .getByRole("rowgroup", { name: "Autores", exact: true })
      .locator(".criterion-count"),
  ).toBeVisible();
});
const book = {
  eplId: 2,
  title: "Libro por descubrir",
  author: "Fríida Ísberg",
  revision: 1,
  language: "es",
  download: { items: [] },
  calibre: null,
  discoveryMatches: [
    { value: "Fríida Ísberg", source: "DOWNLOADS" },
    { value: "Fríida Ísberg", source: "CALIBRE" },
  ],
};
test("discovery preserves filters, selection, navigation and separate preferences", async ({
  page,
}) => {
  const requests: URLSearchParams[] = [];
  await page.route("**/api/catalog/discovery/values?**", (route) =>
    route.fulfill({
      json: {
        items: [
          {
            value: "fríida ísberg",
            label: "Fríida Ísberg",
            counts: { DOWNLOADS: 1, CALIBRE: 1 },
          },
        ],
        meta: { page: 0, size: 100, totalItems: 1, totalPages: 1 },
      },
    }),
  );
  await page.route("**/api/catalog/books?**", (route) => {
    requests.push(new URL(route.request().url()).searchParams);
    return route.fulfill({
      json: {
        items: [book],
        meta: { page: 0, size: 20, totalItems: 1, totalPages: 1 },
      },
    });
  });
  await page.route("**/api/catalog/books/2/magnets", (route) =>
    route.fulfill({ json: [] }),
  );
  await page.route("**/api/catalog/books/2", (route) =>
    route.fulfill({ json: book }),
  );
  await page.goto("/discover");
  await expect(page.getByRole("heading", { name: "Descubrir" })).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Buscar", exact: true }).first(),
  ).toBeEnabled();
  expect(requests).toHaveLength(0);
  await page.getByRole("textbox", { name: "Basarse en", exact: true }).click();
  await page.getByRole("option", { name: "Ambos", exact: true }).click();
  await page
    .getByRole("button", { name: "Buscar", exact: true })
    .first()
    .click();
  await expect(page.locator(".catalog-table tbody")).toContainText(book.title);
  await expect(
    page.getByRole("columnheader", { name: /Coincidencia/ }),
  ).toBeVisible();
  expect(requests.at(-1)?.get("discoverySource")).toBe("BOTH");
  expect(requests.at(-1)?.get("torrentPresence")).toBe("ABSENT");
  expect(requests.at(-1)?.get("calibrePresence")).toBe("ABSENT");
  let exported: any;
  await page.route("**/api/catalog/data/export/preview", (route) => {
    exported = route.request().postDataJSON();
    return route.fulfill({ json: { text: "preview", rows: 1 } });
  });
  await page
    .getByRole("button", { name: "Seleccionar todo (1)", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Exportar datos", exact: true })
    .click();
  await expect.poll(() => exported?.filters?.discoverySource).toBe("BOTH");
  expect(exported.filters.discoveryExcludePending).toBe("true");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Cerrar", exact: true })
    .click();
  await page
    .locator(".catalog-table tbody")
    .getByRole("link", { name: book.title, exact: true })
    .click();
  await page
    .getByRole("link", { name: "Volver a Descubrir", exact: true })
    .click();
  await expect(page).toHaveURL(/\/discover\?/);
  await expect(page.locator(".catalog-table tbody")).toContainText(book.title);
  await page.goto("/catalog");
  await expect(
    page.getByRole("columnheader", { name: /Coincidencia/ }),
  ).toHaveCount(0);
  await page.goto("/discover");
  await expect(
    page.getByRole("textbox", { name: "Basarse en", exact: true }),
  ).toHaveValue("Ambos");
  await chooseDetected(page, "Autores", "Fríida Ísberg");
  await page
    .getByRole("button", { name: "Buscar", exact: true })
    .first()
    .click();
  await expect
    .poll(() => requests.at(-1)?.getAll("discoveryValues"))
    .toEqual(["AUTHOR:fríida ísberg"]);
  await page.screenshot({ path: "/tmp/eplsync-discovery.png", fullPage: true });
});
test("catalog permission alone cannot access discovery", async ({ page }) => {
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      json: {
        id: "reader",
        username: "reader",
        role: "USER",
        permissions: ["CATALOG_READ"],
        mustChangePassword: false,
      },
    }),
  );
  await page.goto("/discover");
  await expect(
    page.getByRole("link", { name: "Descubrir", exact: true }),
  ).toHaveCount(0);
  await expect(page.getByRole("alert")).toBeVisible();
});

test("discovery and advanced search edit the same presence filters before applying", async ({
  page,
}) => {
  const requests: URLSearchParams[] = [];
  await page.route("**/api/catalog/discovery/values?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 100, totalItems: 0, totalPages: 0 },
      },
    }),
  );
  await page.route("**/api/catalog/books?**", (route) => {
    requests.push(new URL(route.request().url()).searchParams);
    return route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
      },
    });
  });
  await page.goto("/discover");
  const advanced = page.locator(".catalog-search");
  await advanced.locator("summary").click();
  const torrent = page
    .locator(".catalog-library-filter")
    .filter({ has: page.locator("legend", { hasText: "Torrent" }) });
  const calibre = page
    .locator(".catalog-library-filter")
    .filter({ has: page.locator("legend", { hasText: "Calibre" }) });
  await expect(
    torrent.getByRole("textbox", { name: "Presencia", exact: true }),
  ).toHaveValue("Ausentes");
  await expect(
    calibre.getByRole("textbox", { name: "Presencia", exact: true }),
  ).toHaveValue("Ausentes");
  await setExclusion(page, "Descargas", false);
  await expect(
    torrent.getByRole("textbox", { name: "Presencia", exact: true }),
  ).toHaveValue("");
  await torrent
    .getByRole("textbox", { name: "Presencia", exact: true })
    .click();
  await page.getByRole("option", { name: "Presentes", exact: true }).click();
  await expectExclusion(page, "Descargas", false);
  await torrent.getByRole("textbox", { name: "Revisión", exact: true }).click();
  await page.getByRole("option", { name: "Actualizada", exact: true }).click();
  await setExclusion(page, "Descargas", true);
  await expect(
    torrent.getByRole("textbox", { name: "Presencia", exact: true }),
  ).toHaveValue("Ausentes");
  await expect(
    torrent.getByRole("textbox", { name: "Revisión", exact: true }),
  ).toHaveValue("");
  await expect(
    torrent.getByRole("textbox", { name: "Revisión", exact: true }),
  ).toBeDisabled();
  await calibre
    .getByRole("button", {
      name: "Quitar filtro de presencia de Calibre",
      exact: true,
    })
    .click();
  await expectExclusion(page, "Calibre", false);
  await setExclusion(page, "Calibre", true);
  await expect(
    calibre.getByRole("textbox", { name: "Presencia", exact: true }),
  ).toHaveValue("Ausentes");
  await expectExclusion(page, "Trabajos pendientes", true);
  await advanced.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect
    .poll(() => requests.at(-1)?.get("torrentPresence"))
    .toBe("ABSENT");
  expect(requests.at(-1)?.get("calibrePresence")).toBe("ABSENT");
  expect(requests.at(-1)?.get("discoverySource")).toBe("DOWNLOADS");
  expect(requests.at(-1)?.has("discoveryExcludeDownloads")).toBe(false);
  expect(requests.at(-1)?.has("torrentRevision")).toBe(false);
  await page.reload();
  await expectExclusion(page, "Descargas", true);
  await expectExclusion(page, "Calibre", true);
});

test("combined discovery criteria retain independent selections and All/Any across reloads", async ({
  page,
}) => {
  const requests: URLSearchParams[] = [];
  await page.route("**/api/catalog/discovery/values?**", (route) => {
    const criterion = new URL(route.request().url()).searchParams.get(
      "criterion",
    );
    const names: Record<string, [string, string][]> = {
      AUTHOR: [["fríida ísberg", "Fríida Ísberg"]],
      COLLECTION: [["saga", "Saga"]],
      GENRE: [
        ["fantasía", "Fantasía"],
        ["aventura", "Aventura"],
      ],
      LANGUAGE: [
        ["es", "es"],
        ["en", "en"],
      ],
      YEAR: [["2000", "2000"]],
    };
    const items = (names[criterion ?? "AUTHOR"] ?? []).map(
      ([value, label]) => ({ value, label, counts: { DOWNLOADS: 1 } }),
    );
    return route.fulfill({
      json: {
        items,
        meta: { page: 0, size: 100, totalItems: items.length, totalPages: 1 },
      },
    });
  });
  await page.route("**/api/catalog/books?**", (route) => {
    requests.push(new URL(route.request().url()).searchParams);
    return route.fulfill({
      json: {
        items: [book],
        meta: { page: 0, size: 20, totalItems: 1, totalPages: 1 },
      },
    });
  });
  await page.goto("/discover");
  const criteriaSelect = page.getByRole("textbox", {
    name: "Buscar coincidencias por",
    exact: true,
  });
  await criteriaSelect.click();
  for (const criterion of ["Géneros", "Idiomas", "Año de publicación"])
    await page.getByRole("option", { name: criterion, exact: true }).click();
  await page.getByRole("heading", { name: "Descubrir", exact: true }).click();
  const genre = page.getByRole("rowgroup", { name: "Géneros", exact: true });
  await chooseDetected(page, "Géneros", "Fantasía");
  const language = page.getByRole("rowgroup", { name: "Idiomas", exact: true });
  await chooseDetected(page, "Idiomas", "Español");
  const search = page
    .getByRole("button", { name: "Buscar", exact: true })
    .first();
  await search.click();
  await expect.poll(() => requests.at(-1)?.get("discoveryMatch")).toBe("ALL");
  expect(requests.at(-1)?.getAll("discoveryCriteria")).toEqual([
    "AUTHOR",
    "GENRE",
    "LANGUAGE",
    "YEAR",
  ]);
  expect(requests.at(-1)?.getAll("discoveryValues")).toEqual([
    "GENRE:fantasía",
    "LANGUAGE:es",
  ]);
  await page
    .getByRole("textbox", { name: "Combinar criterios", exact: true })
    .click();
  await page.getByRole("option", { name: "Cualquiera", exact: true }).click();
  await search.click();
  await expect.poll(() => requests.at(-1)?.get("discoveryMatch")).toBe("ANY");
  await page.reload();
  await expect(
    page.getByRole("textbox", { name: "Combinar criterios", exact: true }),
  ).toHaveValue("Cualquiera");
  await expect(genre.locator(".criterion-selected")).toHaveText("1");
  await expect(language.locator(".criterion-selected")).toHaveText("1");
  // Removed criteria must not leave hidden constraints in the request.
  await criteriaSelect.click();
  await page.getByRole("option", { name: "Géneros", exact: true }).click();
  await page.getByRole("heading", { name: "Descubrir", exact: true }).click();
  await search.click();
  await expect
    .poll(() => requests.at(-1)?.getAll("discoveryCriteria"))
    .toEqual(["AUTHOR", "LANGUAGE", "YEAR"]);
  expect(requests.at(-1)?.getAll("discoveryValues")).toEqual(["LANGUAGE:es"]);
  await page.goto("/discover");
  await expect(
    page.getByRole("textbox", { name: "Combinar criterios", exact: true }),
  ).toHaveValue("Cualquiera");
  await expect(
    page.getByRole("rowgroup", { name: "Año de publicación", exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: "/tmp/eplsync-discovery-combined.png",
    fullPage: true,
  });
});

test("discovery saves unapplied configuration and displays grouped origin badges", async ({
  page,
}) => {
  type Saved = { id: string; name: string; filters: Record<string, string[]> };
  let saved: Saved[] = [];
  const requests: URLSearchParams[] = [];
  await page.route("**/api/catalog/saved-searches**", (route) => {
    if (route.request().method() === "GET")
      return route.fulfill({ json: saved });
    const item = { id: "discovery-saved", ...route.request().postDataJSON() };
    saved = [item];
    return route.fulfill({ json: item });
  });
  await page.route("**/api/catalog/discovery/values?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 100, totalItems: 0, totalPages: 0 },
      },
    }),
  );
  await page.route("**/api/catalog/books?**", (route) => {
    requests.push(new URL(route.request().url()).searchParams);
    return route.fulfill({
      json: {
        items: [
          {
            ...book,
            discoveryMatches: [
              {
                criterion: "AUTHOR",
                value: "Brandon Sanderson",
                source: "DOWNLOADS",
              },
              {
                criterion: "AUTHOR",
                value: "Brandon Sanderson",
                source: "CALIBRE",
              },
            ],
          },
        ],
        meta: { page: 0, size: 20, totalItems: 1, totalPages: 1 },
      },
    });
  });
  await page.goto("/discover");
  await expect(
    page.getByText(
      "Todos exige una coincidencia en cada criterio; Cualquiera, en al menos uno.",
      { exact: true },
    ),
  ).toHaveCount(0);
  await page.getByText("Combinar criterios", { exact: true }).hover();
  await expect(page.getByRole("tooltip")).toContainText(
    "Todos exige una coincidencia",
  );
  await page.getByRole("heading", { name: "Descubrir", exact: true }).hover();
  await page
    .getByRole("textbox", { name: "Buscar coincidencias por", exact: true })
    .click();
  await page.getByRole("option", { name: "Idiomas", exact: true }).click();
  await page.getByRole("heading", { name: "Descubrir", exact: true }).click();
  await page
    .getByRole("textbox", { name: "Combinar criterios", exact: true })
    .click();
  await page.getByRole("option", { name: "Cualquiera", exact: true }).click();
  const save = page.getByRole("button", {
    name: "Guardar búsqueda",
    exact: true,
  });
  await expect(save).toBeVisible();
  expect(requests).toHaveLength(0);
  await save.click();
  const dialog = page.getByRole("dialog");
  await dialog
    .getByRole("textbox", { name: "Nombre", exact: true })
    .fill("Mis descubrimientos");
  await dialog
    .getByRole("button", { name: "Guardar búsqueda", exact: true })
    .click();
  await expect(dialog).toBeHidden();
  expect(saved[0].filters.discoveryCriteria).toEqual(["AUTHOR", "LANGUAGE"]);
  expect(saved[0].filters.discoveryMatch).toEqual(["ANY"]);
  expect(saved[0].filters.torrentPresence).toEqual(["ABSENT"]);
  expect(saved[0].filters.calibrePresence).toEqual(["ABSENT"]);
  expect(saved[0].filters.discoveryExcludePending).toEqual(["true"]);
  await page
    .getByRole("textbox", { name: "Combinar criterios", exact: true })
    .click();
  await page.getByRole("option", { name: "Todos", exact: true }).click();
  await page
    .getByRole("textbox", { name: "Búsquedas guardadas", exact: true })
    .click();
  await page
    .getByRole("option", { name: "Mis descubrimientos", exact: true })
    .click();
  await expect.poll(() => requests.at(-1)?.get("discoveryMatch")).toBe("ANY");
  await expect(
    page.getByRole("textbox", { name: "Combinar criterios", exact: true }),
  ).toHaveValue("Cualquiera");
  const matches = page.locator(".discovery-matches");
  await expect(
    matches.getByText("Brandon Sanderson", { exact: true }),
  ).toHaveCount(1);
  await expect(matches.locator(".mantine-Badge-root")).toHaveText([
    "Calibre",
    "Descargas",
  ]);
  const footer = page.locator(".catalog-search-footer").first();
  const search = footer.getByRole("button", { name: "Buscar", exact: true });
  expect((await search.boundingBox())!.x).toBeLessThan(
    (await save.boundingBox())!.x,
  );
  await page
    .getByRole("button", {
      name: "Limpiar: Buscar coincidencias por",
      exact: true,
    })
    .click();
  await expect(search).toBeDisabled();
  await expect(
    page.getByRole("rowgroup", { name: "Autores", exact: true }),
  ).toHaveCount(0);
  await page.screenshot({
    path: "/tmp/eplsync-discovery-design.png",
    fullPage: true,
  });
});

test("saved searches and their management stay isolated between Catalog and Discover", async ({
  page,
}) => {
  type Entry = { id: string; name: string; filters: Record<string, string[]> };
  const records: Record<string, Entry[]> = {
    CATALOG: [
      {
        id: "catalog-shared-name",
        name: "Mis libros",
        filters: { author: ["Orwell"] },
      },
      { id: "catalog-only", name: "Solo Catálogo", filters: {} },
    ],
    DISCOVERY: [
      {
        id: "discovery-shared-name",
        name: "Mis libros",
        filters: {
          discoverySource: ["DOWNLOADS"],
          discoveryCriteria: ["AUTHOR", "LANGUAGE"],
          discoveryMatch: ["ANY"],
          torrentPresence: ["ABSENT"],
          calibrePresence: ["ABSENT"],
          discoveryExcludePending: ["true"],
        },
      },
      {
        id: "discovery-only",
        name: "Solo Descubrir",
        filters: { discoverySource: ["DOWNLOADS"] },
      },
    ],
  };
  const mutations: string[] = [];
  await page.route("**/api/catalog/saved-searches**", (route) => {
    const request = route.request(),
      url = new URL(request.url()),
      scope = url.searchParams.get("scope") ?? "CATALOG";
    if (request.method() === "GET")
      return route.fulfill({ json: records[scope] });
    mutations.push(`${request.method()}:${scope}`);
    const id = url.pathname.split("/").at(-1)!;
    expect(records[scope].some((item) => item.id === id)).toBe(true);
    if (request.method() === "DELETE") {
      records[scope] = records[scope].filter((item) => item.id !== id);
      return route.fulfill({ status: 200, body: "" });
    }
    const item = { ...request.postDataJSON(), id };
    records[scope] = records[scope].map((previous) =>
      previous.id === id ? item : previous,
    );
    return route.fulfill({ json: item });
  });
  await page.route("**/api/catalog/discovery/values?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 100, totalItems: 0, totalPages: 0 },
      },
    }),
  );
  await page.route("**/api/catalog/books?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
      },
    }),
  );
  const selector = page.getByRole("textbox", {
    name: "Búsquedas guardadas",
    exact: true,
  });
  await page.goto("/discover");
  await expect(page.locator(".catalog-search")).not.toHaveAttribute("open");
  await selector.click();
  await expect(
    page.getByRole("option", { name: "Solo Descubrir", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("option", { name: "Solo Catálogo", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("option", { name: "Mis libros", exact: true }).click();
  await expect(page).toHaveURL(/discoveryMatch=ANY/);
  await expect(page.locator(".catalog-search")).not.toHaveAttribute("open");
  await expect(
    page.getByRole("textbox", { name: "Combinar criterios", exact: true }),
  ).toHaveValue("Cualquiera");
  await page.getByRole("link", { name: "Catálogo", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Catálogo", exact: true }),
  ).toBeVisible();
  await page.locator(".catalog-search summary").click();
  await selector.click();
  await expect(
    page.getByRole("option", { name: "Solo Catálogo", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("option", { name: "Solo Descubrir", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("option", { name: "Mis libros", exact: true }).click();
  await expect(page).toHaveURL(/author=Orwell/);
  await expect(page.locator(".catalog-search")).toHaveAttribute("open", "");
  expect(new URL(page.url()).searchParams.has("discoverySource")).toBe(false);
  await page.getByRole("link", { name: "Descubrir", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Descubrir", exact: true }),
  ).toBeVisible();
  await selector.click();
  await page.getByRole("option", { name: "Mis libros", exact: true }).click();
  await page
    .getByRole("textbox", { name: "Combinar criterios", exact: true })
    .click();
  await page.getByRole("option", { name: "Todos", exact: true }).click();
  await page.getByRole("button", { name: "Gestionar", exact: true }).click();
  await page
    .getByRole("menuitem", {
      name: "Actualizar con los filtros actuales",
      exact: true,
    })
    .click();
  await expect
    .poll(() => records.DISCOVERY[0].filters.discoveryMatch)
    .toEqual(["ALL"]);
  await page.getByRole("button", { name: "Gestionar", exact: true }).click();
  await page.getByRole("menuitem", { name: "Renombrar", exact: true }).click();
  await page
    .getByRole("dialog")
    .getByRole("textbox", { name: "Nombre", exact: true })
    .fill("Mis descubrimientos");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Guardar búsqueda", exact: true })
    .click();
  await expect(selector).toHaveValue("Mis descubrimientos");
  await page.getByRole("button", { name: "Gestionar", exact: true }).click();
  await page
    .getByRole("menuitem", { name: "Eliminar búsqueda", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Eliminar búsqueda", exact: true })
    .click();
  await expect.poll(() => records.DISCOVERY.length).toBe(1);
  expect(records.CATALOG[0]).toEqual({
    id: "catalog-shared-name",
    name: "Mis libros",
    filters: { author: ["Orwell"] },
  });
  expect(mutations).toEqual([
    "PUT:DISCOVERY",
    "PUT:DISCOVERY",
    "DELETE:DISCOVERY",
  ]);
});

test("Discover Clear restores defaults for applied searches and unapplied drafts without deleting saved searches", async ({
  page,
}) => {
  const saved = {
    id: "reset-saved",
    name: "Configurada",
    filters: {
      discoverySource: ["BOTH"],
      discoveryCriteria: ["AUTHOR", "LANGUAGE"],
      discoveryMatch: ["ANY"],
      discoveryValues: ["AUTHOR:brandon sanderson", "LANGUAGE:es"],
      discoveryExcludePending: ["false"],
      torrentPresence: ["PRESENT"],
      calibrePresence: ["PRESENT"],
      title: ["Título guardado"],
    },
  };
  let mutations = 0;
  await page.route("**/api/catalog/saved-searches**", (route) => {
    if (route.request().method() !== "GET") mutations++;
    return route.fulfill({ json: [saved] });
  });
  await page.route("**/api/catalog/discovery/values?**", (route) => {
    const criterion = new URL(route.request().url()).searchParams.get(
      "criterion",
    );
    return route.fulfill({
      json: {
        items: [
          {
            value: criterion === "LANGUAGE" ? "es" : "brandon sanderson",
            label: criterion === "LANGUAGE" ? "es" : "Brandon Sanderson",
            counts: { DOWNLOADS: 1 },
          },
        ],
        meta: { page: 0, size: 100, totalItems: 1, totalPages: 1 },
      },
    });
  });
  await page.route("**/api/catalog/books?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
      },
    }),
  );
  await page.goto("/discover");
  const savedSelect = page.getByRole("textbox", {
    name: "Búsquedas guardadas",
    exact: true,
  });
  await savedSelect.click();
  await page.getByRole("option", { name: "Configurada", exact: true }).click();
  await expect(
    page.getByRole("textbox", { name: "Combinar criterios", exact: true }),
  ).toHaveValue("Cualquiera");
  const footer = page.locator(".catalog-search-footer").first();
  const clear = footer.getByRole("button", { name: "Limpiar", exact: true });
  const defaults = async () => {
    await expect(page).toHaveURL(/\/discover$/);
    await expect(
      page.getByRole("textbox", { name: "Basarse en", exact: true }),
    ).toHaveValue("Descargas");
    await expect(
      page.getByRole("textbox", { name: "Combinar criterios", exact: true }),
    ).toHaveValue("Todos");
    await expect(
      page
        .getByRole("rowgroup", { name: "Autores", exact: true })
        .locator(".criterion-selected"),
    ).toHaveText("1");
    await expect(
      page.getByRole("rowgroup", { name: "Idiomas", exact: true }),
    ).toHaveCount(0);
    for (const name of ["Descargas", "Calibre", "Trabajos pendientes"])
      await expectExclusion(page, name, true);
    await expect(savedSelect).toHaveValue("");
    await expect(
      page.getByRole("button", { name: "Gestionar", exact: true }),
    ).toBeDisabled();
    await expect(
      footer.getByRole("button", { name: "Buscar", exact: true }),
    ).toBeEnabled();
  };
  await clear.click();
  await defaults();
  const advanced = page.locator(".catalog-search");
  if (
    !(await advanced.evaluate(
      (element) => (element as HTMLDetailsElement).open,
    ))
  )
    await advanced.locator("summary").click();
  await expect(
    advanced.getByRole("textbox", { name: "Título", exact: true }),
  ).toHaveValue("");
  await page.getByRole("textbox", { name: "Basarse en", exact: true }).click();
  await page.getByRole("option", { name: "Calibre", exact: true }).click();
  await page
    .getByRole("textbox", { name: "Buscar coincidencias por", exact: true })
    .click();
  await page.getByRole("option", { name: "Idiomas", exact: true }).click();
  await page.getByRole("heading", { name: "Descubrir", exact: true }).click();
  await page
    .getByRole("textbox", { name: "Combinar criterios", exact: true })
    .click();
  await page.getByRole("option", { name: "Cualquiera", exact: true }).click();
  await setExclusion(page, "Trabajos pendientes", false);
  await advanced
    .getByRole("textbox", { name: "Título", exact: true })
    .fill("Borrador sin aplicar");
  await clear.click();
  await defaults();
  await expect(
    advanced.getByRole("textbox", { name: "Título", exact: true }),
  ).toHaveValue("");
  await page.reload();
  await defaults();
  await savedSelect.click();
  await expect(
    page.getByRole("option", { name: "Configurada", exact: true }),
  ).toBeVisible();
  expect(mutations).toBe(0);
});

test("criteria picker stays fixed-height with checkbox options, summary, tooltip and compact rows", async ({
  page,
}) => {
  await page.route("**/api/catalog/discovery/values?**", (route) =>
    route.fulfill({
      json: {
        items: [{ value: "sample", label: "Sample", counts: { DOWNLOADS: 1 } }],
        meta: { page: 0, size: 100, totalItems: 1, totalPages: 1 },
      },
    }),
  );
  await page.goto("/discover");
  const picker = page.getByRole("textbox", {
    name: "Buscar coincidencias por",
    exact: true,
  });
  const row = page.locator(".discovery-filters");
  const initial = (await picker.boundingBox())!;
  const initialRow = (await row.boundingBox())!;
  await picker.click();
  for (const name of [
    "Colecciones",
    "Géneros",
    "Idiomas",
    "Año de publicación",
  ]) {
    const option = page.getByRole("option", { name, exact: true });
    await option.click();
    await expect(option).toHaveAttribute("aria-selected", "true");
    await expect(option.locator("input[type=checkbox]")).toBeChecked();
  }
  await expect(picker).toHaveValue("Autores, Colecciones +3");
  expect((await picker.boundingBox())!.height).toBe(initial.height);
  expect((await row.boundingBox())!.height).toBe(initialRow.height);
  await page.getByRole("heading", { name: "Descubrir", exact: true }).click();
  await picker.hover();
  await expect(page.getByRole("tooltip")).toHaveText(
    "Autores, Colecciones, Géneros, Idiomas, Año de publicación",
  );
  await page.getByRole("heading", { name: "Descubrir", exact: true }).hover();
  const author = page.getByRole("rowgroup", { name: "Autores", exact: true });
  const header = author.locator(".discovery-criterion-row");
  await expect(header.locator(".criterion-count button")).toHaveText("1");
  const title = (await header.getByRole("rowheader").boundingBox())!;
  const count = (await header.locator(".criterion-count").boundingBox())!;
  expect(
    Math.abs(title.y + title.height / 2 - count.y - count.height / 2),
  ).toBeLessThan(2);
  expect(
    Math.abs(
      (await page
        .getByRole("rowgroup", { name: "Año de publicación", exact: true })
        .getByRole("rowheader")
        .boundingBox())!.height - title.height,
    ),
  ).toBeLessThan(1);
  await page.screenshot({
    path: "/tmp/eplsync-discovery-compact-criteria.png",
    fullPage: true,
  });
  await picker.focus();
  await picker.press("ArrowDown");
  await expect(page.getByRole("listbox")).toBeVisible();
  await picker.press("Escape");
  await expect(page.getByRole("listbox")).toHaveCount(0);
  await page
    .getByRole("button", {
      name: "Limpiar: Buscar coincidencias por",
      exact: true,
    })
    .click();
  await expect(picker).toHaveValue("");
  await expect(picker).toHaveAttribute("placeholder", "Selecciona criterios");
  await expect(
    page.getByRole("button", { name: "Buscar", exact: true }).first(),
  ).toBeDisabled();
  await picker.focus();
  await picker.press("Space");
  await page.getByRole("option", { name: "Idiomas", exact: true }).click();
  await expect(picker).toHaveValue("Idiomas");
  await page.getByRole("heading", { name: "Descubrir", exact: true }).click();
  await page.screenshot({
    path: "/tmp/eplsync-discovery-fixed-picker.png",
    fullPage: true,
  });
  await page
    .getByRole("button", { name: "Plegar menú lateral", exact: true })
    .click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator(".sidebar")).toHaveAttribute("inert", "");
  expect(
    await page.evaluate(() => document.body.scrollWidth <= window.innerWidth),
  ).toBe(true);
  await expect(picker).toBeVisible();
  await page.screenshot({
    path: "/tmp/eplsync-discovery-fixed-picker-mobile.png",
    animations: "disabled",
    fullPage: true,
  });
});

test("criteria picker arrows open the menu and show a pointer with or without selected values", async ({
  page,
}) => {
  await page.route("**/api/catalog/discovery/values?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 100, totalItems: 0, totalPages: 0 },
      },
    }),
  );
  await page.goto("/discover");
  const picker = page.getByRole("textbox", {
    name: "Buscar coincidencias por",
    exact: true,
  });
  const clickArrows = async () => {
    const box = (await picker.boundingBox())!;
    const reference = (await page
      .getByRole("textbox", { name: "Basarse en", exact: true })
      .boundingBox())!;
    const referenceArrow = (await page
      .locator(".discovery-filters .mantine-Select-root")
      .first()
      .locator("[data-combobox-chevron]")
      .boundingBox())!;
    const arrow = (await page
      .locator(".discovery-criteria-input .discovery-picker-chevron")
      .boundingBox())!;
    const expected =
      reference.x +
      reference.width -
      referenceArrow.x -
      referenceArrow.width / 2;
    expect(
      Math.abs(box.x + box.width - arrow.x - arrow.width / 2 - expected),
    ).toBeLessThan(0.5);

    expect(
      await page.evaluate(
        ({ x, y }) => getComputedStyle(document.elementFromPoint(x, y)!).cursor,
        { x: box.x + box.width - 18, y: box.y + box.height / 2 },
      ),
    ).toBe("pointer");
    await picker.click({ position: { x: box.width - 18, y: box.height / 2 } });
  };
  await clickArrows();
  await expect(page.getByRole("listbox")).toBeVisible();
  await clickArrows();
  await expect(page.getByRole("listbox")).toHaveCount(0);
  await page
    .getByRole("button", {
      name: "Limpiar: Buscar coincidencias por",
      exact: true,
    })
    .click();
  await expect(picker).toHaveValue("");
  await expect(page.getByRole("listbox")).toHaveCount(0);
  await clickArrows();
  await expect(
    page.getByRole("option", { name: "Autores", exact: true }),
  ).toBeVisible();
  await page.getByRole("option", { name: "Idiomas", exact: true }).click();
  await expect(picker).toHaveValue("Idiomas");
});

test("criteria table aligns its columns and expands manual selections across the full row on mobile", async ({
  page,
}) => {
  const requests: URLSearchParams[] = [];
  await page.route("**/api/catalog/discovery/values?**", (route) => {
    const criterion =
      new URL(route.request().url()).searchParams.get("criterion") ?? "AUTHOR";
    const values: Record<string, [string, string][]> = {
      AUTHOR: [
        ["brandon sanderson", "Brandon Sanderson"],
        ["terry pratchett", "Terry Pratchett"],
      ],
      COLLECTION: [
        ["saga", "Saga"],
        ["saga 2", "Saga 2"],
      ],
      GENRE: [
        ["fantasía", "Fantasía"],
        ["aventura", "Aventura"],
      ],
      LANGUAGE: [
        ["es", "es"],
        ["en", "en"],
      ],
      YEAR: [
        ["2000", "2000"],
        ["2010", "2010"],
      ],
    };
    return route.fulfill({
      json: {
        items: values[criterion].map(([value, label]) => ({
          value,
          label,
          counts: { DOWNLOADS: 1 },
        })),
        meta: { page: 0, size: 100, totalItems: 2, totalPages: 1 },
      },
    });
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
  await page.goto("/discover");
  await page
    .getByRole("textbox", { name: "Buscar coincidencias por", exact: true })
    .click();
  for (const name of [
    "Colecciones",
    "Géneros",
    "Idiomas",
    "Año de publicación",
  ])
    await page.getByRole("option", { name, exact: true }).click();
  await page.getByRole("heading", { name: "Descubrir", exact: true }).click();
  const table = page.getByRole("table", { name: "Coincidencias", exact: true });

  await expect(table.getByRole("columnheader")).toHaveCount(3);
  await expect(
    table.getByRole("columnheader", {
      name: "Usar todos los valores",
      exact: true,
    }),
  ).toHaveCount(0);
  await chooseDetected(page, "Autores", "Brandon Sanderson");
  await chooseDetected(page, "Año de publicación", "2010");
  const author = table.getByRole("rowgroup", { name: "Autores", exact: true });
  await expect(author.locator(".criterion-selected")).toHaveText("1");
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    expect(
      await page.evaluate(() => document.body.scrollWidth <= innerWidth),
    ).toBe(true);
    await expect(
      table.getByRole("columnheader", { name: "Disponibles", exact: true }),
    ).toBeVisible();
  }
  await page
    .getByRole("button", { name: "Buscar", exact: true })
    .first()
    .click();
  await expect
    .poll(() => requests.at(-1)?.getAll("discoveryValues"))
    .toEqual(["AUTHOR:brandon sanderson", "YEAR:2010"]);
  await page.screenshot({
    path: "/tmp/eplsync-criteria-table-mobile-small.png",
    fullPage: true,
  });
});

test("exclusions share the Advanced Search column widths and clearing them preserves Present filters", async ({
  page,
}) => {
  const requests: URLSearchParams[] = [];
  await page.route("**/api/catalog/discovery/values?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 100, totalItems: 0, totalPages: 0 },
      },
    }),
  );
  await page.route("**/api/catalog/books?**", (route) => {
    requests.push(new URL(route.request().url()).searchParams);
    return route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
      },
    });
  });
  await page.goto("/discover");
  const exclusions = page.getByRole("textbox", {
    name: "Excluir presentes en",
    exact: true,
  });
  await expect(exclusions).toHaveValue("Descargas, Calibre +1");
  await exclusions.hover();
  await expect(page.getByRole("tooltip")).toHaveText(
    "Descargas, Calibre, Trabajos pendientes",
  );
  await page.getByRole("heading", { name: "Descubrir", exact: true }).hover();
  const advanced = page.locator(".catalog-search");
  await advanced.locator("summary").click();
  const primary = page.locator(".discovery-filters > div");
  const advancedFields = advanced.locator(".catalog-filter-grid > div");
  await expect(primary).toHaveCount(4);
  for (const width of [2560, 1440, 900, 390]) {
    await page.setViewportSize({ width, height: 1000 });
    if (width === 390)
      await expect(page.locator(".sidebar")).toHaveAttribute("inert", "");
    if (width === 2560)
      await expect(exclusions).toHaveValue(
        "Descargas, Calibre, Trabajos pendientes",
      );
    if (width === 1440)
      await expect(exclusions).toHaveValue("Descargas, Calibre +1");
    for (let index = 0; index < 4; index++) {
      const box = (await primary.nth(index).boundingBox())!,
        reference = (await advancedFields.nth(index).boundingBox())!;
      expect(Math.abs(box.width - reference.width)).toBeLessThan(1);
      expect(Math.abs(box.x - reference.x)).toBeLessThan(1);
    }
    const first = (await primary.nth(0).boundingBox())!,
      second = (await primary.nth(1).boundingBox())!;
    const frame = (await page
      .locator(".discovery-criteria-frame")
      .boundingBox())!;
    const expectedWidth =
      Math.abs(first.y - second.y) < 1
        ? second.x + second.width - first.x
        : first.width;
    expect(Math.abs(frame.width - expectedWidth)).toBeLessThan(1);
    expect(Math.abs(frame.x - first.x)).toBeLessThan(1);
    expect(
      await page.evaluate(() => document.body.scrollWidth <= window.innerWidth),
    ).toBe(true);
  }
  await page
    .getByRole("button", { name: "Limpiar: Excluir presentes en", exact: true })
    .click();
  await expect(exclusions).toHaveValue("");
  await expect(exclusions).toHaveAttribute("placeholder", "Sin exclusiones");
  const torrent = page
    .locator(".catalog-library-filter")
    .filter({ has: page.locator("legend", { hasText: "Torrent" }) });
  const calibre = page
    .locator(".catalog-library-filter")
    .filter({ has: page.locator("legend", { hasText: "Calibre" }) });
  await expect(
    torrent.getByRole("textbox", { name: "Presencia", exact: true }),
  ).toHaveValue("");
  await expect(
    calibre.getByRole("textbox", { name: "Presencia", exact: true }),
  ).toHaveValue("");
  await torrent
    .getByRole("textbox", { name: "Presencia", exact: true })
    .click();
  await page.getByRole("option", { name: "Presentes", exact: true }).click();
  await setExclusion(page, "Trabajos pendientes", true);
  await page
    .getByRole("button", { name: "Limpiar: Excluir presentes en", exact: true })
    .click();
  await expect(
    torrent.getByRole("textbox", { name: "Presencia", exact: true }),
  ).toHaveValue("Presentes");
  await page
    .getByRole("button", { name: "Buscar", exact: true })
    .first()
    .click();
  await expect
    .poll(() => requests.at(-1)?.get("torrentPresence"))
    .toBe("PRESENT");
  expect(requests.at(-1)?.get("calibrePresence")).toBeNull();
  expect(requests.at(-1)?.get("discoveryExcludePending")).toBe("false");
  await expect(exclusions).toHaveValue("");
  await page.screenshot({
    path: "/tmp/eplsync-exclusions-mobile.png",
    animations: "disabled",
    fullPage: true,
  });
  await page.setViewportSize({ width: 2560, height: 1000 });
  await page.screenshot({
    path: "/tmp/eplsync-exclusions-wide.png",
    animations: "disabled",
    fullPage: true,
  });
});

test("exclusion choices respect source permissions", async ({ page }) => {
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      json: {
        id: "calibre-reader",
        username: "reader",
        role: "USER",
        permissions: ["CATALOG_READ", "CALIBRE_READ"],
        mustChangePassword: false,
      },
    }),
  );
  await page.route("**/api/catalog/discovery/values?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 100, totalItems: 0, totalPages: 0 },
      },
    }),
  );
  await page.goto("/discover");
  const exclusions = page.getByRole("textbox", {
    name: "Excluir presentes en",
    exact: true,
  });
  await expect(exclusions).toHaveValue("Calibre, Trabajos pendientes");
  await exclusions.click();
  const downloads = page.getByRole("option", {
    name: "Descargas",
    exact: true,
  });
  await expect(downloads.locator("input")).toBeDisabled();
  await expect(downloads).toHaveAttribute("aria-selected", "false");
  await expect(
    page.getByRole("option", { name: "Calibre", exact: true }),
  ).toHaveAttribute("aria-selected", "true");
});

test("criteria keep dropdown order after selection and URL restoration", async ({
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
  await page.route("**/api/catalog/discovery/values?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 100, totalItems: 0, totalPages: 0 },
      },
    }),
  );
  await page.goto(
    "/discover?discoverySource=DOWNLOADS&discoveryCriteria=COLLECTION&discoveryCriteria=AUTHOR",
  );
  const picker = page.getByRole("textbox", {
    name: "Buscar coincidencias por",
    exact: true,
  });
  await expect(picker).toHaveValue("Autores, Colecciones");
  const rows = page.locator(".discovery-criteria-table tbody");
  await expect(rows.first()).toHaveAttribute("aria-label", "Autores");
  await picker.click();
  await page.getByRole("option", { name: "Autores", exact: true }).click();
  await page
    .getByRole("option", { name: "Año de publicación", exact: true })
    .click();
  await page.getByRole("option", { name: "Autores", exact: true }).click();
  await page.getByRole("option", { name: "Géneros", exact: true }).click();
  await page.getByRole("heading", { name: "Descubrir", exact: true }).click();
  await expect(rows).toHaveCount(4);
  expect(
    await rows.evaluateAll((elements) =>
      elements.map((element) => element.getAttribute("aria-label")),
    ),
  ).toEqual(["Autores", "Colecciones", "Géneros", "Año de publicación"]);
});

test("Discover sync updates sources independently without rerunning the book search", async ({
  page,
}) => {
  let torrentCalls = 0,
    calibreCalls = 0,
    bookCalls = 0,
    suggestionCalls = 0;
  let synced = false;
  await page.route("**/api/catalog/discovery/values?**", (route) => {
    suggestionCalls++;
    return route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 100, totalItems: 0, totalPages: 0 },
      },
    });
  });
  await page.route("**/api/catalog/books?**", (route) => {
    bookCalls++;
    return route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
      },
    });
  });
  await page.route("**/api/torrent/downloads/sync-state", (route) =>
    route.fulfill({
      json: { syncedAt: synced ? "2026-11-23T10:00:00Z" : null },
    }),
  );
  await page.route("**/api/torrent/downloads/sync", async (route) => {
    torrentCalls++;
    await new Promise((resolve) => setTimeout(resolve, 250));
    synced = true;
    return route.fulfill({ json: { applied: true } });
  });
  await page.route("**/api/settings/calibre/sync", async (route) => {
    calibreCalls++;
    await new Promise((resolve) => setTimeout(resolve, 250));
    return calibreCalls === 1
      ? route.fulfill({
          status: 503,
          json: { details: "Calibre no disponible" },
        })
      : route.fulfill({
          json: { syncedAt: "2026-11-23T11:00:00Z", complete: true },
        });
  });
  await page.goto(
    "/discover?discoverySource=DOWNLOADS&discoveryCriteria=AUTHOR",
  );
  await expect.poll(() => suggestionCalls).toBeGreaterThan(0);
  await expect.poll(() => bookCalls).toBeGreaterThan(0);
  const initialBooks = bookCalls,
    initialSuggestions = suggestionCalls;
  const button = page.getByRole("button", {
    name: "Sincronizar todo",
    exact: true,
  });
  await button.click();
  await expect(button).toBeDisabled();
  await expect(
    page.getByText("Calibre no disponible", { exact: true }),
  ).toBeVisible();
  await expect(button).toBeEnabled();
  await expect.poll(() => suggestionCalls).toBeGreaterThan(initialSuggestions);
  await expect(
    page.getByText(/Última sincronización de Descargas:.*23 nov 2026/),
  ).toBeVisible();
  await expect(
    page.getByText(/Última sincronización de Calibre: Sin sincronizar/),
  ).toBeVisible();
  expect(torrentCalls).toBe(1);
  expect(calibreCalls).toBe(1);
  expect(bookCalls).toBe(initialBooks);
  await expect(page.getByText(/Fecha del catálogo/)).toHaveCount(0);
  await page.getByRole("button", { name: "Reintentar", exact: true }).click();
  await expect(
    page.getByText(/Última sincronización de Calibre:.*23 nov 2026/),
  ).toBeVisible();
  await expect(
    page.getByText("Calibre no disponible", { exact: true }),
  ).toHaveCount(0);
  expect(calibreCalls).toBe(2);
  expect(torrentCalls).toBe(1);
  expect(bookCalls).toBe(initialBooks);
  await page.screenshot({
    path: "/tmp/eplsync-discovery-sync.png",
    fullPage: true,
  });
});

test("Discover sync respects read-only Calibre permissions", async ({
  page,
}) => {
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      json: {
        id: "reader-sync",
        username: "reader",
        role: "USER",
        permissions: ["CATALOG_READ", "CALIBRE_READ"],
        mustChangePassword: false,
      },
    }),
  );
  await page.route("**/api/catalog/discovery/values?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 100, totalItems: 0, totalPages: 0 },
      },
    }),
  );
  await page.goto("/discover");
  await expect(
    page.getByText(/Última sincronización de Calibre:/),
  ).toBeVisible();
  await expect(
    page.getByText(/Última sincronización de Descargas:/),
  ).toHaveCount(0);
  await expect(page.getByRole("button", { name: /Sincronizar/ })).toHaveCount(
    0,
  );
});

test("author detection exclusions update counts and survive saved searches, reload and reset", async ({
  page,
}) => {
  const requests: URLSearchParams[] = [];
  let saved: { id: string; name: string; filters: Record<string, string[]> }[] =
    [];
  await page.route("**/api/catalog/discovery/values?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    const count = params.has("maxAuthors")
      ? 1
      : params.get("ignoreAnthologies") === "true"
        ? 2
        : 4;
    return route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 100, totalItems: count, totalPages: 1 },
      },
    });
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
  await page.route("**/api/catalog/saved-searches**", (route) => {
    if (route.request().method() === "GET")
      return route.fulfill({ json: saved });
    const input = route.request().postDataJSON();
    saved = [{ ...input, id: "author-exclusions" }];
    return route.fulfill({ json: saved[0] });
  });
  await page.goto("/discover");
  const row = page.getByRole("rowgroup", { name: "Autores", exact: true });
  const aa = page.getByRole("checkbox", {
    name: "Ignorar libros de «AA. VV.» al detectar autores",
    exact: true,
  });
  const limit = page.getByRole("checkbox", {
    name: "Ignorar libros con más de",
    exact: true,
  });
  await expect(aa).not.toBeChecked();
  await expect(limit).not.toBeChecked();
  await expect(row.locator(".criterion-count")).toHaveText("4");
  await aa.check();
  await expect(row.locator(".criterion-count")).toHaveText("2");
  await limit.check();
  const max = page.getByRole("textbox", {
    name: "Máximo de autores por libro",
    exact: true,
  });
  await max.fill("2");
  await expect(row.locator(".criterion-count")).toHaveText("1");
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
  expect(saved[0].filters.discoveryIgnoreAnthologies).toEqual(["true"]);
  expect(saved[0].filters.discoveryMaxAuthors).toEqual(["2"]);
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
  await expect
    .poll(() => requests.at(-1)?.get("discoveryMaxAuthors"))
    .toBe("2");
  expect(requests.at(-1)?.get("discoveryIgnoreAnthologies")).toBe("true");
  await expect(
    page.getByText("Hay cambios sin aplicar", { exact: true }),
  ).toHaveCount(0);
  await page.reload();
  await expect(aa).toBeChecked();
  await expect(max).toHaveValue("2");
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(aa).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "/tmp/eplsync-author-options-mobile.png",
    fullPage: true,
  });
  await page
    .getByRole("button", { name: "Limpiar", exact: true })
    .first()
    .click();
  await expect(aa).not.toBeChecked();
  await expect(limit).not.toBeChecked();
});

test("detected authors can be inspected, searched and paged without changing the search", async ({
  page,
}) => {
  const requests: URLSearchParams[] = [];
  let bookCalls = 0;
  await page.route("**/api/catalog/books?**", (route) => {
    bookCalls++;
    return route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
      },
    });
  });
  await page.route("**/api/catalog/discovery/values?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    requests.push(params);
    const filtered = params.get("q") === "King";
    return route.fulfill({
      json: {
        items: [
          {
            value: "author",
            label: filtered
              ? "Stephen King"
              : params.get("page") === "1"
                ? "Autor página 2"
                : "Autor inicial",
            counts: { DOWNLOADS: 2, CALIBRE: 1 },
          },
        ],
        meta: {
          page: Number(params.get("page") ?? 0),
          size: 100,
          totalItems: filtered ? 1 : 201,
          totalPages: filtered ? 1 : 3,
        },
      },
    });
  });
  await page.goto(
    "/discover?discoverySource=BOTH&discoveryCriteria=AUTHOR&discoveryIgnoreAnthologies=true&discoveryMaxAuthors=3",
  );
  await page
    .getByRole("button", { name: "Ver autores detectados (201)", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByText("Autor inicial", { exact: true }),
  ).toBeVisible();
  await expect(
    dialog.getByRole("columnheader", { name: "Descargas", exact: true }),
  ).toBeVisible();
  const navigation = dialog.getByRole("navigation", {
    name: "Páginas de valores detectados",
    exact: true,
  });
  await expect(navigation.getByText("1 / 3", { exact: true })).toBeVisible();
  await expect(
    navigation.getByRole("button", { name: "Anterior", exact: true }),
  ).toBeDisabled();
  await dialog.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect(navigation.getByText("2 / 3", { exact: true })).toBeVisible();
  await expect(
    dialog.getByText("Autor página 2", { exact: true }),
  ).toBeVisible();
  await dialog
    .getByRole("textbox", { name: "Buscar autor", exact: true })
    .fill("King");
  await expect(dialog.getByText("Stephen King", { exact: true })).toBeVisible();
  expect(requests.at(-1)?.get("source")).toBe("BOTH");
  expect(requests.at(-1)?.get("ignoreAnthologies")).toBe("true");
  expect(requests.at(-1)?.get("maxAuthors")).toBe("3");
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(dialog.getByText("Stephen King", { exact: true })).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "/tmp/eplsync-detected-authors-mobile.png",
    fullPage: true,
  });
  const searches = bookCalls;
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  expect(bookCalls).toBe(searches);
});

test("author exclusions explain overlaps and unapplied changes include advanced filters", async ({
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
  await page.route("**/api/catalog/discovery/values?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 100, totalItems: 4, totalPages: 1 },
      },
    }),
  );
  await page.route("**/api/catalog/discovery/authors/summary?**", (route) =>
    route.fulfill({
      json: {
        totalBooks: 10,
        ignoredBooks: 4,
        ignoredAnthologies: 3,
        ignoredTooMany: 2,
        ignoredByBoth: 1,
      },
    }),
  );
  await page.goto("/discover");
  const warning = page.getByText("Hay cambios sin aplicar", { exact: true });
  await expect(warning).toHaveCount(0);
  await page
    .getByRole("button", { name: "Buscar", exact: true })
    .first()
    .click();
  await expect(page).toHaveURL(/discoverySource=/);
  await expect(warning).toHaveCount(0);
  const aa = page.getByRole("checkbox", {
    name: "Ignorar libros de «AA. VV.» al detectar autores",
    exact: true,
  });
  await aa.check();
  await expect(warning).toBeVisible();
  await aa.uncheck();
  await expect(warning).toHaveCount(0);
  await aa.check();
  await page
    .getByRole("checkbox", { name: "Ignorar libros con más de", exact: true })
    .check();
  await expect(
    page
      .locator(".discovery-ignore-summary")
      .getByText("4 / 10", { exact: true }),
  ).toBeVisible();
  await expect(
    page
      .locator(".discovery-ignore-summary")
      .getByText("AA. VV.", { exact: true }),
  ).toBeVisible();
  await expect(
    page
      .locator(".discovery-ignore-summary")
      .getByText("Más de 5 autores", { exact: true }),
  ).toBeVisible();
  await expect(page.getByText(/Coinciden ambas exclusiones/)).toHaveCount(0);
  await page
    .getByRole("button", { name: "Buscar", exact: true })
    .first()
    .click();
  await expect(warning).toHaveCount(0);
  await page.locator(".catalog-search summary").click();
  const title = page.getByRole("textbox", { name: "Título", exact: true });
  await title.fill("Prueba");
  await expect(warning).toBeVisible();
  await title.fill("");
  await expect(warning).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});

for (const [criterion, title, field, first, second, found, search] of [
  [
    "COLLECTION",
    "Colecciones detectadas",
    "Buscar colección",
    "Colección inicial",
    "Colección siguiente",
    "Colección buscada",
    "buscada",
  ],
  [
    "GENRE",
    "Géneros detectados",
    "Buscar género",
    "Género inicial",
    "Género siguiente",
    "Aventura",
    "aventura",
  ],
  [
    "YEAR",
    "Años de publicación detectados",
    "Buscar año de publicación",
    "1990",
    "2000",
    "2010",
    "2010",
  ],
  [
    "LANGUAGE",
    "Idiomas detectados",
    "Buscar idioma",
    "Español",
    "Inglés",
    "Español",
    "español",
  ],
]) {
  test(`detected ${criterion} supports browsing, search and origins without author exclusions`, async ({
    page,
  }) => {
    const requests: URLSearchParams[] = [];
    let bookCalls = 0;
    await page.route("**/api/catalog/books?**", (route) => {
      bookCalls++;
      return route.fulfill({
        json: {
          items: [],
          meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
        },
      });
    });
    await page.route("**/api/catalog/discovery/values?**", (route) => {
      const params = new URL(route.request().url()).searchParams;
      requests.push(params);
      const filtered = Boolean(params.get("q"));
      return route.fulfill({
        json: {
          items:
            criterion === "LANGUAGE"
              ? [
                  {
                    value: "es",
                    label: "es",
                    counts: { DOWNLOADS: 3, CALIBRE: 1 },
                  },
                  { value: "en", label: "en", counts: { CALIBRE: 1 } },
                ]
              : [
                  {
                    value: "value",
                    label: filtered
                      ? found
                      : params.get("page") === "1"
                        ? second
                        : first,
                    counts: { DOWNLOADS: 3, CALIBRE: 1 },
                  },
                ],
          meta: {
            page: Number(params.get("page") ?? 0),
            size: 100,
            totalItems: criterion === "LANGUAGE" ? 2 : filtered ? 1 : 201,
            totalPages: criterion === "LANGUAGE" || filtered ? 1 : 3,
          },
        },
      });
    });
    await page.goto(
      `/discover?discoverySource=BOTH&discoveryCriteria=${criterion}&discoveryIgnoreAnthologies=true&discoveryMaxAuthors=3`,
    );
    const row = page
      .getByRole("rowgroup")
      .filter({ has: page.locator(".criterion-count") });
    await row.getByRole("button").click();
    const dialog = page.getByRole("dialog");
    await expect(
      dialog.getByRole("heading", { name: title, exact: true }),
    ).toBeVisible();
    await expect(dialog.getByText(first, { exact: true })).toBeVisible();
    await expect(
      dialog.getByRole("columnheader", { name: "Descargas", exact: true }),
    ).toBeVisible();
    if (criterion !== "LANGUAGE") {
      await dialog
        .getByRole("button", { name: "Siguiente", exact: true })
        .click();
      await expect(dialog.getByText(second, { exact: true })).toBeVisible();
    }
    await dialog
      .getByRole("textbox", { name: field, exact: true })
      .fill(search);
    await expect(dialog.getByText(found, { exact: true })).toBeVisible();
    await expect(
      dialog.getByText("Valores encontrados: 1", { exact: true }),
    ).toBeVisible();
    if (criterion === "LANGUAGE") {
      await expect(dialog.getByText("Inglés", { exact: true })).toHaveCount(0);
      await dialog
        .getByRole("textbox", { name: field, exact: true })
        .fill("en");
      await expect(dialog.getByText("Inglés", { exact: true })).toBeVisible();
      await dialog
        .getByRole("textbox", { name: field, exact: true })
        .fill("zzzz");
      await expect(
        dialog.getByText("No hay valores para mostrar.", { exact: true }),
      ).toBeVisible();
    }
    expect(requests.at(-1)?.get("criterion")).toBe(criterion);
    expect(requests.at(-1)?.get("source")).toBe("BOTH");
    expect(requests.at(-1)?.has("ignoreAnthologies")).toBe(false);
    expect(requests.at(-1)?.has("maxAuthors")).toBe(false);
    const before = bookCalls;
    await page.keyboard.press("Escape");
    await expect(dialog).toBeHidden();
    expect(bookCalls).toBe(before);
    await expect(
      page.getByText("Hay cambios sin aplicar", { exact: true }),
    ).toHaveCount(0);
  });
}

test("checkbox selections preserve all-except across pages, filtering, saved searches and empty selection", async ({
  page,
}) => {
  type Entry = { id: string; name: string; filters: Record<string, string[]> };
  let saved: Entry[] = [];
  const searches: URLSearchParams[] = [];
  await page.route("**/api/catalog/saved-searches**", (route) => {
    if (route.request().method() === "GET")
      return route.fulfill({ json: saved });
    saved = [{ ...route.request().postDataJSON(), id: "except-authors" }];
    return route.fulfill({ json: saved[0] });
  });
  await page.route("**/api/catalog/books?**", (route) => {
    searches.push(new URL(route.request().url()).searchParams);
    return route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
      },
    });
  });
  await page.route("**/api/catalog/discovery/values?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    const filtered = params.get("q") === "Autor 100";
    const start = filtered ? 100 : Number(params.get("page") ?? 0) * 100;
    const ids = filtered ? [start] : [start, start + 1];
    return route.fulfill({
      json: {
        items: ids.map((id) => ({
          value: `autor ${id}`,
          label: `Autor ${id}`,
          counts: { DOWNLOADS: 1 },
        })),
        meta: {
          page: Number(params.get("page") ?? 0),
          size: 100,
          totalItems: filtered ? 1 : 1432,
          totalPages: filtered ? 1 : 15,
        },
      },
    });
  });
  await page.goto(
    "/discover?discoverySource=DOWNLOADS&discoveryCriteria=AUTHOR",
  );
  const main = page.getByRole("table", { name: "Coincidencias", exact: true });
  await expect(main.getByRole("columnheader")).toHaveCount(3);
  const warning = page.getByText("Hay cambios sin aplicar", { exact: true });
  let dialog = await openDetected(page, "Autores");
  await expect(
    dialog.getByRole("checkbox", { name: "Seleccionar Autor 0", exact: true }),
  ).toBeChecked();
  await dialog
    .getByRole("checkbox", { name: "Seleccionar Autor 0", exact: true })
    .uncheck();
  await expect(
    dialog.getByRole("checkbox", {
      name: "Seleccionar esta página",
      exact: true,
    }),
  ).toHaveAttribute("data-indeterminate", "true");
  await dialog.getByRole("button", { name: "Cancelar", exact: true }).click();
  await expect(warning).toHaveCount(0);
  dialog = await openDetected(page, "Autores");
  await expect(
    dialog.getByRole("checkbox", { name: "Seleccionar Autor 0", exact: true }),
  ).toBeChecked();
  await dialog
    .getByRole("checkbox", { name: "Seleccionar Autor 0", exact: true })
    .uncheck();
  await dialog.getByRole("button", { name: "Siguiente", exact: true }).click();
  await dialog
    .getByRole("checkbox", { name: "Seleccionar Autor 100", exact: true })
    .uncheck();
  await dialog
    .getByRole("textbox", { name: "Buscar autor", exact: true })
    .fill("Autor 100");
  await expect(
    dialog.getByText("Valores encontrados: 1", { exact: true }),
  ).toBeVisible();
  await expect(
    dialog.getByRole("checkbox", {
      name: "Seleccionar Autor 100",
      exact: true,
    }),
  ).not.toBeChecked();
  await dialog
    .getByRole("button", { name: "Aplicar selección", exact: true })
    .click();
  await expect(main.locator(".criterion-selected")).toHaveText("1430");
  await expect(warning).toBeVisible();
  await page
    .getByRole("button", { name: "Guardar búsqueda", exact: true })
    .click();
  dialog = page.getByRole("dialog");
  await dialog
    .getByRole("textbox", { name: "Nombre", exact: true })
    .fill("Excepto dos");
  await dialog
    .getByRole("button", { name: "Guardar búsqueda", exact: true })
    .click();
  await expect(dialog).toBeHidden();
  expect(saved[0].filters.discoveryExcludedValues).toEqual([
    "AUTHOR:autor 0",
    "AUTHOR:autor 100",
  ]);
  expect(saved[0].filters.discoveryValues).toBeUndefined();
  await page
    .getByRole("button", { name: "Buscar", exact: true })
    .first()
    .click();
  await expect
    .poll(() => searches.at(-1)?.getAll("discoveryExcludedValues"))
    .toEqual(["AUTHOR:autor 0", "AUTHOR:autor 100"]);
  await page.reload();
  dialog = await openDetected(page, "Autores");
  await expect(
    dialog.getByRole("checkbox", { name: "Seleccionar Autor 0", exact: true }),
  ).not.toBeChecked();
  await dialog
    .getByRole("button", { name: "Seleccionar todos (1432)", exact: true })
    .click();
  await dialog
    .getByRole("button", { name: "Aplicar selección", exact: true })
    .click();
  await page
    .getByRole("textbox", { name: "Búsquedas guardadas", exact: true })
    .click();
  await page.getByRole("option", { name: "Excepto dos", exact: true }).click();
  dialog = await openDetected(page, "Autores");
  await expect(
    dialog.getByRole("checkbox", { name: "Seleccionar Autor 0", exact: true }),
  ).not.toBeChecked();
  await dialog.getByRole("button", { name: /^Quitar selección \(/ }).click();
  await dialog
    .getByRole("button", { name: "Aplicar selección", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Buscar", exact: true })
    .first()
    .click();
  await expect
    .poll(() => searches.at(-1)?.getAll("discoveryValues"))
    .toEqual(["AUTHOR:"]);
  expect(searches.at(-1)?.getAll("discoveryExcludedValues")).toEqual([]);
});

test("selected totals use detected values and ignoring options share a compact group", async ({
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
  await page.route("**/api/catalog/discovery/values?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    const items = [0, 1].map((id) => ({
      value: `autor ${id}`,
      label: `Autor ${id}`,
      counts: { DOWNLOADS: 1 },
    }));
    const all = params.get("allSelected") !== "false";
    const selected = items.filter((item) =>
      all
        ? !params.getAll("excluded").includes(item.value)
        : params.getAll("selected").includes(item.value),
    ).length;
    return route.fulfill({
      json: {
        items,
        selectedTotal: selected,
        meta: { page: 0, size: 100, totalItems: 2, totalPages: 1 },
      },
    });
  });
  await page.setViewportSize({ width: 2560, height: 1100 });
  await page.goto(
    "/discover?discoverySource=DOWNLOADS&discoveryCriteria=AUTHOR&discoveryIgnoreAnthologies=true&discoveryMaxAuthors=5&discoveryExcludedValues=AUTHOR:ausente",
  );
  const row = page.getByRole("rowgroup", { name: "Autores", exact: true });
  await expect(row.locator(".criterion-selected")).toHaveText("2");
  await expect(
    page.getByRole("columnheader", { name: "Seleccionados", exact: true }),
  ).toBeVisible();
  let dialog = await openDetected(page, "Autores");
  await expect(
    dialog.getByRole("button", { name: "Quitar selección (2)", exact: true }),
  ).toBeVisible();
  await dialog
    .getByRole("checkbox", { name: "Seleccionar Autor 0", exact: true })
    .uncheck();
  await expect(
    dialog.getByRole("button", { name: "Quitar selección (1)", exact: true }),
  ).toBeVisible();
  await dialog
    .getByRole("button", { name: "Aplicar selección", exact: true })
    .click();
  await expect(row.locator(".criterion-selected")).toHaveText("1");
  await chooseDetected(page, "Autores", "Autor 1");
  await expect(row.locator(".criterion-selected")).toHaveText("1");
  const group = page.getByRole("region", {
    name: "Detección de autores",
    exact: true,
  });
  await expect(
    group.getByText("Detección de autores", { exact: true }),
  ).toBeVisible();
  await expect(row.getByRole("checkbox")).toHaveCount(0);
  await expect(group.getByRole("checkbox")).toHaveCount(2);
  await expect(group.locator(".discovery-ignore-summary")).toBeVisible();
  const criteriaBox = (await page
    .locator(".discovery-criteria-frame")
    .boundingBox())!;
  const detectionBox = (await group.boundingBox())!;
  expect(Math.abs(detectionBox.width - criteriaBox.width)).toBeLessThan(2);
  expect(Math.abs(detectionBox.x - criteriaBox.x)).toBeLessThan(2);
  await expect(group.getByRole("progressbar")).toHaveCount(0);
  const summaryBox = (await group
    .locator(".discovery-ignore-summary")
    .boundingBox())!;
  const optionsBox = (await group.getByRole("checkbox").last().boundingBox())!;
  expect(summaryBox.y).toBeGreaterThan(optionsBox.y + optionsBox.height);
  await page.screenshot({
    path: "/tmp/eplsync-ignore-group-wide.png",
    fullPage: true,
  });
  await page
    .getByRole("button", { name: "Plegar menú lateral", exact: true })
    .click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator(".sidebar")).toHaveAttribute("inert", "");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "/tmp/eplsync-ignore-group-mobile.png",
    fullPage: true,
  });
});

test("selection counters update immediately without a request per checkbox", async ({
  page,
}) => {
  let requests = 0;
  await page.route("**/api/catalog/books?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
      },
    }),
  );
  await page.route("**/api/catalog/discovery/values?**", (route) => {
    requests++;
    return route.fulfill({
      json: {
        items: [0, 1].map((id) => ({
          value: `autor ${id}`,
          label: `Autor ${id}`,
          counts: { DOWNLOADS: 1 },
        })),
        selectedTotal: 2,
        meta: { page: 0, size: 100, totalItems: 2, totalPages: 1 },
      },
    });
  });
  await page.goto(
    "/discover?discoverySource=DOWNLOADS&discoveryCriteria=AUTHOR",
  );
  const dialog = await openDetected(page, "Autores");
  await expect(
    dialog.getByRole("checkbox", { name: "Seleccionar Autor 0", exact: true }),
  ).toBeChecked();
  const baseline = requests;
  await dialog
    .getByRole("checkbox", { name: "Seleccionar Autor 0", exact: true })
    .uncheck();
  await expect(
    dialog.getByRole("button", { name: "Quitar selección (1)", exact: true }),
  ).toBeVisible({ timeout: 0 });
  await expect(
    dialog.getByText("Seleccionados: 1", { exact: true }),
  ).toBeVisible({ timeout: 0 });
  await dialog
    .getByRole("checkbox", { name: "Seleccionar Autor 1", exact: true })
    .uncheck();
  await expect(
    dialog.getByRole("button", { name: "Quitar selección (0)", exact: true }),
  ).toBeVisible({ timeout: 0 });
  await dialog
    .getByRole("button", { name: "Seleccionar todos (2)", exact: true })
    .click();
  await expect(
    dialog.getByText("Seleccionados: 2", { exact: true }),
  ).toBeVisible({ timeout: 0 });
  await dialog
    .getByRole("checkbox", { name: "Seleccionar esta página", exact: true })
    .uncheck();
  await expect(
    dialog.getByText("Seleccionados: 0", { exact: true }),
  ).toBeVisible({ timeout: 0 });
  expect(requests).toBe(baseline);
  await expect(
    dialog.getByText("Seleccionados: …", { exact: true }),
  ).toHaveCount(0);
});

test("ignored summary stays visible with both detection exclusions off", async ({
  page,
}) => {
  await page.route("**/api/catalog/discovery/values?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        selectedTotal: 2,
        meta: { page: 0, size: 100, totalItems: 2, totalPages: 1 },
      },
    }),
  );
  await page.route("**/api/catalog/discovery/authors/summary?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    const active = params.get("ignoreAnthologies") === "true";
    return route.fulfill({
      json: {
        totalBooks: 4,
        ignoredBooks: active ? 1 : 0,
        ignoredAnthologies: active ? 1 : 0,
        ignoredTooMany: 0,
        ignoredByBoth: 0,
      },
    });
  });
  await page.goto("/discover");
  const group = page.getByRole("region", {
    name: "Detección de autores",
    exact: true,
  });
  const summary = group.locator(".discovery-ignore-summary");
  await expect(summary.getByText("0 / 4", { exact: true })).toBeVisible();
  await expect(summary.getByText("AA. VV.", { exact: true })).toBeVisible();
  await expect(
    summary.getByText("Más de 5 autores", { exact: true }),
  ).toBeVisible();
  const checkbox = group.getByRole("checkbox", {
    name: "Ignorar libros de «AA. VV.» al detectar autores",
    exact: true,
  });
  await checkbox.check();
  await expect(summary.getByText("1 / 4", { exact: true })).toBeVisible();
  await checkbox.uncheck();
  await expect(summary.getByText("0 / 4", { exact: true })).toBeVisible();
  await expect(
    summary.getByText("Más de 5 autores", { exact: true }),
  ).toBeVisible();
});

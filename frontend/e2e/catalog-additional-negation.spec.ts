import { test, expect } from "./fixtures";

test("catalog token negations survive saved search restoration, paging and all-result export", async ({
  page,
}) => {
  await page.addInitScript(() =>
    Object.defineProperty(window, "showSaveFilePicker", {
      value: undefined,
      configurable: true,
    }),
  );
  let stored: any[] = [];
  const queries: URLSearchParams[] = [];
  await page.route("**/api/catalog/suggestions/**", (r) =>
    r.fulfill({ json: { items: [], total: 0, nextOffset: null } }),
  );
  await page.route("**/api/catalog/books?**", (r) => {
    const params = new URL(r.request().url()).searchParams;
    queries.push(params);
    const index = Number(params.get("page") ?? 0);
    return r.fulfill({
      json: {
        items: [
          {
            eplId: 100 + index,
            title: `Book ${index}`,
            author: "Author",
            revision: 2,
            download: { items: [] },
          },
        ],
        meta: {
          page: index,
          size: 20,
          totalItems: 40,
          totalPages: 2,
          hasNext: index === 0,
          hasPrevious: index > 0,
        },
      },
    });
  });
  await page.route("**/api/catalog/saved-searches", (r) => {
    if (r.request().method() === "GET") return r.fulfill({ json: stored });
    stored = [{ ...r.request().postDataJSON(), id: "negated" }];
    return r.fulfill({ json: stored[0] });
  });
  let exported: any;
  await page.route("**/api/catalog/magnets/export", (r) => {
    exported = r.request().postDataJSON();
    return r.fulfill({
      contentType: "text/plain",
      body: "magnet:?xt=urn:btih:A\n",
    });
  });
  await page.goto("/catalog");
  await page.locator(".catalog-search summary").click();
  const ids = page.getByRole("textbox", { name: "EPL ID", exact: true });
  for (const [value, key] of [
    ["1", "Space"],
    ["2", "Tab"],
    ["3", "Enter"],
  ]) {
    await ids.fill(value);
    await ids.press(key);
    await expect(ids).toHaveValue("");
  }
  for (const [label, value] of [
    ["Colección", "Saga with spaces"],
    ["Género", "Drama"],
    ["Revisión", "1.4"],
  ]) {
    const input = page
      .getByRole("textbox", { name: label, exact: true })
      .first();
    await input.fill(value);
    await input.press("Enter");
  }
  for (const label of ["EPL ID", "Colección", "Género", "Revisión"]) {
    const wrapper = page
      .locator(".mantine-InputWrapper-root")
      .filter({ has: page.getByRole("textbox", { name: label, exact: true }) });
    await wrapper
      .getByRole("button", {
        name:
          label === "EPL ID" || label === "Revisión"
            ? "Incluir valores seleccionados"
            : "Contiene alguno de los textos indicados",
        exact: true,
      })
      .click();
  }
  await page
    .getByRole("button", { name: "Guardar búsqueda", exact: true })
    .click();
  const modal = page.getByRole("dialog");
  await modal.getByLabel("Nombre", { exact: true }).fill("Negated tokens");
  await modal
    .getByRole("button", { name: "Guardar búsqueda", exact: true })
    .click();
  await expect(modal).not.toBeVisible();
  const expected = {
    eplId: ["1", "2", "3"],
    eplIdOperator: ["ne"],
    collection: ["Saga with spaces"],
    collectionOperator: ["ne"],
    genres: ["Drama"],
    genresOperator: ["ne"],
    revision: ["1.4"],
    revisionOperator: ["ne"],
  };
  expect(stored[0].filters).toMatchObject(expected);
  await page.getByRole("button", { name: "Limpiar", exact: true }).click();
  if (
    !(await page
      .locator(".catalog-search")
      .evaluate((el) => (el as HTMLDetailsElement).open))
  )
    await page.locator(".catalog-search summary").click();
  await page
    .getByRole("textbox", { name: "Búsquedas guardadas", exact: true })
    .click();
  await page
    .getByRole("option", { name: "Negated tokens", exact: true })
    .click();
  await expect.poll(() => queries.at(-1)?.get("eplIdOperator")).toBe("ne");
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect.poll(() => queries.at(-1)?.get("page")).toBe("1");
  for (const [key, values] of Object.entries(expected))
    expect(queries.at(-1)?.getAll(key)).toEqual(values);
  await page
    .getByRole("button", { name: "Seleccionar todo (40)", exact: true })
    .click();
  await page
    .getByRole("checkbox", { name: "Seleccionar Book 1", exact: true })
    .uncheck();
  const download = page.waitForEvent("download");
  await page
    .getByRole("button", { name: "Exportar magnet links", exact: true })
    .click();
  await download;
  expect(exported.filters).toMatchObject({
    eplId: [1, 2, 3],
    eplIdOperator: "ne",
    collection: ["Saga with spaces"],
    collectionOperator: "ne",
    genres: ["Drama"],
    genresOperator: "ne",
    revision: [1.4],
    revisionOperator: "ne",
    excludedIds: [101],
  });
});

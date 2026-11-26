import { test, expect } from "./fixtures";

test("Calibre metadata export keeps exact selected entries, options and independent preferences", async ({
  page,
}) => {
  await page.addInitScript(() => {
    Object.defineProperty(window, "showSaveFilePicker", {
      value: undefined,
      configurable: true,
    });
    localStorage.setItem(
      "eplsync.catalog.dataExport.v1",
      JSON.stringify({ format: "JSON", columns: ["genres"], csv: "TAB" }),
    );
  });
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      json: {
        id: "reader",
        username: "reader",
        role: "USER",
        mustChangePassword: false,
        permissions: ["CALIBRE_READ"],
      },
    }),
  );
  await page.route("**/api/calibre/books?**", (route) =>
    route.fulfill({
      json: {
        items: [1, 2].map((id) => ({
          calibreId: id,
          eplId: 42,
          title: `Libro ${id}`,
          authors: "Autor",
          revision: 1,
          catalogRevision: 1.2,
          comparison: "OUTDATED",
          presence: "PRESENT",
          formats: ["epub"],
          hardcover: {},
        })),
        meta: {
          page: 0,
          size: 20,
          totalItems: 2,
          totalPages: 1,
          hasNext: false,
          hasPrevious: false,
        },
      },
    }),
  );
  const previews: Record<string, unknown>[] = [];
  await page.route("**/api/calibre/data/export/preview", (route) => {
    const body = route.request().postDataJSON();
    previews.push(body);
    expect(body.filters).toEqual({ ids: [2] });
    return route.fulfill({ json: { rows: 1, text: body.columns.join(";") } });
  });
  await page.route("**/api/calibre/data/export", (route) => {
    expect(route.request().postDataJSON()).toMatchObject({
      filters: { ids: [2] },
      format: "CSV",
      separator: "SEMICOLON",
      extension: "md",
      columns: ["eplId", "calibreId", "title", "authors", "revision"],
    });
    return route.fulfill({
      contentType: "text/csv",
      body: "eplId;calibreId;title;authors;revision\r\n42;2;Libro 2;Autor;1.0\r\n",
    });
  });
  await page.goto("/calibre");
  await page.locator("tbody tr").nth(1).getByRole("checkbox").check();
  await page
    .getByRole("button", { name: "Exportar datos", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByRole("textbox", { name: "Formato", exact: true }),
  ).toHaveValue("CSV");
  await expect(
    dialog.getByText("calibreId;eplId;title;authors;revision", { exact: true }),
  ).toBeVisible();
  await dialog.getByRole("textbox", { name: "Separador", exact: true }).click();
  await page
    .getByRole("option", { name: "Punto y coma (;)", exact: true })
    .click();
  await dialog.getByRole("button", { name: /mover.*EPL ID.*arriba/i }).click();
  await dialog
    .getByRole("textbox", { name: "Extensión del archivo" })
    .fill("md");
  await expect(
    dialog.getByText("eplId;calibreId;title;authors;revision", { exact: true }),
  ).toBeVisible();
  const download = page.waitForEvent("download");
  await dialog.getByRole("button", { name: "Descargar", exact: true }).click();
  expect((await download).suggestedFilename()).toBe("calibre.md");
  await dialog.getByRole("button", { name: "Cerrar", exact: true }).click();
  await page
    .getByRole("button", { name: "Exportar datos", exact: true })
    .click();
  await expect(
    dialog.getByRole("textbox", { name: "Separador", exact: true }),
  ).toHaveValue("Punto y coma (;)");
  await expect(
    dialog.getByRole("textbox", { name: "Extensión del archivo" }),
  ).toHaveValue("md");
  await dialog.getByRole("textbox", { name: "Formato", exact: true }).click();
  await page.getByRole("option", { name: "JSON", exact: true }).click();
  await expect(
    dialog.getByRole("textbox", { name: "Separador", exact: true }),
  ).toHaveCount(0);
  expect(previews.length).toBeGreaterThan(0);
  expect(
    await page.evaluate(
      () =>
        JSON.parse(localStorage.getItem("eplsync.catalog.dataExport.v1")!)
          .columns,
    ),
  ).toEqual(["genres"]);
});

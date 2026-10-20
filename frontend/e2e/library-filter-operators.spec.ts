import { test, expect } from "./fixtures";

test("Calibre exclusion filters survive pagination and selecting all, and each field clears independently", async ({
  page,
}) => {
  const requests: URL[] = [];
  const books = Array.from({ length: 101 }, (_, n) => ({
    calibreId: n + 1,
    title: `Book ${n + 1}`,
    authors: "Author",
    eplId: n + 1,
    revision: 1,
    catalogRevision: 1,
    comparison: n % 2 === 0 ? "OUTDATED" : "CURRENT",
    presence: (n + 1) % 5 === 0 ? "MISSING" : "PRESENT",
  }));
  await page.route("**/api/calibre/books?**", (r) => {
    const url = new URL(r.request().url());
    requests.push(url);
    const p = url.searchParams;
    let rows = books;
    for (const field of ["eplId", "comparison", "presence"] as const) {
      const values = p.get(field)?.split(",") ?? [];
      if (values.length)
        rows = rows.filter(
          (book) =>
            values.includes(String(book[field])) !==
            (p.get(`${field}Operator`) === "ne"),
        );
    }
    const index = Number(p.get("page") ?? 0),
      size = Number(p.get("size") ?? 20);
    return r.fulfill({
      json: {
        items: rows.slice(index * size, (index + 1) * size),
        meta: {
          page: index,
          size,
          totalItems: rows.length,
          totalPages: Math.ceil(rows.length / size),
          hasNext: (index + 1) * size < rows.length,
          hasPrevious: index > 0,
        },
      },
    });
  });
  await page.route("**/api/settings/calibre/snapshot", (r) =>
    r.fulfill({ json: { snapshot: null } }),
  );
  await page.goto("/calibre");
  const filters = page.locator(".filters");
  const field = (label: string) =>
    filters
      .locator(".mantine-InputWrapper-root")
      .filter({ has: page.getByRole("textbox", { name: label, exact: true }) });
  await page.getByRole("textbox", { name: "EPL ID", exact: true }).fill("1,3");
  await page
    .getByRole("textbox", { name: "EPL ID", exact: true })
    .press("Enter");
  await field("EPL ID")
    .getByRole("button", { name: "Incluir valores seleccionados", exact: true })
    .click();
  await page.getByRole("textbox", { name: "Comparación", exact: true }).click();
  await page
    .getByRole("option", {
      name: "Misma revisión que el catálogo",
      exact: true,
    })
    .click();
  await page.keyboard.press("Escape");
  await field("Comparación")
    .getByRole("button", { name: "Incluir valores seleccionados", exact: true })
    .click();
  await page
    .getByRole("textbox", { name: "En el catálogo", exact: true })
    .click();
  await page.getByRole("option", { name: "No", exact: true }).click();
  await page.keyboard.press("Escape");
  await field("En el catálogo")
    .getByRole("button", { name: "Incluir valores seleccionados", exact: true })
    .click();
  const expected = books
    .filter(
      (b) =>
        ![1, 3].includes(b.eplId) &&
        b.comparison !== "CURRENT" &&
        b.presence !== "MISSING",
    )
    .map((b) => b.calibreId);
  await expect
    .poll(() => requests.at(-1)?.searchParams.get("presenceOperator"))
    .toBe("ne");
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect.poll(() => requests.at(-1)?.searchParams.get("page")).toBe("1");
  for (const key of ["eplIdOperator", "comparisonOperator", "presenceOperator"])
    expect(requests.at(-1)?.searchParams.get(key)).toBe("ne");
  await page
    .getByRole("button", {
      name: `Seleccionar todo (${expected.length})`,
      exact: true,
    })
    .click();
  await expect(
    page.getByRole("button", {
      name: `Quitar selección (${expected.length})`,
      exact: true,
    }),
  ).toBeVisible();
  const bulk = requests.findLast((r) => r.searchParams.get("size") === "1000")!;
  for (const key of ["eplIdOperator", "comparisonOperator", "presenceOperator"])
    expect(bulk.searchParams.get(key)).toBe("ne");
  await page
    .getByRole("button", { name: "Limpiar: EPL ID", exact: true })
    .click();
  await expect(
    page.getByRole("textbox", { name: "EPL ID", exact: true }),
  ).toHaveValue("");
  await expect
    .poll(() => requests.at(-1)?.searchParams.has("eplIdOperator"))
    .toBe(false);
  for (const label of ["Comparación", "En el catálogo"])
    await page
      .getByRole("button", { name: `Limpiar: ${label}`, exact: true })
      .click();
  await expect(
    filters.getByRole("button", {
      name: "Excluir valores seleccionados",
      exact: true,
    }),
  ).toHaveCount(0);
  for (const label of ["Libro", "Autor"]) {
    await page.getByLabel(label, { exact: true }).fill("test");
    await page
      .getByRole("button", { name: `Limpiar: ${label}`, exact: true })
      .click();
    await expect(page.getByLabel(label, { exact: true })).toHaveValue("");
  }
});

test("Downloads origin has no operator and EPL ID can be cleared", async ({
  page,
}) => {
  const requests: URL[] = [];
  await page.route("**/api/torrent/downloads?**", (r) => {
    requests.push(new URL(r.request().url()));
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
  await page.goto("/downloads");
  await expect(
    page.locator(".filters").getByRole("button", {
      name: "Incluir valores seleccionados",
      exact: true,
    }),
  ).toHaveCount(2);
  await page.getByRole("textbox", { name: "EPL ID", exact: true }).fill("123");
  await page
    .getByRole("textbox", { name: "EPL ID", exact: true })
    .press("Enter");
  await expect
    .poll(() => requests.at(-1)?.searchParams.get("eplId"))
    .toBe("123");
  await page
    .getByRole("button", { name: "Limpiar: EPL ID", exact: true })
    .click();
  await expect(
    page.getByRole("textbox", { name: "EPL ID", exact: true }),
  ).toHaveValue("");
  await page.getByRole("textbox", { name: "Origen", exact: true }).click();
  await page.getByRole("option", { name: "EPL Sync", exact: true }).click();
  await expect
    .poll(() => requests.at(-1)?.searchParams.get("origin"))
    .toBe("EPLSYNC");
  expect(requests.at(-1)?.searchParams.has("eplId")).toBe(false);
  expect(requests.at(-1)?.searchParams.has("originOperator")).toBe(false);
});

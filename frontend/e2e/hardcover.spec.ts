import { test, expect } from "./fixtures";
const fields = ["SLUG", "BOOK_ID", "EDITION_ID", "ISBN10", "ISBN13", "ASIN"];
const emptyMeta = {
  page: 0,
  size: 20,
  totalItems: 1,
  totalPages: 1,
  hasNext: false,
  hasPrevious: false,
};

test.beforeEach(async ({ page }) => {
  await page.route("**/api/calibre/hardcover/edition-languages?**", (r) =>
    r.fulfill({
      json: [
        { code: "en", name: "English" },
        { code: "es", name: "Spanish" },
      ],
    }),
  );
  await page.route("**/api/calibre/books?**", (r) =>
    r.fulfill({
      json: {
        items: [
          {
            calibreId: 9,
            title: "Libro local",
            authors: "Autor local",
            eplId: 123,
            revision: 1,
            catalogRevision: 1,
            comparison: "CURRENT",
            presence: "PRESENT",
          },
        ],
        meta: emptyMeta,
      },
    }),
  );
  await page.route("**/api/settings/calibre/snapshot", (r) =>
    r.fulfill({ json: { snapshot: null } }),
  );
});

test("maps Hardcover data to custom identifiers, previews conflicts and saves only after confirmation", async ({
  page,
}) => {
  let mapping: Record<string, string> = {
    SLUG: "hardcover",
    BOOK_ID: "hardcover_book",
    EDITION_ID: "hardcover_edition",
  };
  let saves = 0,
    previews = 0,
    discarded = 0;
  await page.route("**/api/calibre/hardcover", (r) => {
    if (r.request().method() === "PUT") mapping = r.request().postDataJSON();
    return r.fulfill({ json: { configured: true, fields, mappings: mapping } });
  });
  await page.route("**/api/calibre/hardcover/search", (r) => {
    expect(r.request().postDataJSON()).toEqual({
      calibreId: 9,
      page: 1,
      useTitle: true,
      useAuthor: true,
      useIsbn: true,
      useAsin: true,
    });
    return r.fulfill({
      json: {
        token: "source-token",
        calibreId: 9,
        title: "Libro local",
        authors: ["Autor local"],
        page: 1,
        hasNext: true,
        items: [
          {
            id: 7,
            slug: "book-slug",
            title: "Libro encontrado",
            authors: ["Autor"],
          },
        ],
      },
    });
  });
  await page.route("**/api/calibre/hardcover/editions?**", (r) => {
    expect(new URL(r.request().url()).searchParams.get("bookId")).toBe("7");
    return r.fulfill({
      json: {
        items: [
          {
            id: 11,
            title: "Edición española",
            language: "es",
            publisher: "Editorial",
            isbn10: "020161622X",
            isbn13: "9780201616224",
            asin: "",
          },
        ],
        hasNext: true,
      },
    });
  });
  const values = [
    {
      field: "SLUG",
      identifier: "hardcover",
      existing: "",
      value: "book-slug",
      state: "READY",
    },
    {
      field: "BOOK_ID",
      identifier: "custom_book",
      existing: "",
      value: "7",
      state: "READY",
    },
    {
      field: "EDITION_ID",
      identifier: "hardcover_edition",
      existing: "",
      value: "11",
      state: "READY",
    },
    {
      field: "ISBN13",
      identifier: "isbn",
      existing: "9780000000000",
      value: "9780201616224",
      state: "CONFLICT",
    },
  ];
  await page.route("**/api/calibre/hardcover/preview", (r) => {
    previews++;
    expect(r.request().postDataJSON()).toEqual({
      token: "source-token",
      bookId: 7,
      editionId: r.request().postDataJSON().editionId,
      mappings: {
        SLUG: "hardcover",
        BOOK_ID: "custom_book",
        EDITION_ID: "hardcover_edition",
        ISBN13: "isbn",
      },
    });
    return r.fulfill({
      json: {
        token: "confirmation-token",
        calibreId: 9,
        title: "Libro local",
        saved: false,
        values,
      },
    });
  });
  await page.route("**/api/calibre/hardcover/apply", (r) => {
    saves++;
    expect(r.request().postDataJSON()).toEqual({ token: "confirmation-token" });
    return r.fulfill({
      json: {
        token: "confirmation-token",
        calibreId: 9,
        title: "Libro local",
        saved: true,
        values,
      },
    });
  });
  await page.route("**/api/calibre/hardcover/discard", (r) => {
    discarded++;
    return r.fulfill({ json: { discarded: true } });
  });
  await page.goto("/calibre");
  await page
    .locator("tbody")
    .getByRole("button", { name: "Completar Hardcover", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByRole("group", {
      name: "Parámetros de búsqueda en Hardcover",
      exact: true,
    }),
  ).toHaveCount(0);
  await expect(
    dialog.getByRole("group", {
      name: "Identificadores que se guardarán en Calibre",
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    dialog.getByRole("table", { name: "Datos de Hardcover" }),
  ).toBeVisible();
  await dialog
    .getByRole("textbox", { name: "Identificador: Hardcover ID", exact: true })
    .fill("custom_book");
  await dialog
    .getByRole("checkbox", { name: "Guardar: ISBN-13", exact: true })
    .check();
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  await dialog
    .getByRole("textbox", { name: "Libro en Hardcover", exact: true })
    .click();
  await page.getByRole("option", { name: /Libro encontrado/ }).click();
  await expect(
    dialog.getByRole("textbox", { name: "Edición en Hardcover", exact: true }),
  ).toBeEnabled();
  await dialog
    .getByRole("textbox", { name: "Edición en Hardcover", exact: true })
    .click();
  await page.getByRole("option", { name: /Edición española/ }).click();
  await expect(
    dialog
      .getByRole("link", { name: "Abrir en Hardcover", exact: true })
      .first(),
  ).toHaveAttribute("href", "https://hardcover.app/books/book-slug");
  await expect(
    dialog
      .getByRole("link", { name: "Abrir en Hardcover", exact: true })
      .nth(1),
  ).toHaveAttribute(
    "href",
    "https://hardcover.app/books/book-slug/editions/11",
  );
  expect(saves).toBe(0);
  await expect(
    dialog.getByRole("button", { name: "Previsualizar", exact: true }),
  ).toHaveCount(0);
  await expect(
    dialog.getByRole("table", { name: "Previsualizar" }),
  ).toContainText("custom_book");
  await expect(
    dialog.getByText("Valor diferente; se conserva", { exact: true }),
  ).toBeVisible();
  await expect.poll(() => previews).toBe(2);
  expect(saves).toBe(0);
  await dialog
    .getByRole("button", { name: "Guardar en Calibre", exact: true })
    .click();
  await expect(dialog.locator(".app-modal-actions")).toContainText(
    "1 libro guardado",
  );
  await expect(
    dialog.getByText("1 libro guardado", { exact: true }),
  ).toBeVisible();
  await expect(
    dialog.getByRole("button", { name: "Guardar en Calibre", exact: true }),
  ).toHaveCount(0);
  expect(saves).toBe(1);
  await dialog.getByRole("button", { name: "Cerrar", exact: true }).click();
  await expect.poll(() => discarded).toBe(1);
});

test("missing API key and duplicate destinations cannot start a lookup", async ({
  page,
}) => {
  let configured = false;
  await page.route("**/api/calibre/hardcover", (r) =>
    r.fulfill({
      json: {
        configured,
        fields,
        mappings: { SLUG: "hardcover", BOOK_ID: "hardcover_book" },
      },
    }),
  );
  await page.goto("/calibre");
  const open = page
    .locator("tbody")
    .getByRole("button", { name: "Completar Hardcover", exact: true });
  await open.click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByText(/EPLSYNC_HARDCOVER_API_KEY/)).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Buscar" })).toBeDisabled();
  await expect(dialog.locator('input[type="password"]')).toHaveCount(0);
  await dialog.getByRole("button", { name: "Cerrar", exact: true }).click();
  configured = true;
  await open.click();
  await dialog
    .getByRole("textbox", { name: "Identificador: Hardcover ID", exact: true })
    .fill("hardcover");
  await expect(dialog.getByRole("button", { name: "Buscar" })).toBeDisabled();
  await dialog
    .getByRole("textbox", { name: "Identificador: Hardcover ID", exact: true })
    .fill("epl");
  await expect(dialog.getByRole("button", { name: "Buscar" })).toBeDisabled();
});

test("read-only users have no Hardcover write action", async ({ page }) => {
  await page.route("**/api/auth/me", (r) =>
    r.fulfill({
      json: {
        id: "reader",
        username: "reader",
        role: "USER",
        email: "",
        mustChangePassword: false,
        permissions: ["CALIBRE_READ"],
      },
    }),
  );
  await page.goto("/calibre");
  await expect(page.getByRole("table")).toContainText("Libro local");
  await expect(
    page.getByRole("button", { name: "Completar Hardcover", exact: true }),
  ).toHaveCount(0);
});

test("close aborts a pending lookup and never writes to Calibre", async ({
  page,
}) => {
  let resolve!: () => void;
  const waiting = new Promise<void>((done) => {
    resolve = done;
  });
  await page.route("**/api/calibre/hardcover", (r) =>
    r.fulfill({
      json: {
        configured: true,
        fields,
        mappings: { BOOK_ID: "hardcover_book" },
      },
    }),
  );
  await page.route("**/api/calibre/hardcover/search", async (r) => {
    await waiting;
    await r.fulfill({ json: {} }).catch(() => undefined);
  });
  await page.goto("/calibre");
  await page
    .locator("tbody")
    .getByRole("button", { name: "Completar Hardcover", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Buscar" }).click();
  await expect(dialog.getByRole("status")).toContainText("Consultando");
  await dialog.getByRole("button", { name: "Cerrar", exact: true }).click();
  await expect(dialog).toBeHidden();
  resolve();
});

test("search fields and previous-book navigation preserve the chosen query", async ({
  page,
}) => {
  await page.route("**/api/calibre/books?**", (r) =>
    r.fulfill({
      json: {
        items: [9, 10].map((calibreId) => ({
          calibreId,
          title: `Libro ${calibreId}`,
          authors: "Autor",
          comparison: "CURRENT",
          presence: "PRESENT",
        })),
        meta: { ...emptyMeta, totalItems: 2 },
      },
    }),
  );
  await page.route("**/api/calibre/hardcover", (r) =>
    r.fulfill({
      json: {
        configured: true,
        fields,
        mappings: { BOOK_ID: "hardcover_book" },
      },
    }),
  );
  const lookedUp: number[] = [];
  await page.route("**/api/calibre/hardcover/search", (r) => {
    const input = r.request().postDataJSON();
    expect(input).toMatchObject({
      useTitle: true,
      useAuthor: true,
      useIsbn: true,
      useAsin: true,
    });
    lookedUp.push(input.calibreId);
    return r.fulfill({
      json: {
        token: `source-${input.calibreId}`,
        calibreId: input.calibreId,
        title: `Libro ${input.calibreId}`,
        authors: ["Autor"],
        page: 1,
        hasNext: false,
        items: [],
      },
    });
  });
  await page.route("**/api/calibre/hardcover/discard", (r) =>
    r.fulfill({ json: { discarded: true } }),
  );
  await page.goto("/calibre");
  await page
    .getByRole("checkbox", { name: "Seleccionar registros de esta página" })
    .check();
  await page
    .getByRole("button", { name: "Completar Hardcover", exact: true })
    .first()
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect(
    dialog.getByText("Libro 1 de 2", {
      exact: true,
    }),
  ).toBeVisible();
  await dialog
    .getByRole("button", { name: "Omitir libro", exact: true })
    .click();
  await expect(
    dialog.getByText("Libro 2 de 2", {
      exact: true,
    }),
  ).toBeVisible();
  await dialog
    .getByRole("button", { name: "Libro anterior", exact: true })
    .click();
  await expect(
    dialog.getByText("Libro 1 de 2", {
      exact: true,
    }),
  ).toBeVisible();
  expect(lookedUp).toEqual([9, 10, 9]);
});

test("missing ISBN and ASIN show per-book warnings without offering a save", async ({
  page,
}) => {
  await page.route("**/api/calibre/hardcover", (r) =>
    r.fulfill({
      json: {
        configured: true,
        fields,
        mappings: { BOOK_ID: "hardcover_book" },
      },
    }),
  );
  await page.route("**/api/calibre/hardcover/search", (r) => {
    expect(r.request().postDataJSON()).toMatchObject({
      useTitle: true,
      useAuthor: true,
      useIsbn: true,
      useAsin: true,
    });
    return r.fulfill({
      json: {
        token: "unavailable",
        calibreId: 9,
        title: "Libro local",
        authors: ["Autor local"],
        items: [],
        hasNext: false,
        page: 1,
        notices: ["ISBN_UNAVAILABLE", "ASIN_UNAVAILABLE", "NO_CRITERIA"],
      },
    });
  });
  await page.route("**/api/calibre/hardcover/discard", (r) =>
    r.fulfill({ json: { discarded: true } }),
  );
  await page.goto("/calibre");
  await page
    .locator("tbody")
    .getByRole("button", { name: "Completar Hardcover", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect(
    dialog.getByRole("textbox", { name: "ISBN", exact: true }),
  ).not.toHaveAttribute("placeholder", /\S/);
  await expect(
    dialog.getByRole("textbox", { name: "ASIN", exact: true }),
  ).not.toHaveAttribute("placeholder", /\S/);
  await expect(dialog.getByRole("status")).toContainText(
    "No hay criterios disponibles",
  );
  await expect(
    dialog.getByRole("button", { name: "Guardar en Calibre", exact: true }),
  ).toHaveCount(0);
});

test("custom title and author lookup leaves the original book unchanged", async ({
  page,
}) => {
  await page.route("**/api/calibre/hardcover", (r) =>
    r.fulfill({
      json: {
        configured: true,
        fields,
        mappings: { BOOK_ID: "hardcover_book" },
      },
    }),
  );
  let calls = 0;
  await page.route("**/api/calibre/hardcover/search", (r) => {
    const data = r.request().postDataJSON();
    calls++;
    if (calls === 2)
      expect(data).toMatchObject({
        calibreId: 9,
        queryTitle: "Dune",
        queryAuthor: "Frank Herbert",
        useTitle: true,
        useAuthor: true,
        useIsbn: false,
        useAsin: false,
      });
    return r.fulfill({
      json: {
        token: `source-${calls}`,
        calibreId: 9,
        title: "Dune (versión ilustrada)",
        authors: ["Frank Herbert y otros"],
        items: [],
        page: 1,
        hasNext: false,
      },
    });
  });
  await page.route("**/api/calibre/hardcover/discard", (r) =>
    r.fulfill({ json: { discarded: true } }),
  );
  await page.goto("/calibre");
  await page
    .locator("tbody")
    .getByRole("button", { name: "Completar Hardcover", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect(
    dialog.getByRole("textbox", { name: "Título", exact: true }),
  ).toHaveValue("Dune (versión ilustrada)");
  await expect(
    dialog.getByRole("button", { name: "Opciones", exact: true }),
  ).toHaveCount(0);
  await dialog
    .getByRole("textbox", { name: "Título", exact: true })
    .fill("Dune");
  await dialog
    .getByRole("textbox", { name: "Autor", exact: true })
    .fill("Frank Herbert");
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect(
    dialog.getByText("Dune (versión ilustrada)", { exact: true }),
  ).toBeVisible();
  expect(calls).toBe(2);
  await expect(
    dialog.getByRole("textbox", { name: "Título", exact: true }),
  ).toHaveValue("Dune");
});

test("manual slug resolves a new book, resets the edition and marks the selection", async ({
  page,
}) => {
  await page.route("**/api/calibre/hardcover", (r) =>
    r.fulfill({
      json: {
        configured: true,
        fields,
        mappings: {
          SLUG: "hardcover",
          BOOK_ID: "hardcover_book",
          EDITION_ID: "hardcover_edition",
        },
      },
    }),
  );
  await page.route("**/api/calibre/hardcover/search", (r) =>
    r.fulfill({
      json: {
        token: "source",
        calibreId: 9,
        title: "Libro local",
        authors: ["Autor"],
        page: 1,
        hasNext: true,
        items: [
          { id: 7, slug: "old-book", title: "Old book", authors: ["Author"] },
        ],
      },
    }),
  );
  await page.route("**/api/calibre/hardcover/editions?**", (r) => {
    const book = new URL(r.request().url()).searchParams.get("bookId");
    return r.fulfill({
      json: {
        items: [
          {
            id: book === "7" ? 11 : 22,
            title: book === "7" ? "Old edition" : "New edition",
            language: "es",
            publisher: "Publisher",
            isbn10: "",
            isbn13: "",
            asin: "",
          },
          {
            id: book === "7" ? 12 : 23,
            title: "Alternative edition",
            language: "en",
            publisher: "Publisher",
            isbn10: "",
            isbn13: "",
            asin: "",
          },
        ],
        hasNext: false,
      },
    });
  });
  await page.route("**/api/calibre/hardcover/preview", (r) => {
    const input = r.request().postDataJSON();
    return r.fulfill({
      json: {
        token: `preview-${input.bookId}`,
        calibreId: 9,
        title: "Libro local",
        saved: false,
        values: [
          {
            field: "SLUG",
            identifier: "hardcover",
            existing: "",
            value: input.bookId === 7 ? "old-book" : "new-book",
            state: "READY",
          },
          {
            field: "BOOK_ID",
            identifier: "hardcover_book",
            existing: "",
            value: String(input.bookId),
            state: "READY",
          },
          {
            field: "EDITION_ID",
            identifier: "hardcover_edition",
            existing: "",
            value: input.editionId ? String(input.editionId) : "",
            state: input.editionId ? "READY" : "MISSING",
          },
        ],
      },
    });
  });
  await page.route("**/api/calibre/hardcover/resolve-slug", (r) => {
    expect(r.request().postDataJSON()).toEqual({
      token: "source",
      slug: "new-book",
    });
    return r.fulfill({
      json: { id: 8, slug: "new-book", title: "New book", authors: ["Author"] },
    });
  });
  await page.goto("/calibre");
  await page
    .locator("tbody")
    .getByRole("button", { name: "Completar Hardcover", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  await dialog
    .getByRole("textbox", { name: "Libro en Hardcover", exact: true })
    .click();
  await page.getByRole("option", { name: /Old book/ }).click();
  await expect(
    dialog.getByRole("textbox", { name: "Edición en Hardcover", exact: true }),
  ).toBeEnabled();
  await dialog
    .getByRole("textbox", { name: "Edición en Hardcover", exact: true })
    .click();
  await page.getByRole("option", { name: /Old edition/ }).click();
  const slug = dialog.getByRole("textbox", {
    name: "Hardcover (slug) manual",
    exact: true,
  });
  await expect(slug).toHaveAttribute("readonly", "");
  await dialog
    .getByRole("button", { name: "Editar slug de Hardcover", exact: true })
    .click();
  await expect(slug).not.toHaveAttribute("readonly", "");
  await slug.fill("new-book");
  await expect(
    dialog.getByRole("button", { name: "Guardar en Calibre", exact: true }),
  ).toBeDisabled();
  await dialog.getByRole("button", { name: "Comprobar", exact: true }).click();
  await expect(slug).toHaveAttribute("readonly", "");
  await expect(
    dialog.getByRole("button", {
      name: "Editar slug de Hardcover",
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    dialog.getByRole("button", { name: "Comprobar", exact: true }),
  ).toHaveCount(0);
  await dialog
    .getByRole("button", { name: "Editar slug de Hardcover", exact: true })
    .click();
  await slug.fill("discarded-edit");
  await slug.press("Escape");
  await expect(dialog).toBeVisible();
  await expect(slug).toHaveValue("new-book");
  await expect(slug).toHaveAttribute("readonly", "");
  await expect(
    dialog.getByText(
      "Obra seleccionada manualmente mediante el slug de Hardcover.",
      { exact: true },
    ),
  ).toBeVisible();
  await expect(
    dialog.getByRole("textbox", { name: "Libro en Hardcover", exact: true }),
  ).toHaveValue(/New book/);
  await expect(
    dialog.getByRole("textbox", { name: "Edición en Hardcover", exact: true }),
  ).toHaveValue("");
  await expect(
    dialog
      .getByRole("table", { name: "Previsualizar" })
      .getByRole("row")
      .filter({ hasText: "hardcover_book" }),
  ).toContainText("8");
  await expect(
    dialog
      .getByRole("table", { name: "Previsualizar" })
      .getByRole("row")
      .filter({ hasText: "hardcover_edition" }),
  ).toContainText("No disponible; se omite");
  await dialog
    .getByRole("textbox", { name: "Edición en Hardcover", exact: true })
    .click();
  await expect(page.getByRole("option", { name: /New edition/ })).toBeVisible();
  await expect(page.getByRole("option", { name: /Old edition/ })).toHaveCount(
    0,
  );
});

test("unique book and edition are selected automatically and preview columns resize", async ({
  page,
}) => {
  await page.route("**/api/calibre/hardcover", (r) =>
    r.fulfill({
      json: {
        configured: true,
        fields,
        mappings: {
          SLUG: "hardcover",
          BOOK_ID: "hardcover_book",
          EDITION_ID: "hardcover_edition",
        },
      },
    }),
  );
  await page.route("**/api/calibre/hardcover/search", (r) =>
    r.fulfill({
      json: {
        token: "unique",
        calibreId: 9,
        title: "Libro local",
        authors: ["Autor"],
        page: 1,
        hasNext: false,
        items: [
          {
            id: 7,
            slug: "unique-book",
            title: "Only book",
            ratingsCount: 1234,
            authors: ["Author"],
          },
        ],
      },
    }),
  );
  await page.route("**/api/calibre/hardcover/editions?**", (r) =>
    r.fulfill({
      json: {
        items: [
          {
            id: 11,
            title: "Only edition",
            usersCount: 87,
            language: "es",
            publisher: "Publisher",
            isbn10: "",
            isbn13: "",
            asin: "",
          },
        ],
        hasNext: false,
      },
    }),
  );
  let previews = 0;
  await page.route("**/api/calibre/hardcover/preview", (r) => {
    previews++;
    expect(r.request().postDataJSON()).toMatchObject({
      bookId: 7,
      editionId: 11,
    });
    return r.fulfill({
      json: {
        token: "preview",
        calibreId: 9,
        title: "Libro local",
        saved: false,
        values: [
          {
            field: "SLUG",
            identifier: "hardcover",
            existing: "",
            value: "unique-book",
            state: "READY",
          },
          {
            field: "BOOK_ID",
            identifier: "hardcover_book",
            existing: "",
            value: "7",
            state: "READY",
          },
          {
            field: "EDITION_ID",
            identifier: "hardcover_edition",
            existing: "",
            value: "11",
            state: "READY",
          },
        ],
      },
    });
  });
  let writes = 0;
  await page.route("**/api/calibre/hardcover/apply", (r) => {
    writes++;
    return r.fulfill({ json: {} });
  });
  await page.goto("/calibre");
  await page
    .locator("tbody")
    .getByRole("button", { name: "Completar Hardcover", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect(
    dialog.getByRole("textbox", { name: "Libro en Hardcover", exact: true }),
  ).toHaveValue(/Only book/);
  await expect(
    dialog.getByRole("textbox", { name: "Edición en Hardcover", exact: true }),
  ).toHaveValue(/Only edition/);
  await expect(
    dialog.getByRole("textbox", { name: "Libro en Hardcover", exact: true }),
  ).toHaveValue(/1[.,]?234 valoraciones/);
  await expect(
    dialog.getByRole("textbox", { name: "Edición en Hardcover", exact: true }),
  ).toHaveValue(/87 usuarios/);
  await expect(
    dialog.getByRole("textbox", { name: "Libro en Hardcover", exact: true }),
  ).toHaveAttribute("title", /título exacto.*relevancia.*valoraciones/);
  await expect(
    dialog.getByRole("textbox", { name: "Edición en Hardcover", exact: true }),
  ).toHaveAttribute("title", /usuarios.*mayor a menor/);
  const [languageBox, editionBox] = await dialog
    .locator('.hardcover-edition-selectors input:not([type="hidden"])')
    .evaluateAll((inputs) =>
      inputs.map((input) => {
        const rect = input.getBoundingClientRect();
        return { y: rect.y, width: rect.width };
      }),
    );
  expect(Math.abs(languageBox.y - editionBox.y)).toBeLessThan(2);
  expect(languageBox.width).toBeGreaterThan(55);
  expect(languageBox.width).toBeLessThan(editionBox.width);
  await expect(
    dialog.getByRole("navigation", { name: "Páginas de obras en Hardcover" }),
  ).toHaveCount(0);
  await expect(
    dialog.getByRole("navigation", {
      name: "Páginas de ediciones en Hardcover",
    }),
  ).toHaveCount(0);
  const searchButton = dialog.getByRole("button", {
    name: "Buscar",
    exact: true,
  });
  const right = await searchButton.evaluate((button) => ({
    button: button.getBoundingClientRect().right,
    parent: button.parentElement!.getBoundingClientRect().right,
  }));
  expect(Math.abs(right.button - right.parent)).toBeLessThan(2);
  const links = dialog.getByRole("link", {
    name: "Abrir en Hardcover",
    exact: true,
  });
  await expect(links).toHaveCount(2);
  for (let index = 0; index < 2; index++) {
    await expect(links.nth(index)).toHaveAttribute("data-variant", "light");
    await expect(links.nth(index).locator("svg")).toHaveAttribute(
      "width",
      "16",
    );
  }
  for (const width of [1440, 480]) {
    await page.setViewportSize({ width, height: 1000 });
    const layout = await dialog
      .locator(".hardcover-book-selector, .hardcover-edition-selectors")
      .evaluateAll((rows) =>
        rows.map((row) => {
          const inputs = Array.from(
            row.querySelectorAll('input:not([type="hidden"])'),
          );
          const input = inputs[inputs.length - 1].getBoundingClientRect();
          const link = row
            .querySelector("a.hardcover-open-link")!
            .getBoundingClientRect();
          return {
            bottom: Math.abs(input.bottom - link.bottom),
            right: link.left - input.right,
            overflow: row.scrollWidth - row.clientWidth,
          };
        }),
      );
    for (const row of layout) {
      expect(row.bottom).toBeLessThan(2);
      expect(row.right).toBeGreaterThan(5);
      expect(row.overflow).toBeLessThan(2);
    }
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
  const table = dialog.getByRole("table", { name: "Previsualizar" });
  await expect(table).toBeVisible();
  for (const label of ["Libro en Calibre", "Libro en Hardcover"])
    await expect(
      dialog.getByRole("group", { name: label, exact: true }),
    ).toBeVisible();
  await expect(dialog.locator(".app-modal-actions")).toContainText(
    "Libro 1 de 1",
  );
  await expect(dialog.locator(".app-modal-actions")).toContainText(
    "0 libros guardados",
  );
  const gaps = await dialog.evaluate((element) => {
    const sections = Array.from(
      element.querySelectorAll(".hardcover-section"),
    ).map((section) => section.getBoundingClientRect());
    const table = element
      .querySelector('table[aria-label="Previsualizar"]')!
      .parentElement!.getBoundingClientRect();
    return [
      sections[1].top - sections[0].bottom,
      table.top - sections[1].bottom,
    ];
  });
  for (const gap of gaps) expect(Math.abs(gap - 16)).toBeLessThan(1);
  const rowHeights = await table
    .locator("tbody tr")
    .evaluateAll((rows) =>
      rows.map((row) => row.getBoundingClientRect().height),
    );
  expect(Math.max(...rowHeights) - Math.min(...rowHeights)).toBeLessThan(1);

  expect(previews).toBe(1);
  expect(writes).toBe(0);
  const handle = table.locator("th").first().getByRole("button");
  const before = (await table.locator("th").first().boundingBox())!.width;
  await handle.focus();
  await handle.press("ArrowRight");
  await expect
    .poll(async () => (await table.locator("th").first().boundingBox())!.width)
    .toBeGreaterThan(before + 10);
  const rect = (await handle.boundingBox())!;
  await page.mouse.move(rect.x + rect.width / 2, rect.y + rect.height / 2);
  await page.mouse.down();
  await page.mouse.move(rect.x + rect.width / 2 + 40, rect.y + rect.height / 2);
  await page.mouse.up();
  await expect
    .poll(async () => (await table.locator("th").first().boundingBox())!.width)
    .toBeGreaterThan(before + 40);
  await expect(
    table.getByRole("textbox", {
      name: "Hardcover (slug) manual",
      exact: true,
    }),
  ).toBeVisible();
  expect(writes).toBe(0);
});

for (const [browser, expected] of [
  ["es-MX", "es"],
  ["en-GB", "en"],
  ["fr-FR", ""],
  ["ca-ES", "ca"],
  ["fr-FR", "fr"],
  ["it-IT", "it"],
  ["pl-PL", "pl"],
  ["pt-BR", "pt"],
  ["zh-Hant-TW", "zh"],
  ["ast-ES", "ast"],
]) {
  test(`edition language defaults to ${expected || "All"} for ${browser} and changing it clears selection`, async ({
    page,
  }) => {
    await page.addInitScript(
      (value) =>
        Object.defineProperty(navigator, "language", { get: () => value }),
      browser,
    );
    if (expected && !["es", "en"].includes(expected)) {
      await page.route("**/api/calibre/hardcover/edition-languages?**", (r) =>
        r.fulfill({
          json: [
            { code: expected, name: expected.toUpperCase() },
            { code: "en", name: "English" },
          ],
        }),
      );
    }
    await page.route("**/api/calibre/hardcover", (r) =>
      r.fulfill({
        json: {
          configured: true,
          fields,
          mappings: { EDITION_ID: "hardcover_edition" },
        },
      }),
    );
    await page.route("**/api/calibre/hardcover/search", (r) =>
      r.fulfill({
        json: {
          token: "lang",
          calibreId: 9,
          title: "Libro local",
          authors: ["Autor"],
          page: 1,
          hasNext: false,
          items: [
            { id: 7, slug: "book", title: "Only book", authors: ["Author"] },
          ],
        },
      }),
    );
    const languages: string[] = [];
    await page.route("**/api/calibre/hardcover/editions?**", (r) => {
      const language = new URL(r.request().url()).searchParams.get("language")!;
      languages.push(language);
      const items = (language ? [language] : ["es", "en"]).map(
        (lang, index) => ({
          id: lang === "es" ? 11 : 12,
          title: `Edition ${lang}`,
          language: lang,
          publisher: "Publisher",
          isbn10: "",
          isbn13: "",
          asin: "",
        }),
      );
      return r.fulfill({ json: { items, hasNext: false } });
    });
    await page.route("**/api/calibre/hardcover/preview", (r) => {
      const { editionId } = r.request().postDataJSON();
      return r.fulfill({
        json: {
          token: "p",
          calibreId: 9,
          title: "Libro local",
          saved: false,
          values: [
            {
              field: "EDITION_ID",
              identifier: "hardcover_edition",
              existing: "",
              value: editionId ? String(editionId) : "",
              state: editionId ? "READY" : "MISSING",
            },
          ],
        },
      });
    });
    await page.goto("/calibre");
    await page
      .locator("tbody")
      .getByRole("button", { name: "Completar Hardcover", exact: true })
      .click();
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
    const language = dialog.getByRole("textbox", {
      name: "Idioma",
      exact: true,
    });
    await expect(language).toHaveValue(
      expected ? new RegExp(expected.toUpperCase()) : "All",
    );
    await expect(
      dialog.getByRole("table", { name: "Previsualizar" }),
    ).toBeVisible();
    expect(languages[0]).toBe(expected);
    if (expected) {
      await expect(
        dialog.getByRole("textbox", {
          name: "Edición en Hardcover",
          exact: true,
        }),
      ).toHaveValue(new RegExp(`Edition ${expected}`));
      await language.click();
      await page.getByRole("option", { name: "All", exact: true }).click();
      await expect(
        dialog.getByRole("textbox", {
          name: "Edición en Hardcover",
          exact: true,
        }),
      ).toHaveValue("");
      await expect(
        dialog.getByRole("table", { name: "Previsualizar" }),
      ).toContainText("No disponible; se omite");
      expect(languages).toEqual([expected, ""]);
    }
  });
}

test("edition updates keep the preview mounted and block stale confirmation on errors", async ({
  page,
}) => {
  await page.route("**/api/calibre/hardcover", (r) =>
    r.fulfill({
      json: {
        configured: true,
        fields,
        mappings: {
          BOOK_ID: "hardcover_book",
          EDITION_ID: "hardcover_edition",
        },
      },
    }),
  );
  await page.route("**/api/calibre/hardcover/search", (r) =>
    r.fulfill({
      json: {
        token: "stable",
        calibreId: 9,
        title: "Libro local",
        authors: ["Autor"],
        page: 1,
        hasNext: false,
        items: [
          { id: 7, slug: "book", title: "Only book", authors: ["Author"] },
        ],
      },
    }),
  );
  await page.route("**/api/calibre/hardcover/editions?**", (r) =>
    r.fulfill({
      json: {
        items: [11, 12, 13].map((id) => ({
          id,
          title: `Edition ${id}`,
          language: "es",
          publisher: "Publisher",
          isbn10: "",
          isbn13: "",
          asin: "",
        })),
        hasNext: false,
      },
    }),
  );
  let release!: () => void;
  const waiting = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/calibre/hardcover/preview", async (r) => {
    const { editionId } = r.request().postDataJSON();
    if (editionId === 11) await waiting;
    if (editionId === 13)
      return r.fulfill({
        status: 500,
        json: { details: "No se pudo comprobar la edición" },
      });
    return r.fulfill({
      json: {
        token: `preview-${editionId}`,
        calibreId: 9,
        title: "Libro local",
        saved: false,
        values: [
          {
            field: "BOOK_ID",
            identifier: "hardcover_book",
            existing: "",
            value: "7",
            state: "READY",
          },
          {
            field: "EDITION_ID",
            identifier: "hardcover_edition",
            existing: "",
            value: editionId ? String(editionId) : "",
            state: editionId ? "READY" : "MISSING",
          },
        ],
      },
    });
  });
  await page.goto("/calibre");
  await page
    .locator("tbody")
    .getByRole("button", { name: "Completar Hardcover", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  const table = dialog.getByRole("table", { name: "Previsualizar" });
  await expect(table).toBeVisible();
  await table.evaluate((element) => {
    element.setAttribute("data-instance", "original");
  });
  const edition = dialog.getByRole("textbox", {
    name: "Edición en Hardcover",
    exact: true,
  });
  await edition.click();
  await page.getByRole("option", { name: /Edition 11/ }).click();
  await expect(table).toBeVisible();
  await expect(table).toHaveAttribute("data-instance", "original");
  await expect(table).toHaveAttribute("aria-busy", "true");
  const save = dialog.getByRole("button", {
    name: "Guardar en Calibre",
    exact: true,
  });
  await expect(save).toBeDisabled();
  release();
  await expect(table).toHaveAttribute("aria-busy", "false");
  await expect(
    table.getByRole("textbox", {
      name: "Hardcover edition manual",
      exact: true,
    }),
  ).toHaveValue("11");
  await edition.click();
  await page.getByRole("option", { name: /Edition 13/ }).click();
  await expect(dialog.getByRole("alert")).toBeVisible();
  await expect(save).toBeDisabled();
  await expect(table).toHaveAttribute("data-instance", "original");
  await edition.click();
  await page.getByRole("option", { name: /Edition 12/ }).click();
  await expect(table).toHaveAttribute("aria-busy", "false");
  await expect(
    table.getByRole("textbox", {
      name: "Hardcover edition manual",
      exact: true,
    }),
  ).toHaveValue("12");
  await expect(table).toHaveAttribute("data-instance", "original");
  await expect(save).toBeEnabled();
});

test("missing identifiers can be entered manually and invalid queries keep the fields available", async ({
  page,
}) => {
  await page.route("**/api/calibre/hardcover", (r) =>
    r.fulfill({
      json: {
        configured: true,
        fields,
        mappings: { BOOK_ID: "hardcover_book" },
      },
    }),
  );
  let calls = 0;
  await page.route("**/api/calibre/hardcover/search", (r) => {
    const input = r.request().postDataJSON();
    calls++;
    if (calls === 2) {
      expect(input).toMatchObject({ useIsbn: true, queryIsbn: "bad" });
      return r.fulfill({
        status: 400,
        json: { details: "El ISBN introducido no es válido" },
      });
    }
    if (calls === 3)
      expect(input).toMatchObject({
        queryIsbn: "9780201616224",
        queryAsin: "B000000001",
        useIsbn: true,
        useAsin: true,
      });
    return r.fulfill({
      json: {
        token: `ids-${calls}`,
        calibreId: 9,
        title: "Libro local",
        authors: ["Autor"],
        page: 1,
        hasNext: false,
        items: [],
        notices:
          calls === 1
            ? ["ISBN_UNAVAILABLE", "ASIN_UNAVAILABLE", "NO_CRITERIA"]
            : ["EXACT_IDENTIFIERS"],
        queryIsbn: "",
        queryAsin: "",
      },
    });
  });
  await page.route("**/api/calibre/hardcover/discard", (r) =>
    r.fulfill({ json: { discarded: true } }),
  );
  await page.goto("/calibre");
  await page
    .locator("tbody")
    .getByRole("button", { name: "Completar Hardcover", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  const isbn = dialog.getByRole("textbox", {
    name: "ISBN",
    exact: true,
  });
  const asin = dialog.getByRole("textbox", {
    name: "ASIN",
    exact: true,
  });
  await expect(isbn).toHaveValue("");
  await expect(asin).toHaveValue("");
  await isbn.fill("bad");
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect(dialog.getByRole("alert")).toBeVisible();
  await expect(isbn).toHaveValue("bad");
  await isbn.fill("9780201616224");
  await asin.fill("B000000001");
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect(
    dialog
      .getByRole("alert")
      .filter({ hasText: "El ISBN introducido no es válido" }),
  ).toHaveCount(0);
  await expect(isbn).toHaveValue("9780201616224");
  await expect(asin).toHaveValue("B000000001");
  expect(calls).toBe(3);
});

test("Hardcover options load on scroll without page buttons or losing selection", async ({
  page,
}) => {
  await page.route("**/api/calibre/hardcover", (r) =>
    r.fulfill({
      json: {
        configured: true,
        fields,
        mappings: {
          BOOK_ID: "hardcover_book",
          EDITION_ID: "hardcover_edition",
        },
      },
    }),
  );
  const bookPages: number[] = [],
    editionPages: number[] = [];
  await page.route("**/api/calibre/hardcover/search", (r) => {
    const input = r.request().postDataJSON();
    bookPages.push(input.page);
    return r.fulfill({
      json: {
        token: "scroll",
        calibreId: 9,
        title: "Libro local",
        authors: ["Autor"],
        page: input.page,
        hasNext: input.page === 1,
        items: Array.from(
          { length: input.page === 1 ? 20 : 5 },
          (_, index) => ({
            id: (input.page === 1 ? 7 : 27) + index,
            slug: `book-${index}`,
            title:
              input.page === 1 ? `Book ${index + 1}` : `More book ${index + 1}`,
            authors: ["Author"],
          }),
        ),
      },
    });
  });
  await page.route("**/api/calibre/hardcover/editions?**", (r) => {
    const params = new URL(r.request().url()).searchParams;
    expect(params.get("language")).toBe("es");
    const at = Number(params.get("page"));
    editionPages.push(at);
    return r.fulfill({
      json: {
        hasNext: at === 1,
        items: Array.from({ length: at === 1 ? 20 : 5 }, (_, index) => ({
          id: (at === 1 ? 11 : 31) + index,
          title:
            at === 1 ? `Edition ${index + 1}` : `More edition ${index + 1}`,
          language: "es",
          publisher: "Publisher",
          isbn10: "",
          isbn13: "",
          asin: "",
        })),
      },
    });
  });
  await page.route("**/api/calibre/hardcover/preview", (r) => {
    const input = r.request().postDataJSON();
    return r.fulfill({
      json: {
        token: "preview",
        calibreId: 9,
        title: "Libro local",
        saved: false,
        values: [
          {
            field: "BOOK_ID",
            identifier: "hardcover_book",
            existing: "",
            value: String(input.bookId),
            state: "READY",
          },
          {
            field: "EDITION_ID",
            identifier: "hardcover_edition",
            existing: "",
            value: input.editionId ? String(input.editionId) : "",
            state: input.editionId ? "READY" : "MISSING",
          },
        ],
      },
    });
  });
  await page.goto("/calibre");
  await page
    .locator("tbody")
    .getByRole("button", { name: "Completar Hardcover", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect(dialog.getByRole("navigation")).toHaveCount(0);
  await dialog
    .getByRole("textbox", { name: "Libro en Hardcover", exact: true })
    .click();
  await page
    .getByRole("option", { name: /^Book 20 ·/ })
    .scrollIntoViewIfNeeded();
  await expect(
    page.getByRole("option", { name: /^More book 5 ·/ }),
  ).toHaveCount(1);
  await page.getByRole("option", { name: /^More book 5 ·/ }).click();
  await expect(
    dialog.getByRole("table", { name: "Previsualizar" }),
  ).toBeVisible();
  await dialog
    .getByRole("textbox", { name: "Edición en Hardcover", exact: true })
    .click();
  await page
    .getByRole("option", { name: /^Edition 20 ·/ })
    .scrollIntoViewIfNeeded();
  await expect(
    page.getByRole("option", { name: /^More edition 5 ·/ }),
  ).toHaveCount(1);
  await page.getByRole("option", { name: /^More edition 5 ·/ }).click();
  await expect(
    dialog.getByRole("textbox", { name: "Libro en Hardcover", exact: true }),
  ).toHaveValue(/More book 5/);
  await expect(
    dialog.getByRole("textbox", {
      name: "Hardcover edition manual",
      exact: true,
    }),
  ).toHaveValue("35");
  expect(bookPages).toEqual([1, 2]);
  expect(editionPages).toEqual([1, 2]);
});

test("manual edition resolves its work and keeps the table at full container width", async ({
  page,
}) => {
  await page.route("**/api/calibre/hardcover", (r) =>
    r.fulfill({
      json: {
        configured: true,
        fields,
        mappings: {
          SLUG: "hardcover",
          BOOK_ID: "hardcover_book",
          EDITION_ID: "hardcover_edition",
        },
      },
    }),
  );
  await page.route("**/api/calibre/hardcover/search", (r) =>
    r.fulfill({
      json: {
        token: "edition",
        calibreId: 9,
        title: "Libro local",
        authors: ["Autor"],
        page: 1,
        hasNext: false,
        items: [
          { id: 7, slug: "old-book", title: "Old book", authors: ["Author"] },
        ],
      },
    }),
  );
  await page.route("**/api/calibre/hardcover/editions?**", (r) =>
    r.fulfill({ json: { items: [], hasNext: false } }),
  );
  await page.route("**/api/calibre/hardcover/resolve-edition", (r) => {
    expect(r.request().postDataJSON()).toEqual({
      token: "edition",
      editionId: 22,
    });
    return r.fulfill({
      json: {
        book: {
          id: 8,
          slug: "new-book",
          title: "New book",
          authors: ["Author"],
        },
        edition: {
          id: 22,
          title: "New edition",
          language: "en",
          publisher: "Publisher",
          isbn10: "",
          isbn13: "",
          asin: "",
        },
      },
    });
  });
  await page.route("**/api/calibre/hardcover/preview", (r) => {
    const input = r.request().postDataJSON();
    return r.fulfill({
      json: {
        token: `preview-${input.bookId}`,
        calibreId: 9,
        title: "Libro local",
        saved: false,
        values: [
          {
            field: "SLUG",
            identifier: "hardcover",
            existing: "",
            value: input.bookId === 7 ? "old-book" : "new-book",
            state: "READY",
          },
          {
            field: "BOOK_ID",
            identifier: "hardcover_book",
            existing: "",
            value: String(input.bookId),
            state: "READY",
          },
          {
            field: "EDITION_ID",
            identifier: "hardcover_edition",
            existing: "",
            value: input.editionId ? String(input.editionId) : "",
            state: input.editionId ? "READY" : "MISSING",
          },
        ],
      },
    });
  });
  await page.goto("/calibre");
  await page
    .locator("tbody")
    .getByRole("button", { name: "Completar Hardcover", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  const table = dialog.getByRole("table", { name: "Previsualizar" });
  await expect(table).toBeVisible();
  await dialog
    .getByRole("button", { name: "Editar edición de Hardcover", exact: true })
    .click();
  const edition = dialog.getByRole("textbox", {
    name: "Hardcover edition manual",
    exact: true,
  });
  await edition.fill("22");
  await expect(
    dialog.getByRole("button", { name: "Guardar en Calibre", exact: true }),
  ).toBeDisabled();
  await dialog
    .getByRole("button", { name: "Comprobar edición", exact: true })
    .click();
  await expect(
    dialog.getByRole("textbox", { name: "Libro en Hardcover", exact: true }),
  ).toHaveValue(/New book/);
  await expect(
    dialog.getByRole("textbox", { name: "Edición en Hardcover", exact: true }),
  ).toHaveValue(/New edition/);
  await expect(
    dialog.getByRole("textbox", {
      name: "Idioma",
      exact: true,
    }),
  ).toHaveValue(/EN/);
  await expect(
    dialog.getByRole("textbox", {
      name: "Hardcover (slug) manual",
      exact: true,
    }),
  ).toHaveValue("new-book");
  await expect(edition).toHaveValue("22");
  await expect(
    table.getByRole("row").filter({ hasText: "hardcover_book" }),
  ).toContainText("8");
  await expect(
    dialog.getByText(
      "Edición seleccionada manualmente mediante su ID de Hardcover.",
      { exact: true },
    ),
  ).toBeVisible();
  const handles = table.locator("th button");
  for (let index = 0; index < 4; index++) {
    await handles.nth(index).focus();
    for (let step = 0; step < 30; step++)
      await handles.nth(index).press("ArrowLeft");
  }
  const size = await table.evaluate((element) => ({
    table: element.getBoundingClientRect().width,
    container: element.parentElement!.clientWidth,
  }));
  expect(size.table).toBeGreaterThanOrEqual(size.container - 1);
});

test("current Calibre identifiers appear before a Hardcover selection and can seed manual lookup", async ({
  page,
}) => {
  const identifiers = {
    hardcover: "existing-book",
    hardcover_book: "8",
    hardcover_edition: "22",
  };
  await page.route("**/api/calibre/hardcover", (r) =>
    r.fulfill({
      json: {
        configured: true,
        fields,
        mappings: {
          SLUG: "hardcover",
          BOOK_ID: "hardcover_book",
          EDITION_ID: "hardcover_edition",
        },
      },
    }),
  );
  await page.route("**/api/calibre/hardcover/search", (r) =>
    r.fulfill({
      json: {
        token: "existing",
        calibreId: 9,
        title: "Libro local",
        authors: ["Autor"],
        page: 1,
        hasNext: false,
        items: [],
        identifiers,
      },
    }),
  );
  await page.route("**/api/calibre/hardcover/resolve-edition", (r) => {
    expect(r.request().postDataJSON()).toEqual({
      token: "existing",
      editionId: 22,
    });
    return r.fulfill({
      json: {
        book: {
          id: 8,
          slug: "existing-book",
          title: "Existing book",
          authors: ["Author"],
        },
        edition: {
          id: 22,
          title: "Existing edition",
          language: "es",
          publisher: "Publisher",
          isbn10: "",
          isbn13: "",
          asin: "",
        },
      },
    });
  });
  await page.route("**/api/calibre/hardcover/editions?**", (r) =>
    r.fulfill({ json: { items: [], hasNext: false } }),
  );
  await page.route("**/api/calibre/hardcover/preview", (r) =>
    r.fulfill({
      json: {
        token: "checked",
        calibreId: 9,
        title: "Libro local",
        saved: false,
        values: [
          {
            field: "SLUG",
            identifier: "hardcover",
            existing: "existing-book",
            value: "existing-book",
            state: "UNCHANGED",
          },
          {
            field: "BOOK_ID",
            identifier: "hardcover_book",
            existing: "8",
            value: "8",
            state: "UNCHANGED",
          },
          {
            field: "EDITION_ID",
            identifier: "hardcover_edition",
            existing: "22",
            value: "22",
            state: "UNCHANGED",
          },
        ],
      },
    }),
  );
  await page.goto("/calibre");
  await page
    .locator("tbody")
    .getByRole("button", { name: "Completar Hardcover", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  const table = dialog.getByRole("table", { name: "Previsualizar" });
  await expect(table).toBeVisible();
  await expect(
    dialog.getByRole("textbox", { name: "Libro en Hardcover", exact: true }),
  ).toHaveValue("");
  await expect(
    table.getByRole("row").filter({ hasText: "hardcover_book" }),
  ).toContainText("8");
  await expect(
    table.getByRole("row").filter({ hasText: "hardcover_edition" }),
  ).toContainText("22");
  await expect(table).toContainText("existing-book");
  await expect(table.getByText("Sin seleccionar", { exact: true })).toHaveCount(
    3,
  );
  await expect(
    dialog.getByRole("button", { name: "Guardar en Calibre", exact: true }),
  ).toHaveCount(0);
  await expect(
    dialog.getByRole("textbox", {
      name: "Hardcover edition manual",
      exact: true,
    }),
  ).toHaveValue("");
  await dialog
    .getByRole("button", { name: "Editar edición de Hardcover", exact: true })
    .click();
  await expect(
    dialog.getByRole("textbox", {
      name: "Hardcover edition manual",
      exact: true,
    }),
  ).toHaveValue("22");
  await dialog
    .getByRole("button", { name: "Comprobar edición", exact: true })
    .click();
  await expect(
    dialog.getByRole("textbox", { name: "Libro en Hardcover", exact: true }),
  ).toHaveValue(/Existing book/);
  await expect(table.getByText("Ya coincide", { exact: true })).toHaveCount(3);
  await expect(
    dialog.getByRole("button", { name: "Guardar en Calibre", exact: true }),
  ).toBeDisabled();
});

test("reselecting a loaded work reuses languages and editions but still checks a new preview", async ({
  page,
}) => {
  await page.route("**/api/calibre/hardcover", (r) =>
    r.fulfill({
      json: {
        configured: true,
        fields,
        mappings: {
          BOOK_ID: "hardcover_book",
          EDITION_ID: "hardcover_edition",
        },
      },
    }),
  );
  await page.route("**/api/calibre/hardcover/search", (r) =>
    r.fulfill({
      json: {
        token: "reuse",
        calibreId: 9,
        title: "Libro local",
        authors: ["Autor"],
        page: 1,
        hasNext: false,
        items: [7, 8].map((id) => ({
          id,
          slug: `book-${id}`,
          title: `Work ${id}`,
          authors: ["Author"],
        })),
      },
    }),
  );
  const languages: number[] = [],
    editions: number[] = [],
    previews: number[] = [];
  await page.route("**/api/calibre/hardcover/edition-languages?**", (r) => {
    languages.push(
      Number(new URL(r.request().url()).searchParams.get("bookId")),
    );
    return r.fulfill({ json: [{ code: "es", name: "Spanish" }] });
  });
  await page.route("**/api/calibre/hardcover/editions?**", (r) => {
    const id = Number(new URL(r.request().url()).searchParams.get("bookId"));
    editions.push(id);
    return r.fulfill({
      json: {
        hasNext: false,
        items: [1, 2].map((at) => ({
          id: id * 10 + at,
          title: `Edition ${id}-${at}`,
          language: "es",
          publisher: "Publisher",
          isbn10: "",
          isbn13: "",
          asin: "",
        })),
      },
    });
  });
  await page.route("**/api/calibre/hardcover/preview", (r) => {
    const input = r.request().postDataJSON();
    previews.push(input.bookId);
    return r.fulfill({
      json: {
        token: `preview-${previews.length}`,
        calibreId: 9,
        title: "Libro local",
        saved: false,
        values: [
          {
            field: "BOOK_ID",
            identifier: "hardcover_book",
            existing: "",
            value: String(input.bookId),
            state: "READY",
          },
          {
            field: "EDITION_ID",
            identifier: "hardcover_edition",
            existing: "",
            value: input.editionId ? String(input.editionId) : "",
            state: input.editionId ? "READY" : "MISSING",
          },
        ],
      },
    });
  });
  await page.goto("/calibre");
  await page
    .locator("tbody")
    .getByRole("button", { name: "Completar Hardcover", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  const work = dialog.getByRole("textbox", {
    name: "Libro en Hardcover",
    exact: true,
  });
  for (const id of [7, 8, 7]) {
    await work.click();
    await page
      .getByRole("option", { name: new RegExp(`^Work ${id} ·`) })
      .click();
    await expect(
      dialog.getByRole("button", { name: "Guardar en Calibre", exact: true }),
    ).toBeEnabled();
  }
  expect(languages).toEqual([7, 8]);
  expect(editions).toEqual([7, 8]);
  expect(previews).toEqual([7, 8, 7]);
  const edition = dialog.getByRole("textbox", {
    name: "Edición en Hardcover",
    exact: true,
  });
  for (const id of [1, 2, 1]) {
    await edition.click();
    await page
      .getByRole("option", { name: new RegExp(`^Edition 7-${id} ·`) })
      .click();
    await expect(
      dialog.getByRole("textbox", {
        name: "Hardcover edition manual",
        exact: true,
      }),
    ).toHaveValue(String(70 + id));
  }
  expect(languages).toEqual([7, 8]);
  expect(editions).toEqual([7, 8]);
  expect(previews).toEqual([7, 8, 7, 7, 7, 7]);
});

test("all three covers follow the selected work and edition while metadata stays cached", async ({
  page,
}) => {
  const png = Buffer.from(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGPQX94AAAJfAVfDJ0G+AAAAAElFTkSuQmCC",
    "base64",
  );
  await page.route("https://assets.hardcover.app/**", (r) =>
    r.fulfill({ contentType: "image/png", body: png }),
  );
  await page.route("**/api/calibre/hardcover/cover?**", (r) =>
    r.fulfill({ contentType: "image/png", body: png }),
  );
  await page.route("**/api/calibre/hardcover", (r) =>
    r.fulfill({
      json: {
        configured: true,
        fields,
        mappings: {
          BOOK_ID: "hardcover_book",
          EDITION_ID: "hardcover_edition",
        },
      },
    }),
  );
  await page.route("**/api/calibre/hardcover/search", (r) =>
    r.fulfill({
      json: {
        token: "covers",
        calibreId: 9,
        title: "Libro local",
        authors: ["Autor"],
        page: 1,
        hasNext: false,
        coverUrl: "/api/calibre/hardcover/cover?token=covers",
        items: [7, 8].map((id) => ({
          id,
          slug: `book-${id}`,
          title: `Work ${id}`,
          authors: ["Author"],
          coverUrl: `https://assets.hardcover.app/book-${id}.png`,
        })),
      },
    }),
  );
  const languages: number[] = [],
    editions: number[] = [],
    previews: number[] = [];
  await page.route("**/api/calibre/hardcover/edition-languages?**", (r) => {
    languages.push(
      Number(new URL(r.request().url()).searchParams.get("bookId")),
    );
    return r.fulfill({ json: [{ code: "es", name: "Spanish" }] });
  });
  await page.route("**/api/calibre/hardcover/editions?**", (r) => {
    const id = Number(new URL(r.request().url()).searchParams.get("bookId"));
    editions.push(id);
    return r.fulfill({
      json: {
        hasNext: false,
        items: [
          {
            id: id * 10 + 1,
            title: `Edition ${id}`,
            language: "es",
            publisher: "Publisher",
            isbn10: "",
            isbn13: "",
            asin: "",
            coverUrl: `https://assets.hardcover.app/edition-${id}.png`,
          },
        ],
      },
    });
  });
  await page.route("**/api/calibre/hardcover/preview", (r) => {
    const input = r.request().postDataJSON();
    previews.push(input.bookId);
    return r.fulfill({
      json: {
        token: `preview-${previews.length}`,
        calibreId: 9,
        title: "Libro local",
        saved: false,
        values: [
          {
            field: "BOOK_ID",
            identifier: "hardcover_book",
            existing: "",
            value: String(input.bookId),
            state: "READY",
          },
          {
            field: "EDITION_ID",
            identifier: "hardcover_edition",
            existing: "",
            value: String(input.editionId),
            state: "READY",
          },
        ],
      },
    });
  });
  await page.goto("/calibre");
  await page
    .locator("tbody")
    .getByRole("button", { name: "Completar Hardcover", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  const current = dialog.locator('img[alt="Portada del libro en Calibre"]');
  await expect(current).toHaveAttribute(
    "src",
    /\/api\/calibre\/hardcover\/cover\?token=covers/,
  );
  const work = dialog.getByRole("textbox", {
    name: "Libro en Hardcover",
    exact: true,
  });
  for (const id of [7, 8, 7]) {
    await work.click();
    await page
      .getByRole("option", { name: new RegExp(`^Work ${id} ·`) })
      .click();
    await expect(
      dialog.locator('img[alt="Portada de la obra en Hardcover"]'),
    ).toHaveAttribute("src", `https://assets.hardcover.app/book-${id}.png`);
    await expect(
      dialog.locator('img[alt="Portada de la edición en Hardcover"]'),
    ).toHaveAttribute("src", `https://assets.hardcover.app/edition-${id}.png`);
    await expect(
      dialog.getByRole("button", { name: "Guardar en Calibre", exact: true }),
    ).toBeEnabled();
  }
  for (const [cover, method] of [
    ["Portada del libro en Calibre", "escape"],
    ["Portada de la obra en Hardcover", "image"],
    ["Portada de la edición en Hardcover", "close"],
    ["Portada de la obra en Hardcover", "background"],
  ]) {
    const trigger = dialog.getByRole("button", {
      name: `Ampliar: ${cover}`,
      exact: true,
    });
    await trigger.click();
    const enlarged = page.getByRole("dialog", { name: cover, exact: true });
    await expect(enlarged).toBeVisible();
    await expect(
      enlarged.getByRole("img", { name: cover, exact: true }),
    ).toBeVisible();
    if (method === "escape") await page.keyboard.press("Escape");
    else if (method === "image")
      await enlarged.getByRole("img", { name: cover, exact: true }).click();
    else if (method === "close")
      await enlarged
        .getByRole("button", { name: "Cerrar", exact: true })
        .click();
    else
      await enlarged
        .locator(".hardcover-cover-expanded")
        .click({ position: { x: 1, y: 1 } });
    await expect(enlarged).toHaveCount(0);
    await expect(dialog).toBeVisible();
    await expect(trigger).toBeFocused();
    await expect(work).toHaveValue(/Work 7/);
    await expect(
      dialog.getByRole("button", { name: "Guardar en Calibre", exact: true }),
    ).toBeEnabled();
  }
  expect(languages).toEqual([7, 8]);
  expect(editions).toEqual([7, 8]);
  expect(previews).toEqual([7, 8, 7]);
  await page.setViewportSize({ width: 480, height: 1000 });
  const overflow = await dialog
    .locator(".hardcover-book-selector, .hardcover-edition-selectors")
    .evaluateAll((rows) =>
      rows.map((row) => row.scrollWidth - row.clientWidth),
    );
  for (const width of overflow) expect(width).toBeLessThan(2);
});

test("failed covers use placeholders without blocking selection or saving", async ({
  page,
}) => {
  await page.route("https://assets.hardcover.app/**", (r) =>
    r.fulfill({ status: 404, body: "" }),
  );
  await page.route("**/api/calibre/hardcover", (r) =>
    r.fulfill({
      json: {
        configured: true,
        fields,
        mappings: { BOOK_ID: "hardcover_book" },
      },
    }),
  );
  await page.route("**/api/calibre/hardcover/search", (r) =>
    r.fulfill({
      json: {
        token: "no-cover",
        calibreId: 9,
        title: "Libro local",
        authors: ["Autor"],
        page: 1,
        hasNext: false,
        items: [
          {
            id: 7,
            slug: "book",
            title: "Only work",
            authors: ["Author"],
            coverUrl: "https://assets.hardcover.app/missing.png",
          },
        ],
      },
    }),
  );
  await page.route("**/api/calibre/hardcover/preview", (r) =>
    r.fulfill({
      json: {
        token: "preview",
        calibreId: 9,
        title: "Libro local",
        saved: false,
        values: [
          {
            field: "BOOK_ID",
            identifier: "hardcover_book",
            existing: "",
            value: "7",
            state: "READY",
          },
        ],
      },
    }),
  );
  await page.goto("/calibre");
  await page
    .locator("tbody")
    .getByRole("button", { name: "Completar Hardcover", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect(
    dialog.locator('img[alt="Portada de la obra en Hardcover"]'),
  ).toHaveCount(0);
  await expect(
    dialog.getByRole("img", {
      name: "Portada de la obra en Hardcover",
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    dialog.getByRole("button", { name: "Guardar en Calibre", exact: true }),
  ).toBeEnabled();
});

test("Calibre cover is not requested without file-read permission", async ({
  page,
}) => {
  await page.route("**/api/auth/me", (r) =>
    r.fulfill({
      json: {
        id: "metadata-editor",
        username: "metadata-editor",
        role: "USER",
        email: "",
        mustChangePassword: false,
        permissions: ["CALIBRE_READ", "CALIBRE_SEND"],
      },
    }),
  );
  await page.route("**/api/calibre/hardcover", (r) =>
    r.fulfill({
      json: {
        configured: true,
        fields,
        mappings: { BOOK_ID: "hardcover_book" },
      },
    }),
  );
  await page.route("**/api/calibre/hardcover/search", (r) =>
    r.fulfill({
      json: {
        token: "private-cover",
        calibreId: 9,
        title: "Libro local",
        authors: ["Autor"],
        page: 1,
        hasNext: false,
        items: [],
        coverUrl: "/api/calibre/hardcover/cover?token=private-cover",
      },
    }),
  );
  let coverRequests = 0;
  await page.route("**/api/calibre/hardcover/cover?**", (r) => {
    coverRequests++;
    return r.fulfill({ status: 403, body: "" });
  });
  await page.goto("/calibre");
  await page
    .locator("tbody")
    .getByRole("button", { name: "Completar Hardcover", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect(
    dialog.getByRole("textbox", { name: "Título", exact: true }),
  ).toHaveValue("Libro local");
  await expect(
    dialog.locator('img[alt="Portada del libro en Calibre"]'),
  ).toHaveCount(0);
  expect(coverRequests).toBe(0);
});

test("missing key links admins directly to the Hardcover settings field", async ({
  page,
}) => {
  await page.route("**/api/calibre/hardcover", (r) =>
    r.fulfill({
      json: {
        configured: false,
        fields,
        mappings: { BOOK_ID: "hardcover_book" },
      },
    }),
  );
  await page.route("**/api/settings/calibre", (r) =>
    r.fulfill({
      json: { url: "", username: "", passwordConfigured: false, library: "" },
    }),
  );
  await page.goto("/calibre");
  await page
    .locator("tbody")
    .getByRole("button", { name: "Completar Hardcover", exact: true })
    .click();
  const link = page
    .getByRole("dialog")
    .getByRole("link", { name: "Ajustes > Calibre > Hardcover", exact: true });
  await expect(link).toHaveAttribute("href", "/settings/calibre#hardcover");
  await link.click();
  await expect(page).toHaveURL(/\/settings\/calibre#hardcover$/);
  await expect(
    page.getByRole("region", { name: "Hardcover", exact: true }),
  ).toBeInViewport();
  await expect(page.getByLabel("API key", { exact: true })).toBeFocused();
});

test("missing key does not offer admin settings links to other users", async ({
  page,
}) => {
  await page.route("**/api/auth/me", (r) =>
    r.fulfill({
      json: {
        id: "editor",
        username: "editor",
        role: "USER",
        permissions: ["CALIBRE_READ", "CALIBRE_SEND"],
      },
    }),
  );
  await page.route("**/api/calibre/hardcover", (r) =>
    r.fulfill({
      json: {
        configured: false,
        fields,
        mappings: { BOOK_ID: "hardcover_book" },
      },
    }),
  );
  await page.goto("/calibre");
  await page
    .locator("tbody")
    .getByRole("button", { name: "Completar Hardcover", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByText(/EPLSYNC_HARDCOVER_API_KEY/)).toBeVisible();
  await expect(
    dialog.getByRole("link", {
      name: "Ajustes > Calibre > Hardcover",
      exact: true,
    }),
  ).toHaveCount(0);
});

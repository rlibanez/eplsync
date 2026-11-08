import { test, expect } from "./fixtures";
test("browses the local library with filters and configurable columns", async ({
  page,
}) => {
  const requests: URL[] = [];
  await page.route("**/api/settings/calibre/snapshot", (r) =>
    r.fulfill({
      json: {
        snapshot: {
          library: "main",
          syncedAt: "2026-10-12T12:00:00Z",
          total: 1,
          identified: 1,
        },
      },
    }),
  );
  await page.route("**/api/calibre/books?**", (r) => {
    const url = new URL(r.request().url());
    requests.push(url);
    return r.fulfill({
      json: {
        items: [
          {
            calibreId: 3,
            title: "El reino del lenguaje",
            authors: "Tom Wolfe",
            hardcover: {
              hardcover: "the-kingdom-of-speech",
              hardcover_book: "113379",
              hardcover_edition: "31145475",
            },
            eplId: 80899,
            revision: 1,
            catalogRevision: 2,
            comparison: "OUTDATED",
            presence: "PRESENT",
          },
        ],
        meta: {
          page: 0,
          size: 20,
          totalItems: 1,
          totalPages: 1,
          hasPrevious: false,
          hasNext: false,
        },
      },
    });
  });
  await page.route("**/api/settings/calibre/sync", (r) =>
    r.fulfill({
      json: {
        library: "main",
        syncedAt: "2026-10-12T12:01:00Z",
        total: 1,
        identified: 1,
      },
    }),
  );
  await page.goto("/calibre");
  await expect(
    page.getByRole("heading", { name: "Biblioteca de Calibre" }),
  ).toBeVisible();
  await expect(page.getByRole("table")).toContainText("Tom Wolfe");
  await expect(page.locator('th[data-table-column="hardcover"]')).toContainText(
    "Hardcover",
  );
  const fields = await page
    .locator("thead th[data-table-column]")
    .evaluateAll((nodes) =>
      nodes.map((n) => n.getAttribute("data-table-column")),
    );
  expect(fields.indexOf("hardcover")).toBe(fields.indexOf("actions") - 1);
  await expect(page.getByRole("table")).toContainText(
    "hardcover_edition:31145475",
  );
  const hardcover = page.getByRole("textbox", {
    name: "Hardcover",
    exact: true,
  });
  await hardcover.click();
  await page.getByRole("option", { name: "Hardcover", exact: true }).click();
  await expect
    .poll(() => requests.at(-1)?.searchParams.get("hardcover"))
    .toBe("SLUG");
  await hardcover.click();
  await page.getByRole("option", { name: "Hardcover ID", exact: true }).click();
  await expect
    .poll(() => requests.at(-1)?.searchParams.get("hardcover"))
    .toBe("SLUG,BOOK_ID");
  await hardcover.click();
  await page
    .getByRole("option", { name: "Hardcover edition", exact: true })
    .click();
  await expect
    .poll(() => requests.at(-1)?.searchParams.get("hardcover"))
    .toBe("SLUG,BOOK_ID,EDITION_ID");
  await page.keyboard.press("Escape");
  const hardcoverFilter = hardcover.locator(
    "xpath=ancestor::div[contains(@class, 'mantine-MultiSelect-root')]",
  );
  await hardcoverFilter
    .getByRole("button", { name: "Incluir valores seleccionados" })
    .click();
  await expect
    .poll(() => requests.at(-1)?.searchParams.get("hardcoverOperator"))
    .toBe("ne");
  await page
    .getByRole("textbox", { name: "Revisión Hardcover", exact: true })
    .click();
  await page
    .getByRole("option", { name: "Sin correspondencia", exact: true })
    .click();
  await expect
    .poll(() => requests.at(-1)?.searchParams.get("hardcoverReview"))
    .toBe("NO_MATCH");
  expect(requests.at(-1)?.searchParams.get("hardcoverOperator")).toBe("ne");
  expect(requests.at(-1)?.searchParams.get("hardcoverReviewOperator")).toBe(
    "eq",
  );
  expect(requests.at(-1)?.searchParams.get("hardcover")).toBe(
    "SLUG,BOOK_ID,EDITION_ID",
  );
  await page.keyboard.press("Escape");
  const hardcoverHeading = page.locator('th[data-table-column="hardcover"]');
  await hardcoverHeading.getByRole("button").first().click();
  await expect
    .poll(() => requests.at(-1)?.searchParams.getAll("sort"))
    .toContain("hardcover,asc");
  await hardcoverHeading.getByRole("button").first().click();
  await expect
    .poll(() => requests.at(-1)?.searchParams.getAll("sort"))
    .toContain("hardcover,desc");

  await expect(page.locator('th[data-table-column="actions"]')).toContainText(
    "Acciones",
  );
  await expect(
    page.getByRole("link", { name: "El reino del lenguaje", exact: true }),
  ).toHaveAttribute("href", "/catalog/80899");
  await expect(page.locator("tbody .book-cover")).toHaveCount(1);
  await expect(page.getByRole("columnheader").nth(2)).toContainText(
    "Calibre ID",
  );
  await page
    .getByRole("textbox", { name: "EPL ID", exact: true })
    .fill("80899");
  await page
    .getByRole("textbox", { name: "EPL ID", exact: true })
    .press("Enter");
  await expect
    .poll(() => requests.at(-1)?.searchParams.get("eplId"))
    .toBe("80899");
  await page.getByLabel("Libro", { exact: true }).fill("reino");
  await expect
    .poll(() => requests.at(-1)?.searchParams.get("title"))
    .toBe("reino");

  await expect(
    page.getByRole("columnheader", { name: /Calibre ID/i }),
  ).toHaveCount(1);
  await expect(
    page.getByRole("textbox", { name: "Calibre ID", exact: true }),
  ).toHaveCount(0);
  await page.getByLabel("Autor", { exact: true }).fill("Wolfe");
  await expect
    .poll(() => requests.at(-1)?.searchParams.get("author"))
    .toBe("Wolfe");
  await page.getByRole("button", { name: "Limpiar", exact: true }).click();
  await expect(page.getByLabel("Autor", { exact: true })).toHaveValue("");
  await page.getByRole("button", { name: "Columnas", exact: true }).click();
  await page
    .getByRole("checkbox", { name: "Calibre ID", exact: true })
    .uncheck();
  await expect(
    page.getByRole("columnheader", { name: /Calibre ID/i }),
  ).toHaveCount(0);
  await page.keyboard.press("Escape");
  await page
    .getByRole("button", { name: "Sincronizar biblioteca", exact: true })
    .click();
  // The view must not emit a second local confirmation alongside the backend notice.
  await expect(
    page.getByText("Biblioteca sincronizada.", { exact: true }),
  ).toHaveCount(0);
  await page.evaluate(() =>
    (
      window as unknown as { __event: (name: string, data: unknown) => void }
    ).__event("operation-notice", {
      id: 0,
      category: "JOB",
      action: "CALIBRE_SYNC",
      outcome: "SUCCEEDED",
      origin: "MANUAL",
      operationId: "library-sync",
      details: { total: 1 },
      actor: { id: "test-admin", kind: "USER" },
    }),
  );
  await expect(
    page.locator(".notification-toasts").getByRole("status"),
  ).toHaveCount(1);
  await expect(page.locator(".notification-toasts")).toContainText(
    "Completado",
  );
});

test("read-only Calibre users can browse without catalog links or synchronization", async ({
  page,
}) => {
  await page.route("**/api/auth/me", (r) =>
    r.fulfill({
      json: {
        id: "reader",
        username: "reader",
        email: "reader@example.org",
        role: "USER",
        mustChangePassword: false,
        permissions: ["CALIBRE_READ"],
      },
    }),
  );
  await page.route("**/api/settings/calibre/snapshot", (r) =>
    r.fulfill({ json: { snapshot: null } }),
  );
  await page.route("**/api/calibre/books?**", (r) =>
    r.fulfill({
      json: {
        items: [
          {
            calibreId: 3,
            title: "Book",
            authors: "Author",
            eplId: 1,
            revision: 1,
            catalogRevision: 1,
            comparison: "CURRENT",
            presence: "PRESENT",
          },
        ],
        meta: {
          page: 0,
          size: 20,
          totalItems: 1,
          totalPages: 1,
          hasPrevious: false,
          hasNext: false,
        },
      },
    }),
  );
  await page.goto("/calibre");
  await expect(page.getByRole("table")).toContainText("Book");
  await expect(page.locator('th[data-table-column="actions"]')).toContainText(
    "Acciones",
  );
  await expect(
    page.getByRole("link", { name: "Ver en Calibre", exact: true }),
  ).toHaveAttribute("href", "/api/calibre/books/3/open");
  await expect(
    page.getByRole("button", { name: "Descargar EPUB", exact: true }),
  ).toHaveCount(0);
  await expect(page.locator("table").getByRole("checkbox")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Sincronizar biblioteca", exact: true }),
  ).toHaveCount(0);
  await expect(page.getByRole("link", { name: "1", exact: true })).toHaveCount(
    0,
  );
});

test("formats are visible, sortable and provide quick filters", async ({
  page,
}) => {
  await page.route("**/api/settings/calibre/snapshot", (r) =>
    r.fulfill({ json: { snapshot: null } }),
  );
  await page.route("**/api/calibre/books/formats", (r) =>
    r.fulfill({ json: ["epub", "pdf"] }),
  );
  await page.route("**/api/calibre/books?**", (r) =>
    r.fulfill({
      json: {
        items: [
          {
            calibreId: 1,
            title: "Formats book",
            authors: "Author",
            eplId: 1,
            revision: 1,
            catalogRevision: 1,
            comparison: "CURRENT",
            presence: "PRESENT",
            formats: ["epub", "pdf"],
          },
        ],
        meta: {
          page: 0,
          size: 20,
          totalItems: 1,
          totalPages: 1,
          hasPrevious: false,
          hasNext: false,
        },
      },
    }),
  );
  await page.goto("/calibre");
  const column = page.locator('th[data-table-column="formats"]');
  await expect(column).toBeVisible();
  const sorted = page.waitForRequest(
    (r) =>
      r.url().includes("/api/calibre/books?") &&
      new URL(r.url()).searchParams.getAll("sort").includes("formats,asc"),
  );
  await column.click();
  await sorted;
  const filtered = page.waitForRequest(
    (r) =>
      r.url().includes("/api/calibre/books?") &&
      new URL(r.url()).searchParams.get("formats") === "pdf",
  );
  await page
    .locator("tbody")
    .getByRole("button", { name: "PDF", exact: true })
    .click();
  await filtered;
  await expect(
    page
      .getByRole("textbox", { name: "Extensión", exact: true })
      .locator(
        "xpath=ancestor::*[contains(@class,'mantine-MultiSelect-root')]",
      ),
  ).toContainText("PDF");
});

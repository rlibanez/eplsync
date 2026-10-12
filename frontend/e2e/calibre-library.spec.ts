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
  await expect(page.getByRole("link", {name: "El reino del lenguaje", exact: true})).toHaveAttribute("href", "/catalog/80899");
  await expect(page.locator("tbody .book-cover")).toHaveCount(1);
  await expect(page.getByRole("columnheader").first()).toContainText("Calibre ID");
  await page.getByLabel("EPL ID", {exact:true}).fill("80899");
  await expect.poll(()=>requests.at(-1)?.searchParams.get("eplId")).toBe("80899");
  await page.getByLabel("Libro", {exact:true}).fill("reino");
  await expect.poll(()=>requests.at(-1)?.searchParams.get("title")).toBe("reino");

  await expect(
    page.getByRole("columnheader", { name: /Calibre ID/i }),
  ).toHaveCount(1);
  await expect(page.getByRole("textbox", {name: "Calibre ID", exact: true})).toHaveCount(0);
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
  await expect(
    page.getByText("Biblioteca sincronizada.", { exact: true }),
  ).toContainText("Biblioteca sincronizada");
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
  await expect(
    page.getByRole("button", { name: "Sincronizar biblioteca", exact: true }),
  ).toHaveCount(0);
  await expect(page.getByRole("link", { name: "1", exact: true })).toHaveCount(
    0,
  );
});

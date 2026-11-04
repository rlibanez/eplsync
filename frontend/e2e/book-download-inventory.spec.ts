import { test, expect } from "./fixtures";
import type { Page } from "@playwright/test";

async function setup(
  page: Page,
  permissions = ["CATALOG_READ", "BOOK_HISTORY_READ"],
) {
  await page.route("**/api/auth/me", (r) =>
    r.fulfill({
      json: {
        id: "reader",
        username: "reader",
        role: "USER",
        permissions,
        mustChangePassword: false,
      },
    }),
  );
  await page.route("**/api/catalog/books/1", (r) =>
    r.fulfill({
      json: {
        eplId: 1,
        title: "Libro",
        author: "Autor",
        revision: 1,
        language: "es",
        coverAvailable: false,
        download: { items: [], totalItems: 0, statuses: [] },
      },
    }),
  );
  await page.route("**/api/catalog/books/1/magnets", (r) =>
    r.fulfill({ json: [] }),
  );
  await page.route("**/api/catalog/books/1/activity?**", (r) =>
    r.fulfill({
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
  await page.route("**/api/catalog/books/1/history?**", (r) =>
    r.fulfill({
      json: {
        items: [
          {
            id: "first",
            revision: 1,
            status: "DOWNLOADED",
            client: "qbittorrent",
            hash: "A".repeat(40),
            inventoryState: "CHECKED",
            inventoryCheckedAt: "2026-11-04T10:00:00Z",
            fileExtensions: ["epub", "pdf"],
          },
          {
            id: "second",
            revision: 1,
            status: "DOWNLOADED",
            client: "qbittorrent",
            hash: "B".repeat(40),
            inventoryState: "CHECKED",
            inventoryCheckedAt: "2026-11-04T10:00:00Z",
            fileExtensions: ["cbr"],
          },
          {
            id: "pending",
            revision: 1,
            status: "SUBMITTED",
            client: "qbittorrent",
            hash: "C".repeat(40),
            inventoryState: "PENDING",
            inventoryCheckedAt: null,
            fileExtensions: [],
          },
          {
            id: "failed",
            revision: 1,
            status: "DOWNLOADED",
            client: "qbittorrent",
            hash: "D".repeat(40),
            inventoryState: "ERROR",
            inventoryCheckedAt: null,
            fileExtensions: [],
          },
        ],
        meta: {
          page: 0,
          size: 20,
          totalItems: 4,
          totalPages: 1,
          hasNext: false,
          hasPrevious: false,
        },
      },
    }),
  );
}

test("history inventory is local, paginated, scoped per torrent and readable without file access", async ({
  page,
}) => {
  await setup(page);
  const calls: string[] = [];
  page.on("request", (r) => {
    if (r.url().includes("/api/torrent/")) calls.push(r.url());
  });
  await page.route("**/api/catalog/books/1/history/*/files?**", (r) => {
    const url = new URL(r.request().url()),
      index = Number(url.searchParams.get("page")),
      size = Number(url.searchParams.get("size"));
    const first = url.pathname.includes("/first/"),
      total = first ? 25 : 1;
    return r.fulfill({
      json: {
        inventoryState: "CHECKED",
        inventoryCheckedAt: "2026-11-04T10:00:00Z",
        items: Array.from(
          { length: Math.min(size, total - index * size) },
          (_, n) => ({
            index: index * size + n,
            name: first
              ? `folder/source-${index * size + n}.epub`
              : "other/comic.cbr",
            extension: first ? "epub" : "cbr",
            size: 1024,
          }),
        ),
        meta: {
          page: index,
          size,
          totalItems: total,
          totalPages: Math.ceil(total / size),
          hasNext: (index + 1) * size < total,
          hasPrevious: index > 0,
        },
      },
    });
  });
  await page.goto("/catalog/1");
  const history = page.locator(".book-history");
  const header = history.locator('th[data-table-column="fileExtensions"]');
  await expect(header).toBeVisible();
  await expect(history.locator("tbody tr").first()).toContainText("EPUB · PDF");
  const sorted = page.waitForRequest(
    (r) =>
      r.url().includes("/api/catalog/books/1/history?") &&
      new URL(r.url()).searchParams
        .getAll("sort")
        .includes("fileExtensions,asc"),
  );
  await header.click();
  await sorted;
  await expect(
    history.getByRole("button", { name: "Descargar EPUB", exact: true }),
  ).toHaveCount(0);
  await history
    .getByRole("button", { name: "Ver archivos", exact: true })
    .nth(0)
    .click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByText("folder/source-0.epub", { exact: true }),
  ).toBeVisible();
  await expect(dialog.locator("tbody tr")).toHaveCount(20);
  await dialog.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect(dialog.locator("tbody tr")).toHaveCount(5);
  await expect(
    dialog.getByText("folder/source-20.epub", { exact: true }),
  ).toBeVisible();
  await dialog.getByRole("button", { name: "Cerrar", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(header).toContainText("Extensiones");
  await history
    .getByRole("button", { name: "Ver archivos", exact: true })
    .nth(1)
    .click();
  await expect(
    page.getByRole("dialog").getByText("other/comic.cbr", { exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("dialog")).not.toContainText("folder/source-");
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect(calls).toEqual([]);
});

test("inventory shows pending checks and cached files after errors", async ({
  page,
}) => {
  await setup(page);
  await page.route("**/api/catalog/books/1/history/*/files?**", (r) => {
    const pending = new URL(r.request().url()).pathname.includes("/pending/");
    return r.fulfill({
      json: {
        inventoryState: pending ? "PENDING" : "ERROR",
        inventoryCheckedAt: pending ? null : "2026-11-04T10:00:00Z",
        items: pending
          ? []
          : [
              {
                index: 0,
                name: "folder/<script>book.epub",
                extension: "epub",
                size: 2048,
              },
            ],
        meta: {
          page: 0,
          size: 20,
          totalItems: pending ? 0 : 1,
          totalPages: pending ? 0 : 1,
          hasNext: false,
          hasPrevious: false,
        },
      },
    });
  });
  await page.goto("/catalog/1");
  const buttons = page
    .locator(".book-history")
    .getByRole("button", { name: "Ver archivos", exact: true });
  await buttons.nth(2).click();
  await expect(page.getByRole("dialog").getByRole("status")).toContainText(
    "Sin comprobar",
  );
  await expect(page.getByRole("dialog").locator("table")).toHaveCount(0);
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Cerrar", exact: true })
    .click();
  await buttons.nth(3).click();
  await expect(page.getByRole("dialog").getByRole("status")).toContainText(
    "Error de comprobación. Se muestra el último inventario guardado.",
  );
  await expect(
    page
      .getByRole("dialog")
      .getByText("folder/<script>book.epub", { exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("dialog").locator("tbody script")).toHaveCount(0);
});

test("catalog readers without history permission see neither inventory nor history requests", async ({
  page,
}) => {
  await setup(page, ["CATALOG_READ"]);
  const calls: string[] = [];
  page.on("request", (r) => {
    if (r.url().includes("/history")) calls.push(r.url());
  });
  await page.goto("/catalog/1");
  await expect(
    page.getByRole("heading", { name: "Libro", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".book-history")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Ver archivos", exact: true }),
  ).toHaveCount(0);
  expect(calls).toEqual([]);
});

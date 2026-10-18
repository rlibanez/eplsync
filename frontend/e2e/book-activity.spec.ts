import { test, expect } from "./fixtures";

async function book(page: import("@playwright/test").Page) {
  await page.route("**/api/catalog/books/1", (route) =>
    route.fulfill({
      json: {
        eplId: 1,
        title: "Libro",
        author: "Autor",
        revision: 1,
        language: "es",
        coverAvailable: false,
        download: { items: [], totalItems: 0, statuses: [] },
        calibre: { totalItems: 0, items: [] },
      },
    }),
  );
  await page.route("**/api/catalog/books/1/magnets", (route) =>
    route.fulfill({ json: [] }),
  );
}
const row = (
  id: string,
  operation: string,
  result: string,
  href: string | null,
) => ({
  id,
  createdAt: "2026-10-19T00:00:00Z",
  operation,
  result,
  revision: "1",
  message: "Detalle conservado",
  href,
});

test("book activity follows current sections and preserves sorting between pages", async ({
  page,
}) => {
  await book(page);
  const requests: URLSearchParams[] = [];
  await page.route("**/api/catalog/books/1/activity?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    requests.push(params);
    const index = Number(params.get("page") ?? 0);
    return route.fulfill({
      json: {
        items: [
          row("calibre", "CALIBRE_SEND", "FAILED", "/calibre/jobs/send"),
          row(
            "cleanup",
            "DOWNLOAD_DELETE",
            "SUCCEEDED",
            "/downloads/jobs/cleanup/remove",
          ),
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
  await page.goto("/catalog/1");
  const activity = page.getByRole("region", {
    name: "Actividad del libro",
    exact: true,
  });
  await expect(activity).toBeVisible();
  await expect(activity).not.toContainText(
    "Operaciones y resultados de los registros y trabajos conservados.",
  );
  await expect(activity.locator(".filters")).toHaveCount(0);
  const controls = activity.locator(".table-toolbar > .catalog-table-controls");
  await expect(controls).toBeVisible();
  const controlBox = await controls.boundingBox();
  const sectionBox = await activity.boundingBox();
  expect(controlBox!.x).toBeGreaterThan(sectionBox!.x + sectionBox!.width / 2);

  await expect(
    page.locator(".detail-related-sections > section").last(),
  ).toHaveClass(/book-activity/);
  await expect(page.locator(".book-history")).toHaveCount(1);
  await expect(page.locator(".book-calibre")).toHaveCount(1);
  await expect(
    activity.getByRole("link", { name: "Ver trabajo" }).first(),
  ).toHaveAttribute("href", "/calibre/jobs/send");
  await expect(
    activity.getByRole("link", { name: "Ver trabajo" }).last(),
  ).toHaveAttribute("href", "/downloads/jobs/cleanup/remove");
  await activity
    .getByRole("button", { name: "Siguiente", exact: true })
    .click();
  await expect.poll(() => requests.at(-1)?.get("page")).toBe("1");
  expect(requests.at(-1)?.get("operation")).toBeNull();
  expect(requests.at(-1)?.get("result")).toBeNull();
  await activity.getByRole("button", { name: "Fecha", exact: true }).click();
  await expect.poll(() => requests.at(-1)?.get("page")).toBe("0");
  expect(requests.at(-1)?.get("sort")).toBe("createdAt,asc");
});

test("catalog-only users do not request activity or see a section", async ({
  page,
}) => {
  await page.route("**/api/auth/me", (r) =>
    r.fulfill({
      json: {
        id: "reader",
        username: "reader",
        role: "USER",
        permissions: ["CATALOG_READ"],
        mustChangePassword: false,
      },
    }),
  );
  await book(page);
  let requests = 0;
  await page.route("**/api/catalog/books/1/activity?**", (route) => {
    requests++;
    return route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
      },
    });
  });
  await page.goto("/catalog/1");
  await expect(
    page.getByRole("heading", { name: "Libro", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".book-activity")).toHaveCount(0);
  expect(requests).toBe(0);
});

test("read-only history permissions hide unauthorized work links", async ({
  page,
}) => {
  await page.route("**/api/auth/me", (r) =>
    r.fulfill({
      json: {
        id: "reader",
        username: "reader",
        role: "USER",
        permissions: ["CATALOG_READ", "BOOK_HISTORY_READ"],
        mustChangePassword: false,
      },
    }),
  );
  await book(page);
  await page.route("**/api/catalog/books/1/activity?**", (route) =>
    route.fulfill({
      json: {
        items: [row("completed", "DOWNLOAD_COMPLETED", "SUCCEEDED", null)],
        meta: {
          page: 0,
          size: 20,
          totalItems: 1,
          totalPages: 1,
          hasNext: false,
          hasPrevious: false,
        },
      },
    }),
  );
  await page.goto("/catalog/1");
  const activity = page.locator(".book-activity");
  await expect(activity).toContainText("Descarga completada");
  await expect(activity.getByRole("link", { name: "Ver trabajo" })).toHaveCount(
    0,
  );
  await expect(activity.locator(".filters")).toHaveCount(0);
});

test("activity errors remain within the new section and can be retried", async ({
  page,
}) => {
  await book(page);
  let failed = true;
  await page.route("**/api/catalog/books/1/activity?**", (route) =>
    failed
      ? route.fulfill({
          status: 503,
          json: { details: "Consulta temporalmente no disponible" },
        })
      : route.fulfill({
          json: {
            items: [],
            meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
          },
        }),
  );
  await page.goto("/catalog/1");
  const activity = page.locator(".book-activity");
  await expect(activity.getByRole("alert")).toBeVisible();
  await expect(page.locator(".book-history")).toBeVisible();
  failed = false;
  await activity
    .getByRole("button", { name: "Reintentar", exact: true })
    .click();
  await expect(activity).toContainText(
    "No hay actividad disponible con estos filtros.",
  );
});

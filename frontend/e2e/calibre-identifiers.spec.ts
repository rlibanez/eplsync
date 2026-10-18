import { test, expect } from "./fixtures";
async function library(page: import("@playwright/test").Page) {
  await page.route("**/api/settings/calibre/snapshot", (r) =>
    r.fulfill({ json: { snapshot: null } }),
  );
  await page.route("**/api/calibre/books?**", (r) =>
    r.fulfill({
      json: {
        items: [1, 2].map((id) => ({
          calibreId: id,
          title: `Libro ${id}`,
          authors: "Autora",
          eplId: null,
          revision: null,
          comparison: "UNKNOWN",
          presence: "UNIDENTIFIED",
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
}
test("previews original download links and fills only selected traceable entries", async ({
  page,
}) => {
  await library(page);
  const applied: unknown[] = [];
  await page.route("**/api/calibre/identifiers/preview", (r) => {
    const id = r.request().postDataJSON().calibreId;
    return r.fulfill({
      json: {
        token: id === 1 ? "token" : null,
        calibreId: id,
        title: `Libro ${id}`,
        eplId: id === 1 ? 85481 : null,
        revision: id === 1 ? "1.1" : null,
        allowed: id === 1,
        message:
          id === 1
            ? "Contenido comprobado con descarga original"
            : "No se conserva un envío original verificable",
      },
    });
  });
  await page.route("**/api/calibre/identifiers/apply", (r) => {
    applied.push(r.request().postDataJSON());
    return r.fulfill({
      json: {
        token: "token",
        calibreId: 1,
        title: "Libro 1",
        eplId: 85481,
        revision: "1.1",
        allowed: false,
        message: "Identificadores completados",
      },
    });
  });
  await page.goto("/calibre");
  await page
    .getByRole("checkbox", { name: "Seleccionar registros de esta página" })
    .check();
  await page
    .locator(".selection-toolbar-actions")
    .getByRole("button", { name: "Completar identificadores", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByRole("group", { name: "Libros", exact: true }),
  ).toBeVisible();
  await expect(
    dialog.getByRole("table", { name: "Libros", exact: true }),
  ).toBeVisible();
  await expect(
    dialog.getByRole("columnheader", { name: "Calibre ID", exact: true }),
  ).toBeVisible();
  await expect(
    dialog.getByRole("columnheader", { name: "Resultado", exact: true }),
  ).toHaveCSS("position", "sticky");
  await expect(dialog).toContainText(
    "No se conserva un envío original verificable",
  );
  await expect(
    dialog.getByRole("checkbox", {
      name: "Seleccionar Calibre ID 1",
      exact: true,
    }),
  ).toBeChecked();
  await expect(
    dialog.getByRole("checkbox", {
      name: "Seleccionar Calibre ID 2",
      exact: true,
    }),
  ).toBeDisabled();
  await expect(
    dialog.getByRole("button", {
      name: "Completar seleccionados",
      exact: true,
    }),
  ).toBeEnabled();
  await page.screenshot({
    path: "/tmp/eplsync-identifiers-list-desktop.png",
    fullPage: true,
    animations: "disabled",
  });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "/tmp/eplsync-identifiers-preview.png",
    fullPage: true,
    animations: "disabled",
  });
  await dialog
    .getByRole("button", { name: "Completar seleccionados", exact: true })
    .click();
  await expect(dialog.getByRole("status")).toContainText(
    "Identificadores completados",
  );
  expect(applied).toEqual([
    { token: "token", operationId: expect.any(String) },
  ]);
  await expect(
    dialog.getByRole("button", {
      name: "Completar seleccionados",
      exact: true,
    }),
  ).toBeDisabled();
});
test("failed metadata changes retain their preview token for safe explicit retry", async ({
  page,
}) => {
  await library(page);
  let calls = 0;
  await page.route("**/api/calibre/identifiers/preview", (r) =>
    r.fulfill({
      json: {
        token: "same-token",
        calibreId: 1,
        title: "Libro 1",
        eplId: 1,
        revision: "1",
        allowed: true,
        message: "Comprobado",
      },
    }),
  );
  await page.route("**/api/calibre/identifiers/apply", (r) => {
    expect(r.request().postDataJSON()).toEqual({
      token: "same-token",
      operationId: expect.any(String),
    });
    calls++;
    return calls === 1
      ? r.fulfill({
          status: 503,
          json: { details: "Calibre no está disponible temporalmente" },
        })
      : r.fulfill({
          json: {
            token: "same-token",
            calibreId: 1,
            title: "Libro 1",
            eplId: 1,
            revision: "1",
            allowed: false,
            message: "Identificadores verificados",
          },
        });
  });
  await page.goto("/calibre");
  await page
    .getByRole("checkbox", { name: "Seleccionar Calibre ID 1", exact: true })
    .check();
  await page
    .locator(".selection-toolbar-actions")
    .getByRole("button", { name: "Completar identificadores", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog
    .getByRole("button", { name: "Completar seleccionados", exact: true })
    .click();
  await expect(dialog).toContainText(
    "Calibre no está disponible temporalmente",
  );
  expect(calls).toBe(1);
  await dialog
    .getByRole("button", { name: "Completar seleccionados", exact: true })
    .click();
  await expect(dialog.getByRole("status")).toContainText(
    "Identificadores verificados",
  );
  expect(calls).toBe(2);
});
test("read-only library accounts cannot complete identifiers", async ({
  page,
}) => {
  await library(page);
  await page.route("**/api/auth/me", (r) =>
    r.fulfill({
      json: {
        id: "reader",
        username: "reader",
        role: "USER",
        permissions: ["CALIBRE_READ", "CALIBRE_FILES_READ"],
        mustChangePassword: false,
      },
    }),
  );
  await page.goto("/calibre");
  await page
    .getByRole("checkbox", { name: "Seleccionar registros de esta página" })
    .check();
  await expect(
    page.getByRole("button", {
      name: "Completar identificadores",
      exact: true,
    }),
  ).toHaveCount(0);
});

test("manual imports show their origin per book before and after completion", async ({
  page,
}) => {
  await library(page);
  await page.route("**/api/calibre/identifiers/preview", (r) => {
    const id = r.request().postDataJSON().calibreId;
    return r.fulfill({
      json: {
        token: id === 1 ? "external" : null,
        calibreId: id,
        title: `Libro ${id}`,
        eplId: id === 1 ? 85481 : null,
        revision: id === 1 ? "1.1" : null,
        allowed: id === 1,
        message:
          id === 1
            ? "Importación ajena a EPL Sync: correspondencia inequívoca"
            : "Importación ajena a EPL Sync: correspondencia no inequívoca; distintas identidades",
      },
    });
  });
  await page.route("**/api/calibre/identifiers/apply", (r) => {
    expect(r.request().postDataJSON()).toEqual({
      token: "external",
      operationId: expect.any(String),
    });
    return r.fulfill({
      json: {
        token: "external",
        calibreId: 1,
        title: "Libro 1",
        eplId: 85481,
        revision: "1.1",
        allowed: false,
        message: "Importación ajena a EPL Sync: identificadores completados",
      },
    });
  });
  await page.goto("/calibre");
  await page
    .getByRole("checkbox", { name: "Seleccionar registros de esta página" })
    .check();
  await page
    .locator(".selection-toolbar-actions")
    .getByRole("button", { name: "Completar identificadores", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.locator("tbody tr").filter({ hasText: "Libro 1" }),
  ).toContainText("Importación ajena a EPL Sync");
  await expect(
    dialog.locator("tbody tr").filter({ hasText: "Libro 2" }),
  ).toContainText("distintas identidades");
  await expect(
    dialog.getByRole("checkbox", {
      name: "Seleccionar Calibre ID 2",
      exact: true,
    }),
  ).toBeDisabled();
  await dialog
    .getByRole("button", { name: "Completar seleccionados", exact: true })
    .click();
  await expect(dialog.getByRole("status")).toContainText(
    "Importación ajena a EPL Sync",
  );
});

test("preview can be cancelled and closed without processing remaining books or accepting late results", async ({
  page,
}) => {
  await library(page);
  let calls = 0;
  let release!: () => void;
  const blocked = new Promise<void>((resolve) => {
    release = resolve;
  });
  const cancelled: string[] = [];
  let firstOperation = "";
  await page.route("**/api/calibre/identifiers/cancel", (route) => {
    cancelled.push(route.request().postDataJSON().operationId);
    return route.fulfill({ json: { cancelled: true } });
  });
  await page.route("**/api/calibre/identifiers/preview", async (route) => {
    const body = route.request().postDataJSON();
    const first = ++calls === 1;
    if (first) {
      firstOperation = body.operationId;
      await blocked;
    }
    await route
      .fulfill({
        json: {
          token: `token-${body.calibreId}`,
          calibreId: body.calibreId,
          title: `Libro ${body.calibreId}`,
          eplId: body.calibreId,
          revision: "1",
          allowed: true,
          message: first
            ? "Resultado antiguo que no debe reaparecer"
            : "Comprobación nueva",
        },
      })
      .catch(() => undefined);
  });
  await page.goto("/calibre");
  await page
    .getByRole("checkbox", { name: "Seleccionar registros de esta página" })
    .check();
  await page
    .locator(".selection-toolbar-actions")
    .getByRole("button", { name: "Completar identificadores", exact: true })
    .click();
  await expect.poll(() => calls).toBe(1);
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByRole("button", { name: "Cerrar", exact: true }),
  ).toHaveCount(0);
  await dialog.getByRole("button", { name: "Cancelar", exact: true }).click();
  await expect.poll(() => cancelled).toEqual([firstOperation]);
  await expect(dialog).toHaveCount(0);
  await page
    .locator(".selection-toolbar-actions")
    .getByRole("button", { name: "Completar identificadores", exact: true })
    .click();
  await expect(
    dialog.getByText("Comprobación nueva", { exact: true }),
  ).toHaveCount(2);
  release();
  await expect(dialog).not.toContainText("Resultado antiguo");
  expect(calls).toBe(3);
});

test("closing with Escape cancels an active preview", async ({ page }) => {
  await library(page);
  let started = false,
    cancelled = false;
  let release!: () => void;
  const blocked = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/calibre/identifiers/preview", async (route) => {
    started = true;
    await blocked;
    await route.fulfill({ json: {} }).catch(() => undefined);
  });
  await page.route("**/api/calibre/identifiers/cancel", (route) => {
    cancelled = true;
    return route.fulfill({ json: { cancelled: true } });
  });
  await page.goto("/calibre");
  await page
    .getByRole("checkbox", { name: "Seleccionar Calibre ID 1", exact: true })
    .check();
  await page
    .locator(".selection-toolbar-actions")
    .getByRole("button", { name: "Completar identificadores", exact: true })
    .click();
  await expect.poll(() => started).toBe(true);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect.poll(() => cancelled).toBe(true);
  release();
});

test("closing during application cancels the remaining work", async ({
  page,
}) => {
  await library(page);
  let applies = 0,
    release!: () => void;
  const blocked = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/calibre/identifiers/preview", (route) => {
    const id = route.request().postDataJSON().calibreId;
    return route.fulfill({
      json: {
        token: `token-${id}`,
        calibreId: id,
        title: `Libro ${id}`,
        eplId: id,
        revision: "1",
        allowed: true,
        message: "Comprobado",
      },
    });
  });
  await page.route("**/api/calibre/identifiers/cancel", (route) =>
    route.fulfill({ json: { cancelled: true } }),
  );
  await page.route("**/api/calibre/identifiers/apply", async (route) => {
    const token = route.request().postDataJSON().token;
    applies++;
    if (token === "token-2") await blocked;
    await route
      .fulfill({
        json: {
          token,
          calibreId: token === "token-1" ? 1 : 2,
          title: token === "token-1" ? "Libro 1" : "Libro 2",
          eplId: 1,
          revision: "1",
          allowed: false,
          message: "Identificadores comprobados",
        },
      })
      .catch(() => undefined);
  });
  await page.goto("/calibre");
  await page
    .getByRole("checkbox", { name: "Seleccionar registros de esta página" })
    .check();
  await page
    .locator(".selection-toolbar-actions")
    .getByRole("button", { name: "Completar identificadores", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByText("Comprobado", { exact: true })).toHaveCount(2);
  await dialog
    .getByRole("button", { name: "Completar seleccionados", exact: true })
    .click();
  await expect.poll(() => applies).toBe(2);
  await expect(
    dialog.locator("tbody tr").filter({ hasText: "Libro 1" }),
  ).toContainText("Identificadores comprobados");
  await dialog.getByRole("button", { name: "Cancelar", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  release();
});

test("row action opens the shared assistant for that book without changing the selection", async ({
  page,
}) => {
  await library(page);
  const previews: number[] = [];
  await page.route("**/api/calibre/identifiers/preview", (route) => {
    const id = route.request().postDataJSON().calibreId;
    previews.push(id);
    return route.fulfill({
      json: {
        token: "row-token",
        calibreId: id,
        title: `Libro ${id}`,
        eplId: 27253,
        revision: "1.1",
        allowed: true,
        message: "Importación ajena a EPL Sync: correspondencia inequívoca",
      },
    });
  });
  await page.route("**/api/calibre/identifiers/apply", (route) => {
    expect(route.request().postDataJSON()).toEqual({
      token: "row-token",
      operationId: expect.any(String),
    });
    return route.fulfill({
      json: {
        token: "row-token",
        calibreId: 2,
        title: "Libro 2",
        eplId: 27253,
        revision: "1.1",
        allowed: false,
        message: "Identificadores completados",
      },
    });
  });
  await page.goto("/calibre");
  const row = page.getByRole("row").filter({ hasText: "Libro 2" });
  await row
    .getByRole("button", { name: "Completar identificadores", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText("Libro 2");
  expect(previews).toEqual([2]);
  await dialog
    .getByRole("button", { name: "Completar seleccionados", exact: true })
    .click();
  await expect(dialog.getByRole("status")).toHaveText(
    "Identificadores completados",
  );
  await dialog.getByRole("button", { name: "Cerrar", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(
    row.getByRole("checkbox", {
      name: "Seleccionar Calibre ID 2",
      exact: true,
    }),
  ).not.toBeChecked();
});

test("identifier table keeps selection across pages and applies only eligible checked books", async ({
  page,
}) => {
  await library(page);
  const books = Array.from({ length: 25 }, (_, index) => ({
    calibreId: index + 1,
    title: `Libro ${index + 1}`,
    authors: "Autora",
    eplId: null,
    revision: null,
    comparison: "UNKNOWN",
    presence: "UNIDENTIFIED",
  }));
  await page.route("**/api/calibre/books?**", (route) => {
    const params = new URL(route.request().url()).searchParams,
      p = Number(params.get("page") ?? 0),
      size = Number(params.get("size") ?? 20);
    return route.fulfill({
      json: {
        items: books.slice(p * size, (p + 1) * size),
        meta: {
          page: p,
          size,
          totalItems: 25,
          totalPages: Math.ceil(25 / size),
          hasNext: (p + 1) * size < 25,
          hasPrevious: p > 0,
        },
      },
    });
  });
  await page.route("**/api/calibre/identifiers/preview", (route) => {
    const id = route.request().postDataJSON().calibreId;
    return route.fulfill({
      json: {
        token: id === 25 ? null : `token-${id}`,
        calibreId: id,
        title: `Libro ${id}`,
        eplId: id === 25 ? null : id,
        revision: id === 25 ? null : "1",
        allowed: id !== 25,
        message:
          id === 25 ? "Sin correspondencia" : "Correspondencia inequívoca",
      },
    });
  });
  const applied: number[] = [];
  await page.route("**/api/calibre/identifiers/apply", (route) => {
    const id = Number(route.request().postDataJSON().token.split("-")[1]);
    applied.push(id);
    return route.fulfill({
      json: {
        token: `token-${id}`,
        calibreId: id,
        title: `Libro ${id}`,
        eplId: id,
        revision: "1",
        allowed: false,
        message: "Identificadores completados",
      },
    });
  });
  await page.goto("/calibre");
  await page
    .getByRole("button", { name: "Seleccionar todo (25)", exact: true })
    .click();
  await page
    .locator(".selection-toolbar-actions")
    .getByRole("button", { name: "Completar identificadores", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  const pager = dialog.getByRole("navigation", {
    name: "Páginas de la previsualización",
  });
  const check = (id: number) =>
    dialog.getByRole("checkbox", {
      name: `Seleccionar Calibre ID ${id}`,
      exact: true,
    });
  await expect(
    dialog.getByRole("button", {
      name: "Completar seleccionados",
      exact: true,
    }),
  ).toBeEnabled();
  await expect(pager).toContainText("1 / 2");
  await expect(dialog.locator("tbody tr")).toHaveCount(20);
  expect(
    await dialog
      .locator(".identifier-preview-table")
      .evaluate((e) => e.scrollHeight > e.clientHeight),
  ).toBe(true);
  await expect(dialog).toContainText("24 libros seleccionados");
  await dialog
    .getByRole("button", { name: "Quitar selección (24)", exact: true })
    .click();
  await expect(dialog).toContainText("0 libros seleccionados");
  await dialog
    .getByRole("button", { name: "Seleccionar disponibles", exact: true })
    .click();
  await expect(dialog).toContainText("24 libros seleccionados");
  await dialog.getByRole("button", { name: "Calibre ID", exact: true }).click();
  await dialog.getByRole("button", { name: "Calibre ID", exact: true }).click();
  await expect(dialog.locator("tbody tr").first()).toContainText("Libro 25");
  await expect(check(25)).toBeDisabled();
  await expect(check(24)).toBeChecked();
  await dialog.getByRole("button", { name: "Calibre ID", exact: true }).click();
  await check(1).uncheck();
  await pager.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect(dialog.locator("tbody tr")).toHaveCount(5);
  await expect(
    dialog.locator("tbody tr").last().locator("[data-row-number]"),
  ).toHaveText("25");
  await check(21).uncheck();
  await expect(check(25)).toBeDisabled();
  await pager.getByRole("button", { name: "Anterior", exact: true }).click();
  await expect(check(1)).not.toBeChecked();
  await expect(check(2)).toBeChecked();
  await pager.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect(check(21)).not.toBeChecked();
  await dialog
    .getByRole("button", { name: "Completar seleccionados", exact: true })
    .click();
  await expect.poll(() => applied.length).toBe(22);
  expect(applied).toEqual(
    books
      .map((book) => book.calibreId)
      .filter((id) => ![1, 21, 25].includes(id)),
  );
  await expect(
    dialog.getByRole("button", {
      name: "Completar seleccionados",
      exact: true,
    }),
  ).toBeDisabled();
  await pager.getByRole("button", { name: "Anterior", exact: true }).click();
  await expect(check(1)).not.toBeChecked();
  await expect(check(2)).toBeDisabled();
});

import { expect, test } from "./fixtures";

const source = "https://covers.example/32.jpg";
const fallback = "https://images.epublibre.org/libros/32.jpg";
const options = {
  connectTimeoutMs: 3000,
  requestTimeoutMs: 3000,
  batchTimeoutMs: 4000,
  concurrency: 4,
};
const initialBook = {
  eplId: 32,
  title: "Dune",
  author: "Frank Herbert",
  revision: 1,
  language: "es",
  coverUrl: source,
  coverAvailable: null as boolean | null,
  download: { items: [] },
};
const summary = {
  dryRun: false,
  checked: 100,
  available: 80,
  unavailable: 15,
  inconclusive: 5,
  wouldChange: 95,
  updated: 95,
  items: [],
};
function result(available: boolean | null, reason: string) {
  return {
    ...summary,
    checked: 1,
    items: [
      {
        eplId: 32,
        coverUrl: source,
        previousAvailable: null,
        available,
        reason,
        httpStatus: available === null ? null : available ? 200 : 404,
        updated: available !== null,
        wouldChange: available !== null,
      },
    ],
  };
}

test.beforeEach(async ({ page }) => {
  await page.route("**/api/ui/config", (r) =>
    r.fulfill({ json: { defaultLanguage: "es" } }),
  );
  await page.route("**/api/catalog/covers/config", (r) =>
    r.fulfill({ json: options }),
  );
  await page.route("**/api/catalog/covers/task", (r) =>
    r.fulfill({ json: { task: null } }),
  );
  await page.route("**/api/catalog/books/32", (r) =>
    r.fulfill({ json: initialBook }),
  );
  await page.route("**/api/catalog/books/32/magnets", (r) =>
    r.fulfill({ json: [] }),
  );
  for (const url of [source, fallback])
    await page.route(url, (r) =>
      r.fulfill({
        contentType: "image/svg+xml",
        body: '<svg xmlns="http://www.w3.org/2000/svg" width="130" height="175"><rect width="130" height="175" fill="teal"/></svg>',
      }),
    );
});

test("cover settings fit desktop and mobile", async ({ page }) => {
  await page.goto("/settings/covers");
  await expect(
    page.getByLabel("Tiempo por portada (segundos)", { exact: true }),
  ).toBeVisible();
  const groupInput = await page
    .getByLabel("Tiempo por grupo (segundos)", { exact: true })
    .boundingBox();
  const concurrencyInput = await page
    .getByLabel("Comprobaciones simultáneas", { exact: true })
    .boundingBox();
  expect(groupInput!.y).toBeCloseTo(concurrencyInput!.y, 0);
  await page.screenshot({
    path: "test-results/cover-settings-desktop.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(async () => {
      const box = await page.locator("#sidebar").boundingBox();
      return box ? box.x + box.width : 0;
    })
    .toBeLessThanOrEqual(0);
  await expect(
    page.getByRole("button", { name: "Comprobar catálogo", exact: true }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "test-results/cover-settings-mobile.png",
    fullPage: true,
  });
});

test("404 activates alternative cover without reloading the document", async ({
  page,
}) => {
  let book = { ...initialBook };
  await page.route("**/api/catalog/books/32", (r) => r.fulfill({ json: book }));
  await page.route("**/api/catalog/covers/check?*", (r) => {
    expect(r.request().method()).toBe("POST");
    const url = new URL(r.request().url());
    expect(url.searchParams.get("eplId")).toBe("32");
    expect(url.searchParams.get("onlyUnchecked")).toBe("false");
    book = { ...book, coverAvailable: false };
    return r.fulfill({ json: result(false, "NOT_FOUND") });
  });
  await page.goto("/catalog/32");
  await page.evaluate(
    () => (document.body.dataset.documentMarker = "retained"),
  );
  await page
    .getByRole("button", { name: "Reparar portada", exact: true })
    .click();
  await expect(page.locator(".detail-cover img")).toHaveAttribute(
    "src",
    fallback,
  );
  await expect(
    page.getByText("Se ha activado la portada alternativa."),
  ).toBeVisible();
  await page.screenshot({
    path: "test-results/book-cover-layout.png",
    fullPage: true,
  });
  const header = await page.locator(".detail-header").boundingBox();
  const cover = await page.locator(".detail-cover").boundingBox();
  expect(cover!.height).toBeGreaterThanOrEqual(300);
  expect(cover!.height).toBeCloseTo(header!.height, 0);
  await expect(
    page.getByText("Se ha activado la portada alternativa."),
  ).toBeHidden({ timeout: 7000 });
  await page
    .getByRole("button", { name: "Reparar portada", exact: true })
    .click();
  await page
    .getByRole("status")
    .getByRole("button", { name: "Cerrar notificación", exact: true })
    .click();
  await expect(
    page.getByText("Se ha activado la portada alternativa."),
  ).toBeHidden();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect(await page.evaluate(() => document.body.dataset.documentMarker)).toBe(
    "retained",
  );
});

test("timeout explains uncertainty and closing preserves the original cover", async ({
  page,
}) => {
  let forced = 0;
  await page.route("**/api/catalog/covers/check?*", (r) =>
    r.fulfill({ json: result(null, "TIMEOUT") }),
  );
  await page.route("**/api/catalog/covers/32/alternative", (r) => {
    forced++;
    return r.abort();
  });
  await page.goto("/catalog/32");
  await page
    .getByRole("button", { name: "Reparar portada", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText(
    "no respondió dentro del tiempo permitido",
  );
  await expect(dialog).not.toContainText("epublibre");
  await dialog.getByRole("button", { name: "Cerrar", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.locator(".detail-cover img")).toHaveAttribute(
    "src",
    source,
  );
  expect(forced).toBe(0);
});

for (const available of [null, true]) {
  test(`explicit alternative works after ${available === null ? "timeout" : "available response"}`, async ({
    page,
  }) => {
    let book = { ...initialBook };
    await page.route("**/api/catalog/books/32", (r) =>
      r.fulfill({ json: book }),
    );
    await page.route("**/api/catalog/covers/check?*", (r) => {
      book = { ...book, coverAvailable: available };
      return r.fulfill({
        json: result(available, available ? "AVAILABLE" : "TIMEOUT"),
      });
    });
    await page.route("**/api/catalog/covers/32/alternative", (r) => {
      expect(r.request().postDataJSON()).toEqual({ expectedCoverUrl: source });
      book = { ...book, coverAvailable: false };
      return r.fulfill({ json: { eplId: 32, coverAvailable: false } });
    });
    await page.goto("/catalog/32");
    await page
      .getByRole("button", { name: "Reparar portada", exact: true })
      .click();
    await page
      .getByRole("dialog")
      .getByRole("button", { name: "Usar portada alternativa" })
      .click();
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expect(page.locator(".detail-cover img")).toHaveAttribute(
      "src",
      fallback,
    );
  });
}

test("failed manual override is visible and is not retried", async ({
  page,
}) => {
  let attempts = 0;
  await page.route("**/api/catalog/covers/check?*", (r) =>
    r.fulfill({ json: result(null, "TIMEOUT") }),
  );
  await page.route("**/api/catalog/covers/32/alternative", (r) => {
    attempts++;
    return r.fulfill({ status: 409, json: { code: "COVER_CHANGED" } });
  });
  await page.goto("/catalog/32");
  await page
    .getByRole("button", { name: "Reparar portada", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Usar portada alternativa" })
    .click();
  await expect(
    page.locator(".notification-toasts").getByRole("alert"),
  ).toContainText("No se ha podido confirmar el cambio");
  expect(attempts).toBe(1);
  await expect(page.locator(".detail-cover img")).toHaveAttribute(
    "src",
    source,
  );
});

test("settings run uses entered options and survives navigation and reload", async ({
  page,
}) => {
  let task: null | Record<string, unknown> = null;
  let starts = 0;
  await page.route("**/api/catalog/covers/task", (r) => {
    if (r.request().method() === "POST") {
      starts++;
      expect(r.request().postDataJSON()).toEqual({
        dryRun: false,
        onlyUnchecked: false,
        options: { ...options, concurrency: 8 },
      });
      task = {
        id: "task-1",
        state: "RUNNING",
        dryRun: false,
        onlyUnchecked: false,
        options: { ...options, concurrency: 8 },
        checked: 25,
        total: 100,
        summary: null,
        error: null,
      };
      return r.fulfill({ status: 202, json: task });
    }
    return r.fulfill({ json: { task } });
  });
  await page.goto("/settings/covers");
  await expect(
    page.getByLabel("Tiempo por portada (segundos)", { exact: true }),
  ).toHaveValue("3");
  await page
    .getByLabel("Comprobaciones simultáneas", { exact: true })
    .fill("8");
  await page
    .getByRole("button", { name: "Comprobar catálogo", exact: true })
    .click();
  await expect(page.getByText("25 de 100 libros comprobados")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Comprobar catálogo", exact: true }),
  ).toBeDisabled();
  await page.getByRole("link", { name: "General", exact: true }).click();
  await expect(
    page.getByRole("link", { name: /Comprobando portadas/ }),
  ).toBeVisible();
  await page.reload();
  await page.getByRole("link", { name: /Comprobando portadas/ }).click();
  await expect(
    page.getByLabel("Comprobaciones simultáneas", { exact: true }),
  ).toHaveValue("8");
  task = { ...task!, state: "COMPLETED", checked: 100, summary };
  await expect(
    page.getByRole("heading", { name: "Comprobación completada" }),
  ).toBeVisible({ timeout: 8000 });
  await expect(page.locator(".cover-summary")).toContainText(
    "Cambios guardados",
  );
  await expect(page.locator(".cover-summary")).toContainText("95");
  expect(starts).toBe(1);
});

test("settings validate time order and can submit preview mode", async ({
  page,
}) => {
  await page.route("**/api/catalog/covers/task", (r) =>
    r.request().method() === "GET"
      ? r.fulfill({ json: { task: null } })
      : (expect(r.request().postDataJSON().dryRun).toBe(true),
        expect(r.request().postDataJSON().onlyUnchecked).toBe(true),
        r.fulfill({
          status: 202,
          json: {
            id: "preview",
            state: "COMPLETED",
            dryRun: true,
            options,
            checked: 100,
            total: 100,
            summary: { ...summary, dryRun: true, updated: 0 },
            error: null,
          },
        })),
  );
  await page.goto("/settings/covers");
  await page
    .getByLabel("Tiempo por portada (segundos)", { exact: true })
    .fill("10");
  await expect(
    page.getByRole("button", { name: "Comprobar catálogo", exact: true }),
  ).toBeDisabled();
  await page
    .getByRole("button", { name: "Restaurar valores del servidor" })
    .click();
  await page.getByLabel("Incluir portadas ya revisadas").uncheck();
  await page.getByLabel("Solo comprobar, sin guardar cambios").check();
  await page
    .getByRole("button", { name: "Comprobar catálogo", exact: true })
    .click();
  await expect(
    page.getByText("Modo prueba: no se guardan cambios."),
  ).toBeVisible();
});

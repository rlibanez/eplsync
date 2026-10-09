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

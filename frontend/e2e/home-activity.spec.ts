import { test, expect } from "./fixtures";
async function setup(page: import("@playwright/test").Page) {
  await page.route("**/api/catalog/books?**", (r) =>
    r.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 10, totalItems: 0, totalPages: 0 },
      },
    }),
  );
  await page.route("**/api/home/summary", (r) =>
    r.fulfill({
      json: {
        catalog: { total: 0 },
        downloads: { total: 0, byStatus: {} },
        jobs: { byStatus: {} },
      },
    }),
  );
  await page.route("**/api/events/operations?**", (r) =>
    r.fulfill({ json: { items: [], total: 0 } }),
  );
}
for (const width of [1440, 390])
  test(`operational home sections show local issues and schedule before Events at ${width}px`, async ({
    page,
  }) => {
    await setup(page);
    await page.setViewportSize({ width, height: 950 });
    await page.route("**/api/home/attention", (r) =>
      r.fulfill({
        json: {
          items: [
            {
              id: "cleanup-id",
              source: "downloads",
              kind: "cleanup",
              state: "COMPLETED",
              message: "Un envío a Calibre impide el borrado",
              updatedAt: "2026-10-18T10:00:00Z",
              href: "/downloads/jobs/cleanup/cleanup-id",
            },
            {
              id: "calibre-id",
              source: "calibre",
              kind: "normal",
              state: "PAUSED",
              message: "El envío requiere confirmación",
              updatedAt: "2026-10-18T09:00:00Z",
              href: "/calibre/jobs/calibre-id",
            },
          ],
          total: 14,
        },
      }),
    );
    await page.route("**/api/home/tasks", (r) =>
      r.fulfill({
        json: {
          items: [
            {
              id: "task",
              name: "Actualizar descargas de madrugada",
              operation: "TORRENT_AUTO_UPDATE",
              enabled: true,
              running: false,
              nextRun: "2026-10-19T03:00:00Z",
              lastRun: "2026-10-18T03:00:00Z",
              lastResult: "SUCCEEDED",
            },
            {
              id: "disabled",
              name: "Catálogo desactivado",
              operation: "CATALOG_IMPORT",
              enabled: false,
              running: false,
              nextRun: null,
              lastRun: null,
              lastResult: null,
            },
          ],
          total: 2,
        },
      }),
    );
    await page.goto("/");
    const attention = page.getByRole("region", { name: "Requieren atención" });
    const tasks = page.getByRole("region", { name: "Tareas programadas" });
    await expect(attention).toContainText(
      "Un envío a Calibre impide el borrado",
    );
    await expect(attention).toContainText("Mostrando 2 de 14.");
    await expect(attention.getByRole("link").first()).toHaveAttribute(
      "href",
      "/downloads/jobs/cleanup/cleanup-id",
    );
    await expect(tasks).toContainText("Actualizar descargas de madrugada");
    await expect(tasks).toContainText("Próxima ejecución");
    await expect(tasks).toContainText("Último resultado");
    await expect(tasks).toContainText("Desactivada");
    await expect(tasks.getByRole("link").first()).toHaveAttribute(
      "href",
      "/settings/tasks",
    );
    const headings = await page
      .locator(".home-dashboard > section > .home-section-title h2")
      .allTextContents();
    expect(headings.slice(-3)).toEqual([
      "Requieren atención",
      "Tareas programadas",
      "Últimos eventos",
    ]);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    const facts = await tasks
      .locator(".home-task-facts > div")
      .evaluateAll((elements) =>
        elements.map((element) => {
          const label = element.querySelector("dt")!;
          const value = element.querySelector("dd")!;
          const range = document.createRange();
          range.selectNodeContents(value);
          return {
            label: label.getBoundingClientRect().left,
            value: range.getBoundingClientRect().left,
            align: getComputedStyle(value).textAlign,
          };
        }),
      );
    for (const fact of facts) {
      expect(fact.align).toBe("left");
      expect(Math.abs(fact.label - fact.value)).toBeLessThan(1);
    }
    if (width >= 1200) {
      const columns = await tasks
        .locator(".home-task-list > li")
        .evaluateAll((rows) =>
          rows.map((row) =>
            Array.from(
              row.querySelectorAll(
                ".home-task-name, .mantine-Badge-root, .home-task-facts > div",
              ),
              (cell) => cell.getBoundingClientRect().left,
            ),
          ),
        );
      for (const row of columns.slice(1))
        row.forEach((left, index) =>
          expect(Math.abs(left - columns[0][index])).toBeLessThan(1),
        );
      for (const row of await tasks.locator(".home-task-list > li").all()) {
        const box = await row.boundingBox();
        expect(box!.height).toBeLessThan(100);
        const cells = await row
          .locator(".home-task-name, .home-task-facts > div")
          .evaluateAll((elements) =>
            elements.map((el) => el.getBoundingClientRect().top),
          );
        expect(Math.max(...cells) - Math.min(...cells)).toBeLessThan(30);
      }
    }
    await page.screenshot({
      path: `/tmp/eplsync-home-activity-${width}.png`,
      fullPage: true,
    });
  });
test("new sections can be disabled, reordered and persist independently", async ({
  page,
}) => {
  await setup(page);
  let reads = 0;
  await page.route("**/api/home/attention", (r) => {
    reads++;
    return r.fulfill({ json: { items: [], total: 0 } });
  });
  await page.goto("/settings/home");
  const rows = page.locator(".home-section-settings > li");
  await expect(rows).toHaveCount(8);
  await expect(rows.nth(5).getByRole("checkbox")).toHaveAccessibleName(
    "Requieren atención",
  );
  await expect(rows.nth(6).getByRole("checkbox")).toHaveAccessibleName(
    "Tareas programadas",
  );
  await expect(rows.last().getByRole("checkbox")).toHaveAccessibleName(
    "Últimos eventos",
  );
  await page
    .getByRole("button", { name: "Subir Tareas programadas", exact: true })
    .click();
  await page
    .getByRole("checkbox", { name: "Requieren atención", exact: true })
    .uncheck();
  await page.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect(page.locator(".notification-toasts")).toContainText(
    "Ajustes guardados.",
  );
  await page.reload();
  await expect(rows.nth(5).getByRole("checkbox")).toHaveAccessibleName(
    "Tareas programadas",
  );
  await expect(
    page.getByRole("checkbox", { name: "Requieren atención", exact: true }),
  ).not.toBeChecked();
  await page.goto("/");
  await expect(
    page.getByRole("region", { name: "Tareas programadas" }),
  ).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Requieren atención" }),
  ).toHaveCount(0);
  expect(reads).toBe(0);
});
test("catalog readers never request or see operational sections", async ({
  page,
}) => {
  await setup(page);
  let reads = 0;
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
  await page.route(/\/api\/home\/(attention|tasks)$/, (r) => {
    reads++;
    return r.fulfill({ json: { items: [], total: 0 } });
  });
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "Novedades", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Requieren atención" }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("region", { name: "Tareas programadas" }),
  ).toHaveCount(0);
  expect(reads).toBe(0);
});

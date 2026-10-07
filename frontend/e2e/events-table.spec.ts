import { checkTableControls } from "./table-controls";
import { test, expect, operationResponse } from "./fixtures";

test.beforeEach(async ({ page }) => {
  await page.route("**/api/ui/config", (r) =>
    r.fulfill({ json: { defaultLanguage: "es" } }),
  );
  await page.route("**/api/catalog/covers/task", (r) =>
    r.fulfill({ json: { task: null } }),
  );
  const entries = [
    { id: 1, category: "CATALOG", outcome: "SUCCEEDED", origin: "MANUAL" },
    { id: 2, category: "CATALOG", outcome: "FAILED", origin: "SCHEDULED" },
    { id: 3, category: "TORRENT", outcome: "SUCCEEDED", origin: "MANUAL" },
  ].map((entry) => ({
    ...entry,
    action: entry.id === 3 ? "SYNC" : "UPDATE",
    createdAt: "2026-10-01T10:00:00Z",
    operationId: "long-operation-".repeat(20) + entry.id,
    details: { message: "Un resumen muy largo ".repeat(100) },
  }));
  await page.route("**/api/events/operations?**", (r) =>
    r.fulfill({ json: operationResponse(entries, r.request().url()) }),
  );
  await page.goto("/events");
  await expect(page.locator(".events-table tbody tr")).toHaveCount(3);
});

test("origin selection and clickable cells combine filters and clear together", async ({
  page,
}) => {
  await page.getByRole("textbox", { name: "Origen", exact: true }).click();
  await page.getByRole("option", { name: "Programado", exact: true }).click();
  await expect(page.locator(".events-table tbody tr")).toHaveCount(1);
  await page.getByRole("button", { name: "Limpiar", exact: true }).click();
  await expect(page.locator(".events-table tbody tr")).toHaveCount(3);
  await page
    .getByRole("button", {
      name: "Filtrar por Resultado: Completado",
      exact: true,
    })
    .first()
    .click();
  await expect(page.locator(".events-table tbody tr")).toHaveCount(2);
  await page
    .getByRole("button", { name: "Filtrar por Origen: Manual", exact: true })
    .first()
    .click();
  await expect(
    page.getByRole("textbox", { name: "Origen", exact: true }),
  ).toHaveValue("Manual");
  await page
    .getByRole("button", {
      name: "Filtrar por Categoría: Catálogo",
      exact: true,
    })
    .first()
    .click();
  await expect(page.locator(".events-table tbody tr")).toHaveCount(1);
  await expect(
    page.getByRole("textbox", { name: "Categoría", exact: true }),
  ).toHaveValue("Catálogo");
  await page.getByRole("button", { name: "Limpiar", exact: true }).click();
  await expect(page.locator(".events-table tbody tr")).toHaveCount(3);
});

test("summary does not shift columns and widths can be resized and persist", async ({
  page,
}) => {
  await page.setViewportSize({ width: 2560, height: 1400 });
  const table = page.locator(".events-table");
  await expect
    .poll(async () =>
      table.evaluate((node) =>
        Math.abs(
          node.getBoundingClientRect().width - node.parentElement!.clientWidth,
        ),
      ),
    )
    .toBeLessThan(1);
  const headers = page.locator(".events-table th");
  const geometry = () =>
    headers.evaluateAll((nodes) =>
      nodes.map((node) => {
        const rect = node.getBoundingClientRect();
        return { x: rect.x, width: rect.width };
      }),
    );
  const before = await geometry();
  await page
    .locator(".events-table summary")
    .first()
    .click({ position: { x: 10, y: 10 } });
  expect(await geometry()).toEqual(before);
  const handle = page.getByRole("button", {
    name: "Ajustar ancho de Inicio",
    exact: true,
  });
  const box = (await handle.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 + 80, box.y + box.height / 2, {
    steps: 5,
  });
  await page.mouse.up();
  expect((await geometry())[0].width).toBeCloseTo(before[0].width + 80, 0);
  await handle.focus();
  await page.keyboard.press("ArrowLeft");
  expect((await geometry())[0].width).toBeCloseTo(before[0].width + 60, 0);
  await page.reload();
  await expect(page.locator(".events-table tbody tr")).toHaveCount(3);
  expect((await geometry())[0].width).toBeCloseTo(before[0].width + 60, 0);
});

test("event cell filters its action without leaving events and clears with other filters", async ({
  page,
}) => {
  await page
    .getByRole("button", {
      name: "Filtrar por Evento: Actualización del catálogo",
      exact: true,
    })
    .first()
    .click();
  await expect(page).toHaveURL(/\/events$/);
  await expect(page.locator(".events-table tbody tr")).toHaveCount(2);
  await expect(
    page.getByRole("textbox", { name: "Evento", exact: true }),
  ).toHaveValue("Actualización del catálogo");
  await page.getByRole("button", { name: "Limpiar", exact: true }).click();
  await expect(page.locator(".events-table tbody tr")).toHaveCount(3);
});

test("event table supports criteria and configurable columns", async ({
  page,
}) => {
  await checkTableControls(
    page,
    "finishedAt",
    "Fin",
    page.locator(".events-panel"),
  );
});

import { test, expect, emitEvent, operationResponse } from "./fixtures";
test.beforeEach(async ({ page }) => {
  await page.route("**/api/ui/config", (r) =>
    r.fulfill({ json: { defaultLanguage: "es" } }),
  );
  await page.route("**/api/catalog/covers/task", (r) =>
    r.fulfill({ json: { task: null } }),
  );
});
const started = {
  id: 1,
  createdAt: "2026-10-02T14:00:00Z",
  operationId: "one",
  category: "CATALOG",
  outcome: "STARTED",
  origin: "MANUAL",
  action: "UPDATE",
  details: {},
};
test("one row per operation stays frozen until refresh and summary exposes timeline", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1920, height: 1080 });
  const entries = [started];
  await page.route("**/api/events/operations?**", (r) =>
    r.fulfill({ json: operationResponse(entries, r.request().url()) }),
  );
  await page.goto("/events");
  const rows = page.locator(".events-table tbody tr");
  await expect(rows).toHaveCount(1);
  await expect(rows).toContainText("En ejecución");
  const before = await rows.boundingBox();
  const completed = {
    ...started,
    id: 2,
    outcome: "SUCCEEDED",
    createdAt: "2026-10-02T14:01:15Z",
    details: { created: 1234, durationMs: 75000 },
  };
  entries.push(completed);
  await emitEvent(page, completed);
  await expect(
    page.getByRole("button", { name: "Actualizar tabla" }),
  ).toBeVisible();
  await expect(rows).toContainText("En ejecución");
  expect((await rows.boundingBox())!.y).toBe(before!.y);
  await page.getByRole("button", { name: "Actualizar tabla" }).click();
  await expect(rows).toHaveCount(1);
  await expect(rows).toContainText("Completado");
  await expect(rows).toContainText("1 min 15 s");
  await rows.locator("summary").click({ position: { x: 10, y: 10 } });
  await expect(rows.locator(".event-timeline li")).toHaveCount(2);
  await expect(rows.locator(".event-timeline li").first()).toContainText(
    "Iniciado",
  );
  await expect(rows.locator(".event-timeline li").last()).toContainText(
    "Completado",
  );
  await page.screenshot({
    path: "test-results/event-operation-summary.png",
    fullPage: true,
  });
  await page.evaluate(() =>
    Object.defineProperty(navigator, "clipboard", {
      value: {
        writeText: async (text: string) => {
          (window as unknown as { copied: string }).copied = text;
        },
      },
      configurable: true,
    }),
  );
  await rows.getByRole("button", { name: "Copiar detalles" }).click();
  await expect(rows.getByRole("status")).toHaveText("Detalles copiados.");
  expect(
    await page.evaluate(() => (window as unknown as { copied: string }).copied),
  ).toContain("1 min 15 s");
  await page.evaluate(() => {
    Object.defineProperty(navigator, "clipboard", {
      value: undefined,
      configurable: true,
    });
    Object.defineProperty(document, "execCommand", {
      value: (command: string) => {
        (window as unknown as { copied: string }).copied = (
          document.activeElement as HTMLTextAreaElement
        ).value;
        return command === "copy";
      },
      configurable: true,
    });
  });
  await rows.getByRole("button", { name: "Copiar detalles" }).click();
  await expect(rows.getByRole("status")).toHaveText("Detalles copiados.");
  expect(
    await page.evaluate(() => (window as unknown as { copied: string }).copied),
  ).toContain("Iniciado");
  await page.evaluate(() =>
    Object.defineProperty(document, "execCommand", {
      value: () => false,
      configurable: true,
    }),
  );
  await rows.getByRole("button", { name: "Copiar detalles" }).click();
  await expect(rows.getByRole("status")).toContainText("No se pudieron copiar");
});

test("filters page and scroll survive leaving, returning and reload while data refreshes", async ({
  page,
}) => {
  const entries = Array.from({ length: 45 }, (_, i) => ({
    ...started,
    id: i + 1,
    operationId: String(i + 1),
    createdAt: new Date(Date.UTC(2026, 9, 2, 10, i)).toISOString(),
    outcome: "SUCCEEDED",
    origin: "SCHEDULED",
  }));
  const requests: URLSearchParams[] = [];
  await page.route("**/api/events/operations?**", (r) => {
    requests.push(new URL(r.request().url()).searchParams);
    return r.fulfill({ json: operationResponse(entries, r.request().url()) });
  });
  await page.goto("/events");
  await page
    .getByRole("button", {
      name: "Filtrar por Origen: Programado",
      exact: true,
    })
    .first()
    .click();
  await page.getByRole("button", { name: "2", exact: true }).click();
  await expect.poll(() => requests.at(-1)?.get("page")).toBe("1");
  await expect(page.locator(".events-table tbody tr")).toHaveCount(20);
  await page.evaluate(() => window.scrollTo(0, 450));
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(450);
  await page
    .locator("#sidebar")
    .getByRole("link", { name: "Ajustes", exact: true })
    .click();
  await expect(
    page.getByRole("switch", { name: "Inicios de ejecución", exact: true }),
  ).toBeVisible();
  entries.push({
    ...entries[0],
    id: 46,
    operationId: "new",
    createdAt: "2026-10-02T18:00:00Z",
  });
  await page.goBack();
  await expect(
    page.getByRole("textbox", { name: "Origen", exact: true }),
  ).toHaveValue("Programado");
  await expect.poll(() => requests.at(-1)?.get("page")).toBe("1");
  await expect.poll(() => requests.at(-1)?.has("snapshot")).toBe(false);
  await expect(page.locator(".events-panel")).toContainText("46 ejecuciones");
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(450);
  await page.reload();
  await expect(page.locator(".events-panel")).toContainText("46 ejecuciones");
  await expect.poll(() => requests.at(-1)?.get("page")).toBe("1");
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(450);
  await page
    .locator("#sidebar")
    .getByRole("link", { name: "Ajustes", exact: true })
    .click();
  await expect(
    page.getByRole("switch", { name: "Inicios de ejecución", exact: true }),
  ).toBeVisible();
  await page
    .locator("#sidebar")
    .getByRole("link", { name: "Eventos", exact: true })
    .click();
  await expect(
    page.getByRole("textbox", { name: "Origen", exact: true }),
  ).toHaveValue("Programado");
  await expect.poll(() => requests.at(-1)?.get("page")).toBe("1");
});

test("notification preferences apply immediately and persist without hiding journal entries", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1920, height: 1080 });
  await page.goto("/settings/general");
  await page.locator(".notification-settings").scrollIntoViewIfNeeded();
  await page
    .locator(".notification-settings")
    .screenshot({ path: "test-results/notification-preferences-desktop.png" });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator("#sidebar")).not.toBeInViewport();
  await page
    .locator(".notification-settings")
    .screenshot({ path: "test-results/notification-preferences-mobile.png" });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.setViewportSize({ width: 1920, height: 1080 });
  const toast = page.locator(".notification-toasts");
  await emitEvent(page, started);
  await expect(toast.getByRole("status")).toHaveCount(0);
  await page
    .getByRole("switch", { name: "Inicios de ejecución", exact: true })
    .check();
  await page
    .getByRole("switch", {
      name: "Finales de ejecución y cambios de estado",
      exact: true,
    })
    .uncheck();
  await emitEvent(page, { ...started, id: 2 });
  await expect(toast).toContainText("Iniciado");
  await toast.getByRole("button", { name: "Cerrar notificación" }).click();
  await emitEvent(page, { ...started, id: 3, outcome: "SUCCEEDED" });
  await expect(toast.getByRole("status")).toHaveCount(0);
  await page
    .getByRole("switch", {
      name: "Finales de ejecución y cambios de estado",
      exact: true,
    })
    .check();
  await page
    .getByRole("checkbox", { name: "Correctos", exact: true })
    .uncheck();
  await page
    .getByRole("checkbox", { name: "Con avisos", exact: true })
    .uncheck();
  await page
    .getByRole("textbox", { name: "Tiempo en pantalla (segundos)" })
    .fill("1");
  await page.reload();
  await expect(
    page.getByRole("switch", { name: "Inicios de ejecución", exact: true }),
  ).toBeChecked();
  await expect(
    page.getByRole("checkbox", { name: "Correctos", exact: true }),
  ).not.toBeChecked();
  await emitEvent(page, { ...started, id: 4, outcome: "SUCCEEDED" });
  await emitEvent(page, { ...started, id: 5, outcome: "PARTIAL" });
  await expect(toast.getByRole("status")).toHaveCount(0);
  await emitEvent(page, { ...started, id: 6, outcome: "FAILED" });
  await expect(toast.getByRole("alert")).toContainText("Fallido");
  await page.mouse.move(0, 0);
  await expect(toast.getByRole("alert")).toHaveCount(0, { timeout: 3000 });
});

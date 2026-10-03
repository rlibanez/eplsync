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
  await page.getByRole("switch", { name: "Inicios de ejecución", exact: true }).uncheck();
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
  await expect(toast).toContainText("Importando catálogo…");
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

test("notification details open the exact operation with an expanded summary", async ({ page }) => {
  await page.route("**/api/events/operations?**", r => {
    const id = new URL(r.request().url()).searchParams.get("operationId");
    return r.fulfill({ json: operationResponse(id === "one" ? [started] : [], r.request().url()) });
  });
  await page.goto("/events");
  await expect(page.getByText("No hay eventos que coincidan con los filtros.", { exact: true })).toBeVisible();
  await page.evaluate(() => sessionStorage.setItem("eplsync.events.navigation", JSON.stringify({
    category: "JOB", outcome: "FAILED", origin: null, from: "", to: "", page: 8, scroll: 400, expanded: [],
  })));
  await emitEvent(page, started);
  await page.locator(".notification-toasts").getByRole("link", { name: "Ver detalles" }).click();
  await expect(page).toHaveURL(/events\?operationId=one/);
  await expect(page.locator(".events-table tbody tr")).toHaveCount(1);
  await expect(page.locator(".events-table details")).toHaveAttribute("open", "");
  await page.reload();
  await expect(page.locator(".events-table details")).toHaveAttribute("open", "");
  await page.goto("/events?operationId=deleted");
  await expect(page.getByText(/Este evento ya no está disponible/)).toBeVisible();
  await expect(page.getByRole("link", { name: "Ver todos los eventos" })).toBeVisible();
});

test("reconnection with a lower event cursor does not fight the frozen table read cursor", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  let cursor = 50;
  await page.route("**/api/events/unread**", r => r.fulfill({ json: { count: 0, cursor } }));
  await page.route("**/api/events/operations?**", r => r.fulfill({
    json: { ...operationResponse([{ ...started, id: 50 }], r.request().url()), cursor: 50 },
  }));
  await page.goto("/events");
  await expect(page.locator(".events-table tbody tr")).toHaveCount(1);
  await expect.poll(() => page.evaluate(() => localStorage.getItem("eplsync.events.lastRead"))).toBe("50");
  cursor = 2;
  await page.evaluate(() => {
    (window as unknown as { __event: (name: string, data: unknown) => void }).__event("reset", { cursor: 2 });
  });
  await expect.poll(() => page.evaluate(() => localStorage.getItem("eplsync.events.lastRead"))).toBe("0");
  await expect(page.getByRole("heading", { name: "Eventos", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Actualizar tabla" })).toBeVisible();
  expect(errors).toEqual([]);
});

test("opening one operation marks only that operation read and persists after reload", async ({ page }) => {
  let entries = [{ ...started, id: 1, operationId: "one" }, { ...started, id: 2, operationId: "two" }];
  await page.route("**/api/events/unread**", r => {
    const { afterId, readIds } = r.request().postDataJSON();
    const latest = new Map(entries.map(e => [e.operationId, e.id]));
    return r.fulfill({ json: {
      count: [...latest.values()].filter(id => id > afterId && !readIds.includes(id)).length,
      cursor: Math.max(...latest.values()),
    } });
  });
  await page.route("**/api/events/operations?**", r => {
    const id = new URL(r.request().url()).searchParams.get("operationId");
    return r.fulfill({ json: { ...operationResponse(entries.filter(e => !id || e.operationId === id), r.request().url()), cursor: Math.max(...entries.map(e => e.id)) } });
  });
  await page.goto("/settings/general");
  const badge = page.locator(".event-unread-count");
  await expect(badge).toHaveText("2");
  await emitEvent(page, entries[1]);
  await page.locator(".notification-toasts").getByRole("link", { name: "Ver detalles" }).click();
  await expect(page.locator(".events-table details")).toHaveAttribute("open", "");
  await expect(badge).toHaveText("1");
  await page.reload();
  await expect(badge).toHaveText("1");
  entries = [...entries, { ...started, id: 3, operationId: "two", outcome: "SUCCEEDED" }];
  await emitEvent(page, entries[2]);
  await expect(badge).toHaveText("2");
  await page.getByRole("button", { name: "Actualizar tabla" }).click();
  await expect(badge).toHaveText("1");
});

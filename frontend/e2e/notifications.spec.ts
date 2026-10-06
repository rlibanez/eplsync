import { test, expect, emitEvent, operationResponse } from "./fixtures";
test.use({ timezoneId: "Europe/Madrid" });

test.beforeEach(async ({ page }) => {
  await page.route("**/api/ui/config", (r) =>
    r.fulfill({ json: { defaultLanguage: "es" } }),
  );
  await page.route("**/api/catalog/covers/task", (r) =>
    r.fulfill({ json: { task: null } }),
  );
});
const historical = {
  id: 1,
  createdAt: "2026-10-01T10:00:00Z",
  category: "CATALOG",
  action: "UPDATE",
  outcome: "SUCCEEDED",
  origin: "SCHEDULED",
  operationId: "old-operation",
  details: { created: 42 },
};

test("backend events survive reload, appear live once, and local feedback stays transient", async ({
  page,
}) => {
  const events = [historical];
  await page.route("**/api/events/operations?**", (r) =>
    r.fulfill({ json: operationResponse(events, r.request().url()) }),
  );
  await page.route("**/api/settings/torrent/connection", (r) =>
    r.fulfill({
      json: { enabled: true, connected: true, client: "qbittorrent" },
    }),
  );
  await page.addInitScript(() =>
    Object.defineProperty(window.crypto, "randomUUID", {
      value: undefined,
      configurable: true,
    }),
  );
  await page.goto("/events");
  const toast = page.locator(".notification-toasts");
  await expect(page.locator(".events-table tbody tr")).toHaveCount(1);
  await expect(page.locator(".events-table")).toContainText("Programado");
  await expect(toast.getByRole("status")).toHaveCount(0); // History does not replay as notifications.
  const live = {
    ...historical,
    id: 2,
    operationId: "live-operation",
    origin: "MANUAL",
  };
  events.push(live);
  await emitEvent(page, live);
  await expect(toast).toContainText("Completado");
  await expect(page.locator(".events-table tbody tr")).toHaveCount(1);
  await page
    .getByRole("button", { name: "Actualizar tabla", exact: true })
    .click();
  await expect(page.locator(".events-table tbody tr")).toHaveCount(2);
  await page.evaluate(() => {
    const transport = window as unknown as {
      __event: (name: string, data: unknown) => void;
      __eventError: () => void;
    };
    transport.__eventError();
    transport.__event("ready", { cursor: 1 });
  });
  await emitEvent(page, live); // A replay must not duplicate the toast.
  await expect(toast.getByRole("status")).toHaveCount(1);
  await toast.getByRole("button", { name: "Cerrar notificación" }).click();
  await expect(toast.getByRole("status")).toHaveCount(0);
  await page.reload();
  await expect(page.locator(".events-table tbody tr")).toHaveCount(2);
  await expect(toast.getByRole("status")).toHaveCount(0);
  await page
    .getByRole("link", { name: "Mantenimiento de eventos", exact: true })
    .click();
  await expect(page.locator("#events")).toBeInViewport();
  await page.goto("/settings/torrent");
  await page
    .getByRole("button", { name: "Comprobar conexión", exact: true })
    .click();
  await expect(toast).toContainText("Conexión correcta");
  await page
    .locator("#sidebar")
    .getByRole("link", { name: "Eventos", exact: true })
    .click();
  await expect(page.locator(".events-table tbody tr")).toHaveCount(2);
  await page.mouse.move(0, 0);
  await expect(toast.getByRole("status")).toHaveCount(0, { timeout: 12000 });
  await expect(page.locator(".events-table tbody tr")).toHaveCount(2);
  await page.screenshot({
    path: "test-results/events-history.png",
    fullPage: true,
  });
});

test("event maintenance confirms inclusive local dates and validates the range", async ({
  page,
}) => {
  await page.emulateMedia({ colorScheme: "dark" });
  let request: unknown;
  await page.route("**/api/events/delete", async (r) => {
    request = r.request().postDataJSON();
    await r.fulfill({ json: { deleted: 7 } });
  });
  await page.goto("/settings/general");
  const section = page.locator("#events");
  await expect(section).toContainText("10000");
  await expect(section).toContainText("365");
  await section.getByRole("textbox").first().click();
  await page
    .getByRole("option", { name: "Intervalo de fechas (días completos)" })
    .click();
  await section.getByLabel("Desde", { exact: true }).fill("2026-10-25");
  await section.getByLabel("Hasta", { exact: true }).fill("2026-10-25");
  await expect(section.getByLabel("Hasta", { exact: true })).toHaveAttribute(
    "min",
    "2026-10-25",
  );
  await section.getByRole("button", { name: "Eliminar eventos" }).click();
  expect(request).toBeUndefined();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Cancelar" })
    .click();
  expect(request).toBeUndefined();
  await section.getByRole("button", { name: "Eliminar eventos" }).click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Eliminar eventos" })
    .click();
  await expect(page.locator(".notification-toasts")).toContainText("7");
  const expected = await page.evaluate(() => ({
    confirm: true,
    from: new Date(2026, 9, 25).toISOString(),
    before: new Date(2026, 9, 26).toISOString(),
  }));
  expect(request).toEqual(expected);
  expect(expected.from).toBe("2026-10-24T22:00:00.000Z");
  expect(expected.before).toBe("2026-10-25T23:00:00.000Z");
});

test("new-event badge survives reload and clears on visiting events, including collapsed sidebar", async ({
  page,
}) => {
  const entries = [historical];
  await page.route("**/api/events/unread**", (r) => {
    const read = Number(r.request().postDataJSON()?.afterId ?? 0);
    return r.fulfill({
      json: {
        count: new Set(
          entries.filter((e) => e.id > read).map((e) => e.operationId),
        ).size,
        cursor: entries.at(-1)!.id,
      },
    });
  });
  await page.route("**/api/events/operations?**", (r) =>
    r.fulfill({ json: operationResponse(entries, r.request().url()) }),
  );
  await page.goto("/settings/general");
  const badge = page.locator(".event-unread-count");
  await expect(badge).toHaveText("1");
  await page.reload();
  await expect(badge).toHaveText("1");
  await page.getByRole("button", { name: "Plegar menú lateral" }).click();
  await expect(badge).toBeVisible();
  entries.push({ ...historical, id: 3, operationId: "third" });
  await emitEvent(page, { ...historical, id: 3, operationId: "third" });
  await expect(badge).toHaveText("2"); // IDs are not used as a count.
  await page
    .locator("#sidebar")
    .getByRole("link", { name: "Eventos", exact: true })
    .click();
  await expect(page.locator(".events-table tbody tr")).toHaveCount(2);
  await expect(badge).toHaveCount(0);
  await page.reload();
  await expect(badge).toHaveCount(0);
  entries.push({ ...historical, id: 4, operationId: "fourth" });
  await emitEvent(page, { ...historical, id: 4, operationId: "fourth" });
  await expect(page.locator(".events-table tbody tr")).toHaveCount(2);
  await expect(badge).toHaveText("1");
  await page
    .getByRole("button", { name: "Actualizar tabla", exact: true })
    .click();
  await expect(page.locator(".events-table tbody tr")).toHaveCount(3);
  await expect(badge).toHaveCount(0);
  await page.evaluate(() => {
    Object.defineProperty(document, "visibilityState", {
      value: "hidden",
      configurable: true,
    });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  entries.push({ ...historical, id: 5, operationId: "fifth" });
  await emitEvent(page, { ...historical, id: 5, operationId: "fifth" });
  await expect(badge).toHaveText("1");
  await page.evaluate(() => {
    Object.defineProperty(document, "visibilityState", {
      value: "visible",
      configurable: true,
    });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  await expect(badge).toHaveText("1"); // Merely returning to a tab does not consume pending events.
  await page
    .getByRole("button", { name: "Actualizar tabla", exact: true })
    .click();
  await expect(badge).toHaveCount(0);
});

test("start and finish share one unread operation and a later finish becomes unread again", async ({
  page,
}) => {
  const entries: (typeof historical)[] = [];
  await page.route("**/api/events/unread**", (r) => {
    const after = Number(
      r.request().postDataJSON()?.afterId ?? 0,
    );
    return r.fulfill({
      json: {
        count: new Set(
          entries.filter((e) => e.id > after).map((e) => e.operationId),
        ).size,
        cursor: entries.at(-1)?.id ?? 0,
      },
    });
  });
  await page.route("**/api/events/operations?**", (r) =>
    r.fulfill({ json: operationResponse(entries, r.request().url()) }),
  );
  await page.goto("/settings/general");
  const badge = page.locator(".event-unread-count");
  entries.push({ ...historical, id: 1, outcome: "STARTED" });
  await emitEvent(page, entries.at(-1)!);
  await expect(badge).toHaveText("1");
  entries.push({ ...historical, id: 2, outcome: "SUCCEEDED" });
  await emitEvent(page, entries.at(-1)!);
  await expect(badge).toHaveText("1");
  await page
    .locator("#sidebar")
    .getByRole("link", { name: "Eventos", exact: true })
    .click();
  await expect(page.locator(".events-table tbody tr")).toHaveCount(1);
  await expect(badge).toHaveCount(0);
  await expect(
    page.getByText("Eventos en tiempo real conectados.", { exact: true }),
  ).toHaveCount(0);
  entries.push({
    ...historical,
    id: 3,
    operationId: "second",
    outcome: "STARTED",
  });
  await emitEvent(page, entries.at(-1)!);
  await page
    .getByRole("button", { name: "Actualizar tabla", exact: true })
    .click();
  await expect(badge).toHaveCount(0);
  entries.push({
    ...historical,
    id: 4,
    operationId: "second",
    outcome: "SUCCEEDED",
  });
  await emitEvent(page, entries.at(-1)!);
  await expect(badge).toHaveText("1");
});

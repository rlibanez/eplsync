import { test, expect, emitEvent } from "./fixtures";

test("account switches independently hide notifications and the event badge and survive reload", async ({
  page,
}) => {
  let preferences = { floating: true, unreadBadge: true };
  await page.route("**/api/auth/notifications", (route) => {
    if (route.request().method() === "PUT")
      preferences = route.request().postDataJSON();
    return route.fulfill({ json: preferences });
  });
  await page.route("**/api/settings/events", (route) =>
    route.fulfill({ json: { section: "events", fields: [] } }),
  );
  await page.route("**/api/events/unread**", (route) =>
    route.fulfill({ json: { count: 3, cursor: 100 } }),
  );
  await page.goto("/settings/notifications");
  const floating = page.getByRole("checkbox", {
    name: "Mostrar notificaciones flotantes",
    exact: true,
  });
  const badge = page.getByRole("checkbox", {
    name: "Mostrar contador de eventos sin leer en el menú lateral",
    exact: true,
  });
  await expect(floating).toBeChecked();
  await expect(badge).toBeChecked();
  await expect(page.locator(".event-unread-count")).toHaveText("3");
  await floating.click();
  await expect.poll(() => preferences.floating).toBe(false);
  await expect(floating).toBeEnabled();
  await expect(
    page.getByRole("checkbox", { name: "Inicios de ejecución", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("textbox", {
      name: "Tiempo en pantalla (segundos)",
      exact: true,
    }),
  ).toBeDisabled();
  await expect(badge).toBeEnabled();
  await badge.click();
  await expect.poll(() => preferences.unreadBadge).toBe(false);
  await expect(page.locator(".event-unread-count")).toHaveCount(0);
  await page.reload();
  await expect(floating).not.toBeChecked();
  await expect(badge).not.toBeChecked();
  await expect(page.locator(".event-unread-count")).toHaveCount(0);
  const started = {
    id: 1,
    createdAt: "2026-10-15T09:00:00Z",
    operationId: "silent",
    category: "CATALOG",
    outcome: "STARTED",
    origin: "MANUAL",
    action: "UPDATE",
    details: {},
  };
  await emitEvent(page, started);
  await expect(
    page.locator(".notification-toasts").getByRole("status"),
  ).toHaveCount(0);
  await floating.click();
  await expect.poll(() => preferences.floating).toBe(true);
  await expect(floating).toBeEnabled();
  await expect(
    page.getByRole("checkbox", { name: "Inicios de ejecución", exact: true }),
  ).toBeEnabled();
  await emitEvent(page, { ...started, id: 2, operationId: "visible" });
  await expect(page.locator(".notification-toasts")).toContainText(
    "Importando catálogo",
  );
  await expect(page.locator(".event-unread-count")).toHaveCount(0);
  await floating.click();
  await expect(
    page.locator(".notification-toasts").getByRole("status"),
  ).toHaveCount(0);
  await expect(floating).toBeEnabled();
  await badge.click();
  await expect(page.locator(".event-unread-count")).toHaveText("3");
});

test("personal event settings are available without event administration permission", async ({
  page,
}) => {
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      json: {
        id: "reader",
        username: "reader",
        role: "USER",
        email: "reader@example.org",
        mustChangePassword: false,
        permissions: [],
      },
    }),
  );
  await page.route("**/api/auth/notifications", (route) =>
    route.fulfill({
      json:
        route.request().method() === "PUT"
          ? route.request().postDataJSON()
          : { floating: true, unreadBadge: true },
    }),
  );
  await page.goto("/settings/notifications");
  await expect(
    page.getByRole("checkbox", {
      name: "Mostrar notificaciones flotantes",
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", {
      name: "Mantenimiento de eventos",
      exact: true,
    }),
  ).toHaveCount(0);
  await expect(
    page
      .locator("#sidebar")
      .getByRole("link", { name: "Eventos", exact: true }),
  ).toHaveCount(0);
});

test("notification settings are consolidated in Notifications rather than General", async ({
  page,
}) => {
  await page.route("**/api/settings/events", (route) =>
    route.fulfill({ json: { section: "events", fields: [] } }),
  );
  await page.goto("/settings/events");
  await expect(page).toHaveURL(/\/settings\/notifications$/);
  await page.goto("/settings/general");
  await expect(
    page.getByRole("heading", { name: "Notificaciones", exact: true }),
  ).toHaveCount(0);
  await page
    .locator(".settings-tabs")
    .getByRole("link", { name: "Notificaciones", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Notificaciones", exact: true }),
  ).toHaveCount(1);
  await expect(
    page.getByRole("checkbox", { name: "Inicios de ejecución", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("textbox", {
      name: "Tiempo en pantalla (segundos)",
      exact: true,
    }),
  ).toBeVisible();
});

test("failed saves restore the previous account preferences and show an inline error", async ({
  page,
}) => {
  await page.route("**/api/settings/events", (route) =>
    route.fulfill({ json: { section: "events", fields: [] } }),
  );
  await page.route("**/api/auth/notifications", (route) =>
    route.request().method() === "PUT"
      ? route.fulfill({ status: 500, json: { details: "Save failed" } })
      : route.fallback(),
  );
  await page.goto("/settings/notifications");
  const floating = page.getByRole("checkbox", {
    name: "Mostrar notificaciones flotantes",
    exact: true,
  });
  await expect(floating).toBeChecked();
  await floating.click();
  await expect(
    page.locator(".notification-settings").getByRole("alert"),
  ).toBeVisible();
  await expect(floating).toBeChecked();
  await expect(
    page.getByRole("checkbox", { name: "Inicios de ejecución", exact: true }),
  ).toBeEnabled();
  await emitEvent(page, {
    id: 99,
    createdAt: "2026-10-15T09:00:00Z",
    operationId: "after-save-error",
    category: "CATALOG",
    outcome: "STARTED",
    origin: "MANUAL",
    action: "UPDATE",
    details: {},
  });
  await expect(page.locator(".notification-toasts")).toContainText(
    "Importando catálogo",
  );
});

import { test, expect } from "./fixtures";

test("saves private Telegram settings, keeps the token hidden and sends an explicit test", async ({
  page,
}) => {
  await page.route("**/api/settings/events", (route) =>
    route.fulfill({ json: { section: "events", fields: [] } }),
  );
  let stored = {
    enabled: false,
    tokenConfigured: false,
    chatId: "",
    success: false,
    warning: true,
    error: true,
    language: "es",
    pending: 0,
    failed: 0,
  };
  let tests = 0;
  let writes = 0;
  await page.route("**/api/auth/telegram", async (route) => {
    if (route.request().method() === "PUT") {
      writes++;
      const input = route.request().postDataJSON();
      expect(input).not.toHaveProperty("userId");
      stored = {
        ...stored,
        ...input,
        tokenConfigured: input.clearToken
          ? false
          : !!input.token || stored.tokenConfigured,
      };
      delete (stored as unknown as Record<string, unknown>).token;
      delete (stored as unknown as Record<string, unknown>).clearToken;
    }
    return route.fulfill({ json: stored });
  });
  await page.route("**/api/auth/telegram/test", (route) => {
    tests++;
    expect(route.request().postDataJSON()).toMatchObject({
      chatId: "123456",
      clearToken: false,
    });
    return route.fulfill({ status: 204 });
  });
  await page.goto("/settings/notifications");
  const section = page.locator("section").filter({
    has: page.getByRole("heading", { name: "Telegram", exact: true }),
  });
  await expect(
    section.getByRole("button", { name: "Enviar mensaje de prueba" }),
  ).toBeDisabled();
  await section
    .getByLabel("Token del bot", { exact: true })
    .fill("123456789:abcdefghijklmnopqrstuvwxyz_123456789");
  await section.getByLabel("ID del chat", { exact: true }).fill("123456");
  await section
    .getByRole("button", { name: "Enviar mensaje de prueba" })
    .click();
  await expect(section.getByRole("status")).toContainText(
    "Mensaje de prueba enviado",
  );
  expect(writes).toBe(0);
  expect(stored.tokenConfigured).toBe(false);
  await expect(
    section.getByLabel("Token del bot", { exact: true }),
  ).toHaveValue("123456789:abcdefghijklmnopqrstuvwxyz_123456789");

  await section.getByLabel("Activar notificaciones de Telegram").check();
  await section.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect(section.getByRole("status")).toContainText(
    "Configuración de Telegram guardada",
  );
  await expect(
    section.getByLabel("Token del bot", { exact: true }),
  ).toHaveValue("");
  await section
    .getByRole("button", { name: "Enviar mensaje de prueba" })
    .click();
  await expect(
    section
      .getByRole("status")
      .filter({ hasText: "Mensaje de prueba enviado" }),
  ).toBeVisible();
  expect(tests).toBe(2);
  await page.reload();
  await expect(
    section.getByLabel("Token del bot", { exact: true }),
  ).toHaveValue("");
  await expect(section.getByLabel("ID del chat", { exact: true })).toHaveValue(
    "123456",
  );
  await section.getByLabel("Eliminar el token guardado").check();
  await section.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect(
    section.getByLabel("Activar notificaciones de Telegram"),
  ).not.toBeChecked();
  await expect(
    section.getByRole("button", { name: "Enviar mensaje de prueba" }),
  ).toBeDisabled();
});

import { test, expect } from "./fixtures";

test.beforeEach(async ({ page }) => {
  await page.route("**/api/settings/calibre", (r) =>
    r.fulfill({
      json: {
        url: "http://calibre.test",
        username: "",
        passwordConfigured: false,
        library: "main",
        browserUrl: "",
        autoCompleteIdentifiers: true,
      },
    }),
  );
});

test("admin can check without saving, save and clear the key", async ({
  page,
}) => {
  let view = {
    apiKeyConfigured: true,
    environmentConfigured: true,
    overridden: false,
  };
  const writes: unknown[] = [];
  await page.route("**/api/settings/hardcover", (r) => {
    const method = r.request().method();
    if (method === "PUT") {
      const input = r.request().postDataJSON();
      writes.push(input);
      view = {
        ...view,
        overridden: true,
        apiKeyConfigured: !input.clearApiKey,
      };
    }
    if (method === "DELETE") {
      writes.push("restore");
      view = { ...view, overridden: false, apiKeyConfigured: true };
    }
    return r.fulfill({ json: view });
  });
  let checks = 0;
  await page.route("**/api/settings/hardcover/connection", (r) => {
    expect(r.request().postDataJSON()).toEqual({
      apiKey: "draft-test-key",
      clearApiKey: false,
    });
    checks++;
    return r.fulfill({ json: { connected: true } });
  });
  await page.goto("/settings/calibre");
  const panel = page.getByRole("region", { name: "Hardcover", exact: true });
  await expect(panel).toBeVisible();
  await expect(
    page
      .getByRole("navigation", { name: "Ajustes" })
      .getByRole("link", { name: "Hardcover", exact: true }),
  ).toHaveCount(0);
  const field = page.getByLabel("API key", { exact: true });
  await expect(field).toHaveValue("");
  await expect(field).toHaveAttribute("type", "password");
  await expect(field).toHaveAttribute("placeholder", "API key configurada");
  await field.fill("draft-test-key");
  await panel
    .getByRole("button", { name: "Comprobar conexión", exact: true })
    .click();
  await expect(
    page.getByText("Conexión correcta con Hardcover."),
  ).toBeVisible();
  expect(checks).toBe(1);
  expect(writes).toEqual([]);
  await panel.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect(field).toHaveValue("");
  await expect(
    panel.getByRole("button", {
      name: "Usar configuración del entorno",
      exact: true,
    }),
  ).toHaveCount(0);
  expect(writes).toEqual([{ apiKey: "draft-test-key", clearApiKey: false }]);
  await page
    .getByRole("checkbox", {
      name: "Borrar la API key y desactivar la integración",
    })
    .check();
  await expect(
    panel.getByRole("button", { name: "Comprobar conexión", exact: true }),
  ).toBeDisabled();
  await panel.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect(page.getByText("No hay una API key configurada.")).toBeVisible();
  expect(writes).toEqual([
    { apiKey: "draft-test-key", clearApiKey: false },
    { apiKey: "", clearApiKey: true },
  ]);
  await page.setViewportSize({ width: 480, height: 900 });
  await expect(field).toBeVisible();
  expect(
    await page.evaluate(
      () =>
        document.documentElement.scrollWidth -
        document.documentElement.clientWidth,
    ),
  ).toBeLessThan(2);
});

test("non-admin cannot see or load global Hardcover settings", async ({
  page,
}) => {
  let reads = 0;
  await page.route("**/api/auth/me", (r) =>
    r.fulfill({
      json: {
        id: "reader",
        username: "reader",
        role: "USER",
        permissions: ["CALIBRE_READ", "CALIBRE_SEND", "SETTINGS_MANAGE"],
      },
    }),
  );
  await page.route("**/api/settings/hardcover", (r) => {
    reads++;
    return r.fulfill({ json: {} });
  });
  await page.goto("/settings/calibre");
  await expect(
    page.getByRole("textbox", { name: "API key", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("link", { name: "Hardcover", exact: true }),
  ).toHaveCount(0);
  expect(reads).toBe(0);
});

test("credential errors are visible without saving or echoing secrets", async ({
  page,
}) => {
  await page.route("**/api/settings/hardcover", (r) =>
    r.fulfill({
      json: {
        apiKeyConfigured: false,
        environmentConfigured: false,
        overridden: false,
      },
    }),
  );
  await page.route("**/api/settings/hardcover/connection", (r) =>
    r.fulfill({
      status: 400,
      json: {
        details:
          "Hardcover ha rechazado la clave o sus permisos; revisa la API key en Ajustes > Calibre > Hardcover",
      },
    }),
  );
  await page.goto("/settings/calibre");
  const panel = page.getByRole("region", { name: "Hardcover", exact: true });
  await page.getByLabel("API key", { exact: true }).fill("invalid-test-key");
  await panel
    .getByRole("button", { name: "Comprobar conexión", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText(
    "Hardcover ha rechazado la clave",
  );
  await expect(
    page.getByRole("button", { name: "Reintentar", exact: true }),
  ).toHaveCount(0);
});

test("quota is shown from cached response metadata without remote checks and the old route redirects", async ({
  page,
}) => {
  let checks = 0;
  await page.route("**/api/settings/hardcover", (r) =>
    r.fulfill({
      json: {
        apiKeyConfigured: true,
        environmentConfigured: true,
        overridden: false,
        usage: {
          limit: 5000,
          remaining: 4840,
          used: 160,
          observedAt: "2026-10-26T10:00:00Z",
          resetAt: "2026-10-27T00:00:00Z",
        },
      },
    }),
  );
  await page.route("**/api/settings/hardcover/connection", (r) => {
    checks++;
    return r.fulfill({ json: { connected: true } });
  });
  await page.goto("/settings/hardcover");
  await expect(page).toHaveURL(/\/settings\/calibre$/);
  const panel = page.getByRole("region", { name: "Hardcover", exact: true });
  await expect(panel).toContainText(/Uso diario: 160 \/ 5[.,]?000 consultas/);
  await expect(panel).toContainText(/4[.,]?840 disponibles/);
  await expect(panel).toContainText("Última respuesta:");
  await expect(panel).not.toContainText("La API key se comparte");
  await expect(panel).not.toContainText("Se utiliza EPLSYNC_HARDCOVER_API_KEY");
  expect(
    await panel.evaluate(
      (element) =>
        element.previousElementSibling?.querySelector("h2")?.textContent,
    ),
  ).toContain("Identificadores");
  expect(checks).toBe(0);
});

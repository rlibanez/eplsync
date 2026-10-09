import { test, expect } from "./fixtures";

test("catalog reader sees neither an empty downloads group nor import controls", async ({
  page,
}) => {
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      json: {
        id: "reader",
        username: "reader",
        email: "reader@example.org",
        role: "USER",
        mustChangePassword: false,
        permissions: ["CATALOG_READ"],
      },
    }),
  );
  await page.route("**/api/catalog/books?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        meta: { totalItems: 0, totalPages: 0, page: 0, size: 20 },
      },
    }),
  );
  await page.goto("/catalog");
  await expect(
    page.getByRole("link", { name: "Catálogo", exact: true }),
  ).toBeVisible();
  await expect(
    page.locator(".nav-group-label").filter({ hasText: "Descargas" }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Importar catálogo", exact: true }),
  ).toHaveCount(0);
});

test("restore defaults clears permission overrides when the editor is saved", async ({
  page,
}) => {
  const user = {
    id: "reader",
    username: "reader",
    email: "reader@example.org",
    role: "USER",
    status: "ACTIVE",
    overrides: { CATALOG_READ: "DENY", TORRENT_SEND: "ALLOW" },
    permissions: ["TORRENT_SEND"],
    mustChangePassword: false,
  };
  let saved: Record<string, unknown> | undefined;
  await page.route("**/api/security/users", (route) =>
    route.fulfill({ json: [user] }),
  );
  await page.route("**/api/security/permissions", (route) =>
    route.fulfill({ json: ["CATALOG_READ", "TORRENT_SEND"] }),
  );
  await page.route("**/api/security/policy", (route) =>
    route.fulfill({
      json: {
        registrationEnabled: false,
        approvalRequired: true,
        idleMinutes: 30,
        maximumHours: 12,
        passwordMinimumLength: 8,
      },
    }),
  );
  await page.route("**/api/security/users/reader", (route) => {
    saved = route.request().postDataJSON();
    return route.fulfill({ json: { success: true } });
  });
  await page.goto("/settings/users");
  await page
    .getByRole("button", { name: "Editar reader", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog
    .getByRole("button", { name: "Restaurar predeterminados", exact: true })
    .click();
  await expect(dialog.getByLabel("Consultar catálogo y magnets")).toHaveValue(
    "Heredar: permitido",
  );
  await expect(dialog.getByLabel("Enviar y renombrar torrents")).toHaveValue(
    "Heredar: denegado",
  );
  await dialog.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(saved).toEqual({ role: "USER", status: "ACTIVE", overrides: {} });
});

test("unified torrent permission exposes download history and synchronization together", async ({
  page,
}) => {
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      json: {
        id: "sync-user",
        username: "sync-user",
        email: "sync@example.org",
        role: "USER",
        mustChangePassword: false,
        permissions: ["TORRENT_SYNC"],
      },
    }),
  );
  await page.route("**/api/torrent/downloads?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        meta: { totalItems: 0, totalPages: 0, page: 0, size: 20 },
      },
    }),
  );
  await page.route("**/api/torrent/downloads/summary**", (route) =>
    route.fulfill({ json: { total: 0, byStatus: {} } }),
  );
  await page.goto("/downloads");
  await expect(
    page.getByRole("link", { name: "Estado", exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("EPL id", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("button", {
      name: "Previsualizar sincronización",
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", {
      name: "Sincronizar con el cliente",
      exact: true,
    }),
  ).toBeVisible();
});

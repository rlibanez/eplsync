import { test, expect } from "./fixtures";
test("admin controls registration and mandatory approval", async ({ page }) => {
  let policy = {
    registrationEnabled: false,
    approvalRequired: true,
    idleMinutes: 30,
    maximumHours: 12,
    passwordMinimumLength: 8,
  };
  await page.route("**/api/ui/config", (route) =>
    route.fulfill({ json: { defaultLanguage: "es" } }),
  );
  await page.route("**/api/security/users", (route) =>
    route.fulfill({ json: [] }),
  );
  await page.route("**/api/security/permissions", (route) =>
    route.fulfill({ json: ["CATALOG_READ"] }),
  );
  await page.route("**/api/security/policy", (route) => {
    if (route.request().method() === "PUT") {
      expect(route.request().headers()["x-csrf-token"]).toBe("test-csrf");
      policy = route.request().postDataJSON();
    }
    return route.fulfill({ json: policy });
  });
  await page.goto("/settings/users");
  await expect(page.getByLabel("Aprobación obligatoria")).toBeChecked();
  await expect(
    page
      .locator(".panel")
      .getByRole("heading", { name: "Usuarios y seguridad", exact: true }),
  ).toHaveCount(0);
  for (const name of ["Registro", "Sesiones", "Contraseñas"]) {
    await expect(page.getByRole("group", { name, exact: true })).toBeVisible();
  }
  const field = await page
    .getByLabel("Longitud mínima de contraseña")
    .boundingBox();
  const button = await page
    .getByRole("button", { name: "Guardar", exact: true })
    .boundingBox();
  expect(field!.width).toBeGreaterThanOrEqual(300);
  expect(field!.width).toBeLessThanOrEqual(360);
  expect(button!.width).toBeLessThan(200);
  await page.getByLabel("Habilitar registro público").check();
  await page.getByLabel("Longitud mínima de contraseña").fill("10");
  await page.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect(page.getByLabel("Habilitar registro público")).toBeChecked();
  expect(policy.registrationEnabled).toBe(true);
  expect(policy.approvalRequired).toBe(true);
  expect(policy.passwordMinimumLength).toBe(10);
});
test("erasing users requires the full reset phrase and explicit flag", async ({
  page,
}) => {
  let body: Record<string, unknown> | undefined;
  await page.route("**/api/ui/config", (route) =>
    route.fulfill({ json: { defaultLanguage: "es" } }),
  );
  await page.route("**/api/maintenance/reset", (route) => {
    body = route.request().postDataJSON();
    return route.fulfill({ json: { success: true } });
  });
  await page.goto("/settings/database");
  await expect(
    page.locator(".section-tabs a[href='/settings/reset']"),
  ).toHaveCount(0);
  await expect(
    page.locator(".section-tabs a[href='/settings/missing']"),
  ).toHaveCount(0);
  await page
    .getByRole("button", { name: "Reiniciar base de datos", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog
    .getByLabel("Borrar también usuarios y toda la configuración")
    .check();
  const submit = dialog.getByRole("button", { name: "Vaciar base de datos" });
  await expect(submit).toBeDisabled();
  await dialog.getByLabel("BORRAR TODO").fill("BORRAR TODO");
  await submit.click();
  await expect(dialog).toHaveCount(0);
  expect(body).toEqual({
    confirm: true,
    eraseUsersAndSettings: true,
    fullResetConfirmation: "BORRAR TODO",
  });
});

test("users table creates, edits permissions and confirms irreversible deletion", async ({
  page,
}) => {
  const administrator = {
    id: "admin",
    username: "admin",
    email: "admin@example.org",
    role: "ADMIN",
    status: "ACTIVE",
    mustChangePassword: false,
    overrides: {},
    permissions: ["CATALOG_READ", "TORRENT_SEND"],
  };
  let users = [administrator];
  let deletes = 0;
  let resets = 0;
  let creationAttempts = 0;
  await page.route("**/api/ui/config", (route) =>
    route.fulfill({ json: { defaultLanguage: "es" } }),
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
  await page.route("**/api/security/permissions", (route) =>
    route.fulfill({ json: ["CATALOG_READ", "TORRENT_SEND"] }),
  );
  await page.route("**/api/security/users", (route) => {
    if (route.request().method() === "POST") {
      creationAttempts++;
      if (creationAttempts === 1)
        return route.fulfill({
          status: 400,
          json: { details: "Email inválido" },
        });
      const input = route.request().postDataJSON();
      const user = {
        ...input,
        id: "reader",
        status: "ACTIVE",
        mustChangePassword: true,
        overrides: {},
        permissions: ["CATALOG_READ"],
      };
      users = [...users, user];
      return route.fulfill({
        json: {
          user,
          password: "temporary-password",
          expiresAt: "2099-01-01T00:00:00Z",
        },
      });
    }
    return route.fulfill({ json: users });
  });
  await page.route("**/api/security/users/reader/password", (route) => {
    expect(route.request().method()).toBe("POST");
    expect(route.request().headers()["x-csrf-token"]).toBe("test-csrf");
    resets++;
    return route.fulfill({
      json: {
        user: users.find((user) => user.id === "reader"),
        password: "new-temporary-password",
        expiresAt: "2099-01-01T00:00:00Z",
      },
    });
  });
  await page.route("**/api/security/users/reader", (route) => {
    expect(route.request().headers()["x-csrf-token"]).toBe("test-csrf");
    if (route.request().method() === "PUT") {
      const input = route.request().postDataJSON();
      expect(input.overrides.TORRENT_SEND).toBe("ALLOW");
      users = users.map((user) =>
        user.id === "reader"
          ? { ...user, ...input, permissions: ["CATALOG_READ", "TORRENT_SEND"] }
          : user,
      );
    } else if (route.request().method() === "DELETE") {
      expect(route.request().postDataJSON()).toEqual({ confirm: true });
      deletes++;
      users = users.filter((user) => user.id !== "reader");
    }
    return route.fulfill({ json: { success: true } });
  });
  await page.goto("/settings/users");
  const sections = page.locator("main > .panel");
  await expect(
    sections.first().getByRole("heading", { name: "Usuarios", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Borrar admin", exact: true }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "Crear", exact: true }).click();
  let dialog = page.getByRole("dialog");
  const geometry = () =>
    dialog.evaluate((element) => {
      const bounds = element.getBoundingClientRect();
      const role = element
        .querySelector(".mantine-Select-input")!
        .getBoundingClientRect();
      return { height: bounds.height, roleOffset: role.y - bounds.y };
    });
  const initialGeometry = await geometry();
  await dialog.getByLabel(/^Nombre de usuario/).fill("bad user");
  await expect(
    page.getByRole("tooltip", {
      name: "El usuario debe tener entre 3 y 64 caracteres: letras, números, punto, guion o guion bajo",
      exact: true,
    }),
  ).toBeVisible();
  await dialog.getByLabel(/^Correo electrónico/).fill("invalid@domain");
  await expect(
    page.getByRole("tooltip", { name: "Email inválido", exact: true }),
  ).toBeVisible();
  await expect(
    dialog.getByRole("button", { name: "Crear usuario", exact: true }),
  ).toBeDisabled();
  const invalidGeometry = await geometry();
  expect(
    Math.abs(invalidGeometry.height - initialGeometry.height),
  ).toBeLessThan(1);
  expect(
    Math.abs(invalidGeometry.roleOffset - initialGeometry.roleOffset),
  ).toBeLessThan(1);
  expect(creationAttempts).toBe(0);
  await dialog.getByLabel(/^Nombre de usuario/).fill("reader");
  await dialog.getByLabel(/^Correo electrónico/).fill("reader@example.org");
  const validGeometry = await geometry();
  expect(Math.abs(validGeometry.height - initialGeometry.height)).toBeLessThan(
    1,
  );
  expect(
    Math.abs(validGeometry.roleOffset - initialGeometry.roleOffset),
  ).toBeLessThan(1);
  await dialog
    .getByRole("button", { name: "Crear usuario", exact: true })
    .click();
  await expect(dialog.getByRole("alert")).toContainText("Email inválido");
  await expect(page.locator("main [role='alert']")).toHaveCount(0);
  await dialog
    .getByRole("button", { name: "Crear usuario", exact: true })
    .click();
  await expect(
    dialog.getByLabel("Contraseña temporal", { exact: true }),
  ).toHaveValue("temporary-password");
  await dialog.getByRole("button", { name: "Cerrar", exact: true }).click();
  await expect(dialog).toBeHidden();
  const row = page.getByRole("row").filter({
    has: page.getByRole("button", { name: "Editar reader", exact: true }),
  });
  await expect(row).toContainText("Consultar catálogo y magnets");
  await page
    .getByRole("button", { name: "Editar reader", exact: true })
    .click();
  dialog = page.getByRole("dialog");
  await dialog.getByLabel("Enviar y renombrar torrents").click();
  await page.getByRole("option", { name: "Permitir", exact: true }).click();
  await dialog.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(row).toContainText("Enviar y renombrar torrents");
  await expect(page.locator("table.catalog-table")).toBeVisible();
  await expect(
    page.getByRole("columnheader", { name: "Permisos", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", {
      name: "Reiniciar contraseña de reader",
      exact: true,
    })
    .click();
  await expect(dialog).toContainText("se cerrarán todas sus sesiones");
  await dialog.getByRole("button", { name: "Cancelar", exact: true }).click();
  expect(resets).toBe(0);
  await page
    .getByRole("button", {
      name: "Reiniciar contraseña de reader",
      exact: true,
    })
    .click();
  await dialog
    .getByRole("button", { name: "Generar contraseña temporal", exact: true })
    .click();
  await expect(
    dialog.getByLabel("Contraseña temporal", { exact: true }),
  ).toHaveValue("new-temporary-password");
  await dialog.getByRole("button", { name: "Cerrar", exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(resets).toBe(1);
  await page
    .getByRole("button", { name: "Borrar reader", exact: true })
    .click();
  await expect(dialog).toContainText("Esta acción es irreversible");
  await dialog.getByRole("button", { name: "Cancelar", exact: true }).click();
  expect(deletes).toBe(0);
  await page
    .getByRole("button", { name: "Borrar reader", exact: true })
    .click();
  await dialog
    .getByRole("button", { name: "Borrar usuario", exact: true })
    .click();
  await expect(row).toHaveCount(0);
  expect(deletes).toBe(1);
});

test("users sort by translated headers and edit in grouped responsive dialog", async ({
  page,
}) => {
  const permissions = [
    "CATALOG_READ",
    "BOOK_HISTORY_READ",
    "DOWNLOADS_READ",
    "TORRENT_SEND",
    "TORRENT_SYNC",
    "TORRENT_JOBS_MANAGE",
    "TORRENT_CLEANUP",
    "TORRENT_FILES_DELETE",
    "CATALOG_IMPORT",
    "CATALOG_DELETE",
    "COVERS_MANAGE",
    "EVENTS_MANAGE",
    "SETTINGS_MANAGE",
  ];
  const users = [
    { id: "zeta", username: "zeta", role: "USER", status: "ACTIVE" },
    { id: "alpha", username: "alpha", role: "USER", status: "DISABLED" },
    { id: "admin", username: "admin", role: "ADMIN", status: "PENDING" },
  ].map((user) => ({
    ...user,
    email: `${user.username}@example.org`,
    overrides: {},
    permissions: ["CATALOG_READ"],
    mustChangePassword: false,
  }));
  await page.route("**/api/ui/config", (route) =>
    route.fulfill({ json: { defaultLanguage: "es" } }),
  );
  await page.route("**/api/security/users", (route) =>
    route.fulfill({ json: users }),
  );
  await page.route("**/api/security/permissions", (route) =>
    route.fulfill({ json: permissions }),
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
  await page.goto("/settings/users");
  const names = () =>
    page
      .locator("tbody tr td:first-child")
      .evaluateAll((cells) =>
        cells.map((cell) => cell.childNodes[0].textContent),
      );
  await expect.poll(names).toEqual(["admin", "alpha", "zeta"]);
  await page
    .getByRole("button", { name: "Nombre de usuario", exact: true })
    .click();
  await expect.poll(names).toEqual(["zeta", "alpha", "admin"]);
  await expect(
    page.getByRole("columnheader", { name: "Nombre de usuario", exact: true }),
  ).toHaveAttribute("aria-sort", "descending");
  await page.getByRole("button", { name: "Rol", exact: true }).click();
  await expect.poll(names).toEqual(["admin", "alpha", "zeta"]);
  await page.getByRole("button", { name: "Estado", exact: true }).click();
  await expect.poll(names).toEqual(["zeta", "alpha", "admin"]);
  await page.getByRole("button", { name: "Estado", exact: true }).click();
  await expect.poll(names).toEqual(["admin", "alpha", "zeta"]);
  await page.getByRole("button", { name: "Editar zeta", exact: true }).click();
  const dialog = page.getByRole("dialog");
  for (const name of ["Cuenta", "Catálogo", "Cliente torrent", "Sistema"]) {
    await expect(
      dialog.getByRole("group", { name, exact: true }),
    ).toBeVisible();
  }
  await expect(
    dialog
      .locator(".user-editor-footer")
      .getByRole("button", { name: "Guardar", exact: true }),
  ).toBeInViewport();
  await dialog.screenshot({ path: "/tmp/eplsync-user-editor-wide.png" });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(
    dialog
      .locator(".user-editor-footer")
      .getByRole("button", { name: "Guardar", exact: true }),
  ).toBeInViewport();
  const overflow = await dialog.evaluate(
    (element) => element.scrollWidth > element.clientWidth,
  );
  expect(overflow).toBe(false);
  await dialog.screenshot({ path: "/tmp/eplsync-user-editor-mobile.png" });
  await dialog.getByRole("button", { name: "Cancelar", exact: true }).click();
  await expect(dialog).toBeHidden();
});

test("admin self reset keeps the temporary password visible after logout", async ({
  page,
}) => {
  await page.context().grantPermissions(["clipboard-read", "clipboard-write"]);
  let signedIn = true;
  const admin = {
    id: "test-admin",
    username: "admin",
    email: "admin@example.org",
    role: "ADMIN",
    status: "ACTIVE",
    mustChangePassword: false,
    overrides: {},
    permissions: ["CATALOG_READ"],
  };
  await page.route("**/api/ui/config", (route) =>
    route.fulfill({ json: { defaultLanguage: "es" } }),
  );
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      status: signedIn ? 200 : 401,
      json: signedIn ? admin : {},
    }),
  );
  await page.route("**/api/auth/status", (route) =>
    route.fulfill({
      json: {
        initialized: true,
        registrationEnabled: false,
        passwordMinimumLength: 8,
      },
    }),
  );
  await page.route("**/api/auth/logout", (route) => {
    signedIn = false;
    return route.fulfill({ json: { success: true } });
  });
  await page.route("**/api/security/users", (route) =>
    route.fulfill({ json: [admin] }),
  );
  await page.route("**/api/security/permissions", (route) =>
    route.fulfill({ json: ["CATALOG_READ"] }),
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
  await page.route("**/api/security/users/test-admin/password", (route) => {
    signedIn = false;
    return route.fulfill({
      json: {
        user: admin,
        password: "admin-temporary-secret",
        expiresAt: "2099-01-01T00:00:00Z",
      },
    });
  });
  await page.goto("/settings/users");
  await page.getByRole("button", { name: "Editar admin", exact: true }).click();
  await expect(
    page
      .getByRole("dialog")
      .getByText("Generar contraseña temporal", { exact: true }),
  ).toHaveCount(0);
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Cancelar", exact: true })
    .click();
  const reset = page.getByRole("button", {
    name: "Reiniciar contraseña de admin",
    exact: true,
  });
  await expect(reset).toBeEnabled();
  await reset.click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Generar contraseña temporal", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Iniciar sesión", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("dialog").getByLabel("Contraseña temporal", { exact: true }),
  ).toHaveValue("admin-temporary-secret");
  await expect(
    page
      .getByRole("dialog")
      .getByText(
        "Se muestra una sola vez. Entrégala por un canal privado. Solo permite establecer la contraseña definitiva.",
        { exact: true },
      ),
  ).toHaveCount(0);
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Copiar contraseña", exact: true })
    .click();
  await expect(
    page
      .getByRole("dialog")
      .getByRole("button", { name: "Contraseña copiada", exact: true }),
  ).toBeVisible();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(
    "admin-temporary-secret",
  );
  await expect(
    page.getByText("Cambios guardados", { exact: true }),
  ).toHaveCount(0);
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Cerrar", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

test("pending account can be approved directly and the approval action then disappears", async ({
  page,
}) => {
  const user = {
    id: "pending",
    username: "pendinguser",
    email: "pending@example.org",
    role: "USER",
    status: "PENDING",
    overrides: {},
    permissions: ["CATALOG_READ"],
    mustChangePassword: false,
  };
  await page.route("**/api/ui/config", (route) =>
    route.fulfill({ json: { defaultLanguage: "es" } }),
  );
  await page.route("**/api/security/users", (route) =>
    route.fulfill({ json: [user] }),
  );
  await page.route("**/api/security/permissions", (route) =>
    route.fulfill({ json: ["CATALOG_READ"] }),
  );
  await page.route("**/api/security/policy", (route) =>
    route.fulfill({
      json: {
        registrationEnabled: true,
        approvalRequired: true,
        idleMinutes: 30,
        maximumHours: 12,
        passwordMinimumLength: 8,
      },
    }),
  );
  await page.route("**/api/security/users/pending/approve", (route) => {
    expect(route.request().method()).toBe("POST");
    user.status = "ACTIVE";
    return route.fulfill({ json: { success: true } });
  });
  await page.goto("/settings/users");
  await page
    .getByRole("button", { name: "Aprobar cuenta de pendinguser", exact: true })
    .click();
  await expect(
    page.getByRole("button", {
      name: "Aprobar cuenta de pendinguser",
      exact: true,
    }),
  ).toHaveCount(0);
  await expect(page.locator(".users-table")).toContainText("Activo");
});


test("settings tabs wrap and database stays immediately before about", async ({ page }) => {
  await page.goto("/settings/general");
  const tabs = page.locator(".settings-tabs");
  await expect(tabs.locator("a").nth(-2)).toHaveAttribute("href", "/settings/database");
  await expect(tabs.locator("a").last()).toHaveAttribute("href", "/settings/about");
  await expect(tabs.locator("a[href='/settings/catalog']")).toBeVisible();
  await page.setViewportSize({ width: 390, height: 900 });
  const layout = await tabs.evaluate((nav) => ({
    scroll: nav.scrollWidth,
    width: nav.clientWidth,
    rows: new Set(Array.from(nav.querySelectorAll("a"), (a) => Math.round(a.getBoundingClientRect().top))).size,
  }));
  expect(layout.scroll).toBeLessThanOrEqual(layout.width);
  expect(layout.rows).toBeGreaterThan(1);
  await tabs.locator("a[href='/settings/database']").click();
  await expect(page.getByRole("button", { name: "Reiniciar base de datos", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Configuración de importación" })).toHaveCount(0);
});

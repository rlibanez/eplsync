import { test, expect } from "@playwright/test";
test.beforeEach(async ({ page }) => {
  page.on("pageerror", (error) => {
    throw error;
  });
});
const account = {
  id: "reader",
  username: "reader",
  email: "reader@example.org",
  role: "USER",
  mustChangePassword: false,
  permissions: [],
};
test("login sends CSRF and enforces restricted navigation", async ({
  page,
}) => {
  let signedIn = false;
  await page.route("http://127.0.0.1:5178/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/ui/config")
      return route.fulfill({ json: { defaultLanguage: "es" } });
    if (path === "/api/auth/me")
      return route.fulfill({
        status: signedIn ? 200 : 401,
        json: signedIn ? account : {},
      });
    if (path === "/api/auth/status")
      return route.fulfill({
        json: { initialized: true, registrationEnabled: false },
      });
    if (path === "/api/auth/csrf")
      return route.fulfill({
        json: { token: "masked-token", headerName: "X-CSRF-TOKEN" },
      });
    if (path === "/api/auth/login") {
      expect(route.request().headers()["x-csrf-token"]).toBe("masked-token");
      expect(route.request().postDataJSON()).toEqual({
        username: "reader",
        password: "reader-password",
      });
      signedIn = true;
      return route.fulfill({ json: account });
    }
    return route.fulfill({ json: {} });
  });
  await page.goto("/settings/account");
  await expect(
    page.getByRole("heading", { name: "EPL Sync", exact: true }),
  ).toHaveCSS("text-align", "center");
  await expect(page.locator(".auth-panel form > div")).toHaveCSS("gap", "16px");
  await expect(
    page.locator(".auth-panel .mantine-InputWrapper-required"),
  ).toHaveCount(0);
  await expect(page.getByLabel("Nombre de usuario")).toHaveAttribute(
    "required",
    "",
  );
  await page.getByLabel("Nombre de usuario").fill("reader");
  await page.getByLabel(/^Contraseña/).fill("reader-password");
  await page
    .getByRole("button", { name: "Iniciar sesión", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Mi cuenta", exact: true }),
  ).toHaveCount(0);
  await expect(
    page
      .locator(".panel")
      .getByRole("heading", { name: "Correo electrónico", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".panel.settings-section")).toHaveCount(2);
  const passwordField = await page
    .getByLabel(/^Nueva contraseña/)
    .boundingBox();
  const changeButton = await page
    .getByRole("button", { name: "Cambiar contraseña", exact: true })
    .boundingBox();
  expect(passwordField!.width).toBeGreaterThanOrEqual(300);
  expect(passwordField!.width).toBeLessThanOrEqual(360);
  const currentBox = (await page
    .getByLabel(/^Contraseña actual/)
    .boundingBox())!;
  const repeatBox = (await page
    .getByLabel(/^Repetir contraseña/)
    .boundingBox())!;
  expect(passwordField!.x).toBe(currentBox.x);
  expect(repeatBox.x).toBe(currentBox.x);
  expect(passwordField!.y).toBeGreaterThan(currentBox.y);
  expect(repeatBox.y).toBeGreaterThan(passwordField!.y);
  expect(changeButton!.width).toBeLessThan(250);
  await expect(
    page.getByRole("link", { name: "Catálogo", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("link", { name: "Descargas", exact: true }),
  ).toHaveCount(0);
  await page.goto("/downloads");
  await expect(
    page.getByText("No tienes permiso para acceder a este apartado"),
  ).toBeVisible();
});
test("temporary credentials require password replacement", async ({ page }) => {
  await page.route("http://127.0.0.1:5178/api/**", (route) =>
    route.fulfill({
      json:
        new URL(route.request().url()).pathname === "/api/auth/me"
          ? { ...account, mustChangePassword: true }
          : { defaultLanguage: "es" },
    }),
  );
  await page.goto("/catalog");
  await expect(
    page.getByRole("heading", {
      name: "Debes establecer una contraseña definitiva",
    }),
  ).toBeVisible();
  await expect(page.getByLabel("Contraseña actual")).toBeVisible();
  const current = page.getByLabel("Contraseña actual");
  const next = page.getByLabel(/^Nueva contraseña/);
  const repeat = page.getByLabel("Repetir contraseña");
  await expect(
    page.getByRole("button", { name: "Cerrar sesión", exact: true }),
  ).toHaveCount(0);
  const changeButton = page.getByRole("button", {
    name: "Cambiar contraseña",
    exact: true,
  });
  const formBox = (await page.locator(".auth-panel form").boundingBox())!;
  expect((await changeButton.boundingBox())!.width).toBe(formBox.width);
  const currentBox = (await current.boundingBox())!;
  const nextBox = (await next.boundingBox())!;
  const repeatBox = (await repeat.boundingBox())!;
  expect(nextBox.x).toBe(currentBox.x);
  expect(repeatBox.x).toBe(currentBox.x);
  expect(nextBox.y).toBeGreaterThan(currentBox.y);
  expect(repeatBox.y).toBeGreaterThan(nextBox.y);
  await next.fill("short");
  await expect(
    page
      .getByRole("tooltip", {
        name: "Entre 8 y 256 caracteres.",
        exact: true,
      })
      .first(),
  ).toBeVisible();
  await repeat.fill("short");
  await expect(
    page.getByRole("tooltip", { name: "Las contraseñas coinciden" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Cambiar contraseña", exact: true }),
  ).toBeDisabled();
  await next.fill("permanent password");
  await repeat.fill("different password");
  await expect(
    page.getByRole("tooltip", { name: "Las contraseñas no coinciden" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Cambiar contraseña", exact: true }),
  ).toBeDisabled();
  await repeat.fill("permanent password");
  await expect(
    page.getByRole("tooltip", { name: "Las contraseñas coinciden" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Cambiar contraseña", exact: true }),
  ).toBeEnabled();

  await expect(
    page.getByRole("link", { name: "Catálogo", exact: true }),
  ).toHaveCount(0);
});

test("first web load creates an administrator only after password confirmation", async ({
  page,
}) => {
  let created = false;
  let signedIn = false;
  let submissions = 0;
  const administrator = {
    ...account,
    id: "web-admin",
    username: "webadmin",
    email: "owner@example.org",
    role: "ADMIN",
  };
  await page.route("http://127.0.0.1:5178/api/**", (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/ui/config")
      return route.fulfill({ json: { defaultLanguage: "es" } });
    if (path === "/api/auth/csrf")
      return route.fulfill({
        json: { token: "setup-token", headerName: "X-CSRF-TOKEN" },
      });
    if (path === "/api/auth/status")
      return route.fulfill({
        json: {
          initialized: created,
          registrationEnabled: false,
          approvalRequired: true,
        },
      });
    if (path === "/api/auth/me")
      return route.fulfill({
        status: signedIn ? 200 : 401,
        json: signedIn ? administrator : {},
      });
    if (path === "/api/auth/setup") {
      expect(route.request().headers()["x-csrf-token"]).toBe("setup-token");
      expect(route.request().postDataJSON()).toEqual({
        username: "webadmin",
        email: "owner@example.org",
        password: "permanent admin password",
        passwordConfirmation: "permanent admin password",
      });
      submissions++;
      created = true;
      signedIn = true;
      return route.fulfill({ json: administrator });
    }
    if (path === "/api/auth/logout") {
      signedIn = false;
      return route.fulfill({ json: { success: true } });
    }
    return route.fulfill({ json: {} });
  });
  await page.goto("/settings/account");
  await expect(
    page.getByRole("heading", { name: "Crear el primer administrador" }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Crear administrador", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "EPL Sync", exact: true }),
  ).toHaveCSS("text-align", "center");
  await page
    .locator(".auth-panel")
    .screenshot({ path: "/tmp/eplsync-admin-compact.png" });
  const setupButton = page.getByRole("button", {
    name: "Crear administrador",
    exact: true,
  });
  const initialButtonY = (await setupButton.boundingBox())!.y;
  await page.getByLabel(/^Nombre de usuario/).fill("bad user");
  await expect(
    page.getByText(
      "El usuario debe tener entre 3 y 64 caracteres: letras, números, punto, guion o guion bajo.",
      { exact: true },
    ),
  ).toBeVisible();
  await page.getByLabel(/^Nombre de usuario/).fill("webadmin");
  await expect(
    page.getByText("Entre 8 y 256 caracteres.", { exact: true }),
  ).toHaveCount(0);
  await page.getByText("Contraseña", { exact: true }).hover();
  await expect(
    page
      .getByRole("tooltip", {
        name: "Entre 8 y 256 caracteres.",
        exact: true,
      })
      .first(),
  ).toBeVisible();
  await page.getByLabel("Correo electrónico").fill("owner@invalid");
  await expect(page.getByText("Email inválido", { exact: true })).toBeVisible();
  expect(
    Math.abs((await setupButton.boundingBox())!.y - initialButtonY),
  ).toBeLessThan(1);
  await page.getByLabel("Correo electrónico").fill("owner@example.org");
  await expect(page.getByText("Email inválido", { exact: true })).toBeHidden();
  expect(
    Math.abs((await setupButton.boundingBox())!.y - initialButtonY),
  ).toBeLessThan(1);
  await page.mouse.move(0, 0);
  await page.getByLabel(/^Contraseña/).fill("short");
  await expect(
    page
      .getByRole("tooltip", {
        name: "Entre 8 y 256 caracteres.",
        exact: true,
      })
      .first(),
  ).toBeVisible();
  await expect(setupButton).toBeDisabled();
  await page.getByLabel(/^Contraseña/).fill("permanent admin password");
  await page.getByLabel("Repetir contraseña").fill("another admin password");
  await expect(
    page.getByText("Las contraseñas no coinciden", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Crear administrador", exact: true }),
  ).toBeDisabled();
  await page.getByLabel("Repetir contraseña").fill("permanent admin password");
  await page
    .getByRole("button", { name: "Crear administrador", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Mi cuenta", exact: true }),
  ).toHaveCount(0);
  await expect(
    page
      .locator(".panel")
      .getByRole("heading", { name: "Correo electrónico", exact: true }),
  ).toBeVisible();
  expect(submissions).toBe(1);
  await page
    .getByRole("button", { name: "Cerrar sesión", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Iniciar sesión", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Crear el primer administrador" }),
  ).toHaveCount(0);
});

test("sync permission uses State without requesting download history", async ({
  page,
}) => {
  let historyRequests = 0;
  await page.route("http://127.0.0.1:5178/api/**", (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/auth/me")
      return route.fulfill({
        json: { ...account, permissions: ["TORRENT_SYNC"] },
      });
    if (path.startsWith("/api/torrent/downloads")) historyRequests++;
    return route.fulfill({ json: { defaultLanguage: "es" } });
  });
  await page.goto("/downloads/sync");
  await expect(page).toHaveURL(/\/downloads$/);
  await expect(
    page.locator("#sidebar").getByRole("link", { name: "Estado", exact: true }),
  ).toBeVisible();
  await expect(
    page
      .locator("#sidebar")
      .getByRole("link", { name: "Sincronizar con el cliente" }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Previsualizar sincronización" }),
  ).toBeVisible();
  expect(historyRequests).toBe(0);
});

test("registration and return to login use full width buttons", async ({
  page,
}) => {
  await page.route("http://127.0.0.1:5178/api/**", (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/auth/me")
      return route.fulfill({ status: 401, json: {} });
    if (path === "/api/auth/status")
      return route.fulfill({
        json: {
          initialized: true,
          registrationEnabled: true,
          approvalRequired: true,
          passwordMinimumLength: 8,
        },
      });
    return route.fulfill({ json: { defaultLanguage: "es" } });
  });
  await page.goto("/catalog");
  const register = page.getByRole("button", {
    name: "Registrarse",
    exact: true,
  });
  const login = page.getByRole("button", {
    name: "Iniciar sesión",
    exact: true,
  });
  await expect(register).toBeVisible();
  expect((await register.boundingBox())!.width).toBe(
    (await login.boundingBox())!.width,
  );
  await register.click();
  const back = page.getByRole("button", {
    name: "Volver al inicio de sesión",
    exact: true,
  });
  await expect(back).toBeVisible();
  expect((await back.boundingBox())!.width).toBe(
    (await register.boundingBox())!.width,
  );
  await back.click();
  await expect(login).toBeVisible();
});

test("connection failure shows a clear page and retry restores access", async ({
  page,
}) => {
  let offline = true;
  await page.route("http://127.0.0.1:5178/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/ui/config")
      return route.fulfill({ json: { defaultLanguage: "es" } });
    if (path === "/api/auth/me") {
      if (offline) return route.abort("connectionrefused");
      return route.fulfill({ status: 401, json: {} });
    }
    if (path === "/api/auth/status")
      return route.fulfill({
        json: { initialized: true, registrationEnabled: false },
      });
    return route.fulfill({ json: {} });
  });
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "No se puede conectar con el servidor" }),
  ).toBeVisible();
  await expect(page.getByRole("alert")).not.toContainText("Failed to fetch");
  await page.getByRole("button", { name: "Reintentar" }).click();
  await expect(
    page.getByRole("heading", { name: "No se puede conectar con el servidor" }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Reintentar" })).toBeEnabled();
  await page.screenshot({ path: "/tmp/eplsync-connection-error.png" });
  await page.setViewportSize({ width: 360, height: 760 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  offline = false;
  await page.getByRole("button", { name: "Reintentar" }).click();
  await expect(
    page.getByRole("button", { name: "Iniciar sesión", exact: true }),
  ).toBeVisible();
});

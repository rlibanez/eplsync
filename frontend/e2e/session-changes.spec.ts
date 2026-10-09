import { test, expect } from "@playwright/test";

test("another tab renews the token even when the account stays the same", async ({
  context,
  page,
}) => {
  let epoch = 1;
  let stale = 0;
  let saves = 0;
  let reads = 0;
  const unexpected: string[] = [];
  const account = {
    id: "reader",
    username: "reader",
    email: "reader@example.org",
    role: "USER",
    mustChangePassword: false,
    permissions: [],
  };
  await context.route(
    (url) => url.pathname.startsWith("/api/"),
    async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path === "/api/auth/me") {
        reads++;
        return route.fulfill({ json: account });
      }
      if (path === "/api/auth/csrf")
        return route.fulfill({
          json: { token: `token-${epoch}`, headerName: "X-CSRF-TOKEN" },
        });
      if (path === "/api/auth/status")
        return route.fulfill({
          json: {
            initialized: true,
            registrationEnabled: false,
            passwordMinimumLength: 8,
          },
        });
      if (path === "/api/auth/home")
        return route.fulfill({ json: { sections: [] } });
      if (path === "/api/auth/email") {
        if (route.request().headers()["x-csrf-token"] !== `token-${epoch}`) {
          stale++;
          return route.fulfill({ status: 403, json: { code: "CSRF_INVALID" } });
        }
        expect(route.request().method()).toBe("PUT");
        saves++;
        return route.fulfill({ json: { success: true } });
      }
      unexpected.push(`${route.request().method()} ${path}`);
      return route.fulfill({ status: 501, json: { code: "E2E_UNMOCKED_API" } });
    },
  );
  await page.goto("/settings/account");
  const save = page.getByRole("button", { name: "Guardar", exact: true });
  await save.click();
  await expect.poll(() => saves).toBe(1);
  const other = await context.newPage();
  await other.goto("/settings/account");
  await expect(
    other.getByLabel("Correo electrónico", { exact: true }),
  ).toBeVisible();
  const before = reads;
  epoch++;
  await other.evaluate(() => {
    const channel = new BroadcastChannel("eplsync-session-changed");
    channel.postMessage("eplsync-session-changed");
    channel.close();
  });
  await expect.poll(() => reads).toBeGreaterThan(before);
  await save.click();
  await expect.poll(() => saves).toBe(2);
  expect(stale).toBe(0);
  expect(unexpected).toEqual([]);
});

test("logout and a different login in another tab replace the account and discard the previous form", async ({
  context,
  page,
}) => {
  const accounts = {
    reader: {
      id: "reader",
      username: "reader",
      email: "reader@example.org",
      role: "USER",
      mustChangePassword: false,
      permissions: [],
    },
    second: {
      id: "second",
      username: "second",
      email: "second@example.org",
      role: "USER",
      mustChangePassword: false,
      permissions: [],
    },
  };
  let current: typeof accounts.reader | null = accounts.reader;
  let epoch = 1;
  let logouts = 0;
  let logins = 0;
  const unexpected: string[] = [];
  await context.route(
    (url) => url.pathname.startsWith("/api/"),
    (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path === "/api/auth/me")
        return route.fulfill({
          status: current ? 200 : 401,
          json: current ?? {},
        });
      if (path === "/api/auth/status")
        return route.fulfill({
          json: {
            initialized: true,
            registrationEnabled: false,
            passwordMinimumLength: 8,
          },
        });
      if (path === "/api/auth/home")
        return route.fulfill({ json: { sections: [] } });
      if (path === "/api/auth/csrf")
        return route.fulfill({
          json: { token: `token-${epoch}`, headerName: "X-CSRF-TOKEN" },
        });
      if (path === "/api/auth/logout") {
        expect(route.request().method()).toBe("POST");
        expect(route.request().headers()["x-csrf-token"]).toBe(
          `token-${epoch}`,
        );
        current = null;
        epoch++;
        logouts++;
        return route.fulfill({ json: { success: true } });
      }
      if (path === "/api/auth/login") {
        expect(route.request().method()).toBe("POST");
        expect(route.request().headers()["x-csrf-token"]).toBe(
          `token-${epoch}`,
        );
        expect(route.request().postDataJSON()).toEqual({
          username: "second",
          password: "second-password",
        });
        current = accounts.second;
        epoch++;
        logins++;
        return route.fulfill({ json: current });
      }
      unexpected.push(`${route.request().method()} ${path}`);
      return route.fulfill({ status: 501, json: { code: "E2E_UNMOCKED_API" } });
    },
  );
  await page.goto("/settings/account");
  await page
    .getByLabel("Correo electrónico", { exact: true })
    .fill("unsaved@example.org");
  const other = await context.newPage();
  await other.goto("/settings/account");
  await expect(
    other.getByLabel("Correo electrónico", { exact: true }),
  ).toHaveValue(accounts.reader.email);
  await other
    .locator("#sidebar")
    .getByRole("button", { name: "reader", exact: true })
    .click();
  await other
    .getByRole("menuitem", { name: "Cerrar sesión", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Iniciar sesión", exact: true }),
  ).toBeVisible();
  await expect(
    other.getByRole("button", { name: "Iniciar sesión", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByLabel("Correo electrónico", { exact: true }),
  ).toHaveCount(0);
  await other.getByLabel("Nombre de usuario", { exact: true }).fill("second");
  await other.getByLabel("Contraseña", { exact: true }).fill("second-password");
  await other
    .getByRole("button", { name: "Iniciar sesión", exact: true })
    .click();
  for (const tab of [page, other]) {
    await expect(
      tab.getByLabel("Correo electrónico", { exact: true }),
    ).toHaveValue(accounts.second.email);
    await expect(
      tab
        .locator("#sidebar")
        .getByRole("button", { name: "second", exact: true }),
    ).toBeVisible();
    await expect(
      tab
        .locator("#sidebar")
        .getByRole("button", { name: "reader", exact: true }),
    ).toHaveCount(0);
  }
  expect(logouts).toBe(1);
  expect(logins).toBe(1);
  expect(unexpected).toEqual([]);
});

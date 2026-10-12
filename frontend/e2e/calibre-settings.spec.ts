import { test, expect } from "./fixtures";
test.beforeEach(async ({ page }) => {
  await page.route("**/api/settings/calibre/snapshot", (route) =>
    route.fulfill({ json: { snapshot: null } }),
  );
});

test("checks form credentials and saves the selected library", async ({
  page,
}) => {
  let saved = { url: "", username: "", passwordConfigured: false, library: "" };
  await page.route("**/api/settings/calibre", (route) => {
    if (route.request().method() === "PUT") {
      const input = route.request().postDataJSON();
      expect(input.library).toBe("main");
      expect(input.password).toBe("example-password");
      saved = {
        url: input.url,
        username: input.username,
        passwordConfigured: true,
        library: input.library,
      };
    }
    return route.fulfill({ json: saved });
  });
  await page.route("**/api/settings/calibre/connection", (route) => {
    expect(route.request().postDataJSON()).toMatchObject({
      url: "http://localhost:8080",
      username: "reader",
      password: "example-password",
    });
    return route.fulfill({
      json: {
        connected: true,
        libraries: [{ id: "main", name: "Main library" }],
        defaultLibrary: "main",
      },
    });
  });
  await page.goto("/settings/calibre");
  await page
    .getByRole("textbox", {
      name: "URL del servidor de contenido",
      exact: true,
    })
    .fill("http://localhost:8080");
  await page.getByLabel("Usuario", { exact: true }).fill("reader");
  await page.getByLabel("Contraseña", { exact: true }).fill("example-password");
  await page
    .getByRole("button", { name: "Comprobar conexión", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Conexión correcta");
  await page.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect(page.getByLabel("Contraseña", { exact: true })).toHaveValue("");
  await expect(page.getByRole("heading", {name: "Libros de Calibre", exact: true})).toHaveCount(0);
  await expect(page.getByRole("button", {name: "Sincronizar biblioteca", exact: true})).toBeEnabled();
  await page
    .getByRole("textbox", {
      name: "URL del servidor de contenido",
      exact: true,
    })
    .fill("http://other:8081");
  await expect(
    page.getByText(
      "Al cambiar la URL o el usuario debes introducir las credenciales correspondientes antes de guardar.",
    ),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Sincronizar biblioteca", exact: true }),
  ).toBeDisabled();
});

test("shows only the latest error when saving and checking cleared credentials", async ({
  page,
}) => {
  const error = {
    status: 502,
    json: {
      details:
        "Calibre ha rechazado el acceso; revisa el usuario, la contraseña y los permisos de biblioteca",
    },
  };
  await page.route("**/api/settings/calibre", (route) =>
    route.fulfill(
      route.request().method() === "PUT"
        ? error
        : {
            json: {
              url: "http://localhost:8080",
              username: "reader",
              passwordConfigured: true,
              library: "main",
            },
          },
    ),
  );
  await page.route("**/api/settings/calibre/connection", (route) =>
    route.fulfill(error),
  );
  await page.goto("/settings/calibre");
  await page
    .getByRole("checkbox", {
      name: "Eliminar contraseña guardada",
      exact: true,
    })
    .check();
  await page.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect(page.getByRole("alert")).toHaveCount(1);
  await page
    .getByRole("button", { name: "Comprobar conexión", exact: true })
    .click();
  await expect(page.getByRole("alert")).toHaveCount(1);
  await expect(page.getByRole("alert")).toContainText(
    "Calibre ha rechazado el acceso",
  );
  await page.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect(page.getByRole("alert")).toHaveCount(1);
});

test("clears an earlier sync failure after checking the connection successfully", async ({ page }) => {
  await page.route("**/api/settings/calibre", route => route.fulfill({json: {
    url: "http://localhost:8080", username: "reader", passwordConfigured: true, library: "main",
  }}));
  await page.route("**/api/settings/calibre/sync", route => route.fulfill({status: 502, json: {details: "Calibre ha rechazado el acceso"}}));
  let connected = false;
  await page.route("**/api/settings/calibre/connection", route => route.fulfill(connected
    ? {json: {connected: true, libraries: [{id: "main", name: "Main"}], defaultLibrary: "main"}}
    : {status: 502, json: {details: "Calibre ha rechazado el acceso"}}));
  await page.goto("/settings/calibre");
  await page.getByRole("button", {name: "Sincronizar biblioteca", exact: true}).click();
  await expect(page.getByRole("alert")).toHaveCount(1);
  await page.getByRole("button", {name: "Comprobar conexión", exact: true}).click();
  await expect(page.getByRole("alert")).toHaveCount(1);
  connected = true;
  await page.getByRole("button", {name: "Comprobar conexión", exact: true}).click();
  await expect(page.getByRole("alert")).toHaveCount(0);
  await expect(page.getByRole("status")).toContainText("Conexión correcta");
});

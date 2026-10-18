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
      expect(input.browserUrl).toBe("https://books.example/calibre");
      saved = {
        url: input.url,
        username: input.username,
        passwordConfigured: true,
        library: input.library,
        browserUrl: input.browserUrl,
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
  await page
    .getByLabel("URL para abrir en el navegador", { exact: true })
    .fill("https://books.example/calibre");
  await page.getByLabel("Usuario", { exact: true }).fill("reader");
  await page.getByLabel("Contraseña", { exact: true }).fill("example-password");
  await page
    .getByRole("button", { name: "Comprobar conexión", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Conexión correcta");
  await page
    .getByRole("region", { name: "Configuración de Calibre", exact: true })
    .getByRole("button", { name: "Guardar", exact: true })
    .click();
  await expect(page.getByLabel("Contraseña", { exact: true })).toHaveValue("");
  await expect(
    page.getByRole("heading", { name: "Libros de Calibre", exact: true }),
  ).toHaveCount(0);
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
    page.getByRole("heading", {
      name: "Sincronización con el catálogo",
      exact: true,
    }),
  ).toHaveCount(0);
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
  await page
    .getByRole("region", { name: "Configuración de Calibre", exact: true })
    .getByRole("button", { name: "Guardar", exact: true })
    .click();
  await expect(page.getByRole("alert")).toHaveCount(1);
  await page
    .getByRole("button", { name: "Comprobar conexión", exact: true })
    .click();
  await expect(page.getByRole("alert")).toHaveCount(1);
  await expect(page.getByRole("alert")).toContainText(
    "Calibre ha rechazado el acceso",
  );
  await page
    .getByRole("region", { name: "Configuración de Calibre", exact: true })
    .getByRole("button", { name: "Guardar", exact: true })
    .click();
  await expect(page.getByRole("alert")).toHaveCount(1);
});

for (const initial of [undefined, false] as const) {
  test(`automatic identifiers default/persistence (initial=${initial})`, async ({
    page,
  }) => {
    let enabled = initial;
    let saves = 0;
    await page.route("**/api/settings/calibre", (route) =>
      route.fulfill({
        json: {
          url: "",
          username: "",
          passwordConfigured: false,
          library: "",
          autoCompleteIdentifiers: enabled,
        },
      }),
    );
    await page.route("**/api/settings/calibre/identifiers", (route) => {
      expect(route.request().method()).toBe("PUT");
      saves++;
      enabled = route.request().postDataJSON().autoCompleteIdentifiers;
      return route.fulfill({ json: { autoCompleteIdentifiers: enabled } });
    });
    await page.goto("/settings/calibre");
    const section = page.getByRole("region", {
      name: "Identificadores",
      exact: true,
    });
    const toggle = section.getByRole("checkbox");
    if (initial === false) await expect(toggle).not.toBeChecked();
    else await expect(toggle).toBeChecked();
    await toggle.click();
    await section.getByRole("button", { name: "Guardar", exact: true }).click();
    await expect.poll(() => saves).toBe(1);
    await page.reload();
    if (initial === false) await expect(toggle).toBeChecked();
    else await expect(toggle).not.toBeChecked();
    await expect(section).toContainText("manualmente");
  });
}

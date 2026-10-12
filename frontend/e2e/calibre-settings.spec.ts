import { test, expect } from "./fixtures";

test("checks form credentials, selects a library and reads EPL identifiers", async ({
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
  await page.route("**/api/settings/calibre/books?**", (route) =>
    route.fulfill({
      json: {
        items: [
          {
            calibreId: 3,
            title: "El reino del lenguaje",
            authors: ["Tom Wolfe"],
            eplId: 80899,
            revision: 1,
            linked: true,
          },
          {
            calibreId: 9,
            title: "Other",
            authors: [],
            eplId: null,
            revision: null,
            linked: false,
          },
        ],
        total: 2,
        page: 0,
        size: 20,
      },
    }),
  );
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
  await page
    .getByRole("button", { name: "Consultar biblioteca", exact: true })
    .click();
  await expect(page.getByRole("table")).toContainText("80899");
  await expect(page.getByRole("table")).toContainText("Sin vincular");
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
    page.getByRole("button", { name: "Consultar biblioteca", exact: true }),
  ).toBeDisabled();
});

test("shows a concrete connection error", async ({ page }) => {
  await page.route("**/api/settings/calibre", (route) =>
    route.fulfill({
      json: {
        url: "http://localhost:8080",
        username: "",
        passwordConfigured: false,
        library: "",
      },
    }),
  );
  await page.route("**/api/settings/calibre/connection", (route) =>
    route.fulfill({
      status: 502,
      json: {
        details:
          "Calibre ha rechazado el acceso; revisa el usuario, la contraseña y los permisos de biblioteca",
      },
    }),
  );
  await page.goto("/settings/calibre");
  await page
    .getByRole("button", { name: "Comprobar conexión", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText(
    "Calibre ha rechazado el acceso",
  );
});

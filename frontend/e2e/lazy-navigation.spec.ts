import { test, expect } from "./fixtures";

test("production routes load on demand and navigate without runtime errors", async ({
  page,
}) => {
  const errors: string[] = [];
  const scripts: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("request", (request) => {
    if (request.resourceType() === "script") scripts.push(request.url());
  });
  await page.route("**/api/events/operations**", (route) =>
    route.fulfill({
      json: { items: [], total: 0, page: 0, size: 20, cursor: 0 },
    }),
  );
  await page.route("**/api/catalog/books?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 1, totalItems: 0, totalPages: 0 },
      },
    }),
  );
  await page.route("**/api/torrent/downloads/summary", (route) =>
    route.fulfill({ json: { total: 0, byStatus: {} } }),
  );
  await page.route("**/api/torrent/jobs?**", (route) =>
    route.fulfill({ json: { items: [], meta: { totalItems: 0 } } }),
  );
  await page.route("**/api/security/users", (route) =>
    route.fulfill({ json: [] }),
  );
  await page.route("**/api/security/permissions", (route) =>
    route.fulfill({ json: [] }),
  );
  await page.route("**/api/security/policy", (route) =>
    route.fulfill({
      json: {
        registrationEnabled: false,
        approvalRequired: true,
        idleMinutes: 30,
        maximumHours: 24,
        passwordMinimumLength: 8,
      },
    }),
  );
  await page.goto("/settings/account");
  await expect(
    page.getByRole("textbox", { name: "Correo electrónico" }),
  ).toBeVisible();
  expect(
    scripts.some((url) =>
      /\/assets\/(Home|Events|UserSettings|DatabaseReset)-/.test(url),
    ),
  ).toBe(false);
  await page
    .getByRole("link", { name: "Eventos", exact: true })
    .first()
    .click();
  await expect(
    page.getByRole("heading", { name: "Eventos", exact: true }),
  ).toBeVisible();
  expect(scripts.some((url) => /\/assets\/Events-/.test(url))).toBe(true);
  await page.goBack();
  await expect(
    page.getByRole("textbox", { name: "Correo electrónico" }),
  ).toBeVisible();
  await page
    .getByRole("link", { name: "Usuarios y seguridad", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Crear", exact: true }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Base de datos", exact: true }).click();
  await expect(
    page.getByRole("heading", {
      name: "Reiniciar toda la base de datos",
      exact: true,
    }),
  ).toBeVisible();
  await page.locator("a.brand").click();
  await expect(
    page.getByRole("heading", { name: "Novedades", exact: true }),
  ).toBeVisible();
  for (const route of ["Home", "UserSettings", "DatabaseReset"])
    expect(scripts.some((url) => url.includes(`/assets/${route}-`))).toBe(true);
  expect(errors).toEqual([]);
});

test("missing production chunks display recovery and reload the same route", async ({
  page,
}) => {
  let unavailable = true;
  await page.route(/\/assets\/Events-[^/]+\.js$/, (route) =>
    unavailable ? route.abort("connectionrefused") : route.continue(),
  );
  await page.route("**/api/events/operations**", (route) =>
    route.fulfill({
      json: { items: [], total: 0, page: 0, size: 20, cursor: 0 },
    }),
  );
  await page.goto("/events?category=SECURITY#results");
  await expect(
    page.getByRole("heading", { name: "No se ha podido cargar esta página" }),
  ).toBeVisible();
  await expect(page.locator("body")).not.toContainText(
    "Unexpected Application Error!",
  );
  unavailable = false;
  await page.getByRole("button", { name: "Recargar aplicación" }).click();
  await expect(
    page.getByRole("heading", { name: "Eventos", exact: true }),
  ).toBeVisible();
  await expect(page).toHaveURL(/\/events\?category=SECURITY#results$/);
});

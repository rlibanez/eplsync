import { test, expect } from "./fixtures";

async function installation(
  page: import("@playwright/test").Page,
  status: string,
  step = 0,
) {
  let progress = { step, status, torrent: "pending", calibre: "pending" };
  const emptyPage = {
    items: [],
    meta: {
      page: 0,
      size: 10,
      totalItems: 0,
      totalPages: 0,
      hasNext: false,
      hasPrevious: false,
    },
  };
  await page.route("**/api/catalog/books?**", (route) =>
    route.fulfill({ json: emptyPage }),
  );
  await page.route("**/api/events/operations?**", (route) =>
    route.fulfill({ json: emptyPage }),
  );
  await page.route("**/api/settings/setup", async (route) => {
    if (route.request().method() === "POST")
      progress = route.request().postDataJSON();
    await route.fulfill({ json: progress });
  });
  await page.route("**/api/settings/torrent", (route) =>
    route.fulfill({ json: { section: "torrent", fields: [] } }),
  );
  await page.route("**/api/settings/calibre", (route) =>
    route.fulfill({
      json: {
        url: "",
        username: "",
        passwordConfigured: false,
        library: "",
        browserUrl: "",
      },
    }),
  );
  await page.route("**/api/settings/calibre/snapshot", (route) =>
    route.fulfill({ json: { snapshot: null } }),
  );
  return () => progress;
}

test("new installation guides catalog, skips optional integrations and resumes from Settings", async ({
  page,
}) => {
  const progress = await installation(page, "active");
  await page.goto("/settings/general");
  await expect(page).toHaveURL(/\/settings\/setup$/);
  await expect(
    page.getByRole("button", { name: "Siguiente", exact: true }),
  ).toBeDisabled();
  let imported = false;
  await page.route("**/api/catalog/import/metadata", (route) =>
    route.fulfill({ json: { metadata: imported ? { totalRows: 1 } : null } }),
  );
  await page.route("**/api/catalog/import/source", (route) =>
    route.fulfill({
      json: { defaultUrl: "https://example.test/catalog.zip", archive: null },
    }),
  );
  await page.route("**/api/catalog/import/run", (route) => {
    expect(route.request().postDataJSON()).toEqual({
      source: "URL",
      url: "https://example.test/catalog.zip",
      dryRun: false,
    });
    imported = true;
    return route.fulfill({
      json: {
        success: true,
        recordsProcessed: 1,
        recordsCreated: 1,
        recordsUpdated: 0,
        recordsUnchanged: 0,
        errors: 0,
      },
    });
  });
  await page
    .getByRole("button", { name: "Importar catálogo", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Siguiente", exact: true }).click();
  await dialog
    .getByRole("button", { name: "Actualizar catálogo", exact: true })
    .click();
  await expect(dialog).toBeHidden();
  await expect(
    page.getByText("Catálogo importado correctamente.", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Siguiente", exact: true }),
  ).toBeEnabled();
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Configuración torrent" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Omitir", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Configuración de Calibre" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Cerrar", exact: true }).click();
  await expect(page).toHaveURL(/\/$/);
  expect(progress().status).toBe("deferred");
  expect(progress().torrent).toBe("skipped");
  await page.goto("/settings/general");
  await page
    .getByRole("button", { name: "Abrir asistente de configuración" })
    .click();
  await expect(
    page.getByRole("heading", { name: "Configuración de Calibre" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Omitir", exact: true }).click();
  await expect(
    page.locator(".import-summary").getByText("Omitido", { exact: true }),
  ).toHaveCount(2);
  await page.getByRole("button", { name: "Finalizar e ir al inicio" }).click();
  await expect(page).toHaveURL(/\/$/);
  await page.reload();
  await expect(page).toHaveURL(/\/$/);
  expect(progress().status).toBe("completed");
});

test("existing installation stays on its page and can open the wizard manually", async ({
  page,
}) => {
  const progress = await installation(page, "completed");
  await page.goto("/settings/general");
  await expect(
    page.getByRole("button", { name: "Abrir asistente de configuración" }),
  ).toBeVisible();
  await expect(page).toHaveURL(/\/settings\/general$/);
  await page
    .getByRole("button", { name: "Abrir asistente de configuración" })
    .click();
  await expect(page).toHaveURL(/\/settings\/setup$/);
  expect(progress().status).toBe("active");
  expect(progress().step).toBe(0);
});

test("sync failures keep the step and successful sync permits continuing", async ({
  page,
}) => {
  const progress = await installation(page, "active", 1);
  let attempts = 0;
  await page.route("**/api/torrent/downloads/sync", (route) => {
    expect(route.request().postDataJSON()).toEqual({
      dryRun: false,
      includeDetails: false,
    });
    return ++attempts === 1
      ? route.fulfill({
          status: 502,
          json: { details: "Cliente torrent no disponible" },
        })
      : route.fulfill({ json: { applied: true } });
  });
  await page.goto("/settings/setup");
  await expect(
    page.getByRole("button", { name: "Siguiente", exact: true }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "Sincronizar", exact: true }).click();
  await expect(page.getByText("Cliente torrent no disponible")).toBeVisible();
  expect(progress().step).toBe(1);
  await page.getByRole("button", { name: "Sincronizar", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Siguiente", exact: true }),
  ).toBeEnabled();
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  expect(progress().torrent).toBe("done");
  await expect(
    page.getByRole("heading", { name: "Configuración de Calibre" }),
  ).toBeVisible();
  await page.route("**/api/settings/calibre/sync", (route) =>
    route.fulfill({ json: {} }),
  );
  await page.getByRole("button", { name: "Sincronizar", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Siguiente", exact: true }),
  ).toBeEnabled();
  await page.reload();
  await expect(
    page.getByRole("button", { name: "Siguiente", exact: true }),
  ).toBeEnabled();
  expect(progress().calibre).toBe("done");
});

test("wizard fits a narrow screen", async ({ page }) => {
  await installation(page, "active", 2);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/settings/setup");
  await expect(
    page.getByRole("heading", { name: "Configuración de Calibre" }),
  ).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(
    390,
  );
  await page.screenshot({
    path: "/tmp/eplsync-setup-mobile.png",
    fullPage: true,
  });
});

import { test, expect, emitEvent } from "./fixtures";

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

test("wizard suppresses floating notices without replaying them after closing", async ({
  page,
}) => {
  await installation(page, "completed");
  await page.route("**/api/settings/events", (route) =>
    route.fulfill({ json: { section: "events", fields: [] } }),
  );
  // Load preferences before injecting a live event; rendering General does not
  // guarantee that this independent request has finished.
  await page.goto("/settings/notifications");
  await expect(
    page.getByRole("checkbox", {
      name: "Mostrar notificaciones flotantes",
      exact: true,
    }),
  ).toBeChecked();
  await page.getByRole("link", { name: "General", exact: true }).click();
  const toast = page.locator(".notification-toasts");
  const event = (id: number) => ({
    id,
    createdAt: new Date().toISOString(),
    category: "CATALOG",
    action: "UPDATE",
    outcome: "SUCCEEDED",
    origin: "MANUAL",
    operationId: `setup-notice-${id}`,
    details: { created: 1 },
  });
  await expect(
    page.getByRole("button", { name: "Abrir asistente de configuración" }),
  ).toBeVisible();
  await emitEvent(page, event(100));
  await expect(toast.getByRole("status")).toHaveCount(1);
  await page
    .getByRole("button", { name: "Abrir asistente de configuración" })
    .click();
  await expect(page).toHaveURL(/\/settings\/setup$/);
  await expect(toast.getByRole("status")).toHaveCount(0);
  await emitEvent(page, event(101));
  await expect(toast.getByRole("status")).toHaveCount(0);
  await page.getByRole("button", { name: "Cerrar", exact: true }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(toast.getByRole("status")).toHaveCount(0);
  await emitEvent(page, event(102));
  await expect(toast.getByRole("status")).toHaveCount(1);
});

for (const kind of ["torrent", "calibre"] as const) {
  test(`${kind}: sync requires saving changes and credentials`, async ({
    page,
  }) => {
    await installation(page, "active", kind === "torrent" ? 1 : 2);
    let username = "original",
      saveFails = true,
      syncs = 0;
    const field = (
      key: string,
      type: string,
      value: string | boolean,
      configured = false,
    ) => ({ key, type, value, configured, overridden: false });
    const view = () =>
      kind === "torrent"
        ? {
            section: "torrent",
            fields: [
              field("torrent.enabled", "boolean", true),
              field("torrent.base-url", "text", "http://torrent.test:8090"),
              field("torrent.qbittorrent.auth.username", "text", username),
              field("torrent.qbittorrent.auth.password", "secret", "", true),
            ],
          }
        : {
            url: "http://calibre.test:8081",
            username,
            passwordConfigured: true,
            library: "main",
            browserUrl: "",
          };
    await page.route(`**/api/settings/${kind}`, (route) => {
      if (route.request().method() === "PUT") {
        if (saveFails)
          return route.fulfill({
            status: 502,
            json: { details: "Guardado rechazado en la prueba" },
          });
        username = route.request().postDataJSON()[
          kind === "torrent" ? "torrent.qbittorrent.auth.username" : "username"
        ];
      }
      return route.fulfill({ json: view() });
    });
    await page.route(`**/api/settings/${kind}/connection`, (route) =>
      route.fulfill({
        json:
          kind === "torrent"
            ? {
                enabled: true,
                connected: true,
                client: "qbittorrent",
                authMode: "session",
                version: "5",
                apiVersion: "2",
              }
            : {
                connected: true,
                libraries: [{ id: "main", name: "Main" }],
                defaultLibrary: "main",
              },
      }),
    );
    await page.route(
      kind === "torrent"
        ? "**/api/torrent/downloads/sync"
        : "**/api/settings/calibre/sync",
      (route) => {
        syncs++;
        return route.fulfill({
          status: 502,
          json: { details: "Fallo inicial de sincronización" },
        });
      },
    );
    await page.goto("/settings/setup");
    const sync = page.getByRole("button", { name: "Sincronizar", exact: true });
    const user = page.getByRole("textbox", { name: "Usuario", exact: true });
    const notice = page.getByText(
      "Guarda la configuración antes de sincronizar.",
      { exact: true },
    );
    await expect(sync).toBeEnabled();
    await sync.click();
    await expect(
      page.getByText("Fallo inicial de sincronización", { exact: true }),
    ).toBeVisible();
    await user.fill("edited");
    await expect(sync).toBeDisabled();
    await expect(notice).toBeVisible();
    await expect(
      page.getByText("Fallo inicial de sincronización", { exact: true }),
    ).toHaveCount(0);
    await page
      .getByRole("button", { name: "Comprobar conexión", exact: true })
      .click();
    await expect(sync).toBeDisabled();
    await expect(notice).toBeVisible();
    expect(syncs).toBe(1);
    await user.fill("original");
    await expect(sync).toBeEnabled();
    await expect(notice).toHaveCount(0);
    const password = page.getByLabel("Contraseña", { exact: true });
    await password.fill("replacement-test-only");
    await expect(sync).toBeDisabled();
    await password.fill("");
    await expect(sync).toBeEnabled();
    if (kind === "calibre") {
      const clear = page.getByRole("checkbox", {
        name: "Eliminar contraseña guardada",
        exact: true,
      });
      await clear.check();
      await expect(sync).toBeDisabled();
      await clear.uncheck();
      await expect(sync).toBeEnabled();
    } else {
      await page
        .getByRole("button", { name: "Borrar credencial", exact: true })
        .click();
      await expect(sync).toBeDisabled();
      await password.fill("replacement-test-only");
      await password.fill("");
      await expect(sync).toBeEnabled();
    }
    await user.fill("saved-user");
    const save = page
      .getByRole("button", { name: "Guardar", exact: true })
      .first();
    await save.click();
    await expect(
      page.getByText("Guardado rechazado en la prueba", { exact: true }),
    ).toBeVisible();
    await expect(sync).toBeDisabled();
    saveFails = false;
    await save.click();
    await expect(sync).toBeEnabled();
    await expect(notice).toHaveCount(0);
    await expect(user).toHaveValue("saved-user");
    await sync.click();
    await expect.poll(() => syncs).toBe(2);
  });
}

test("Calibre library chosen by connection check must be saved before sync", async ({
  page,
}) => {
  await installation(page, "active", 2);
  let library = "old-library";
  await page.route("**/api/settings/calibre", (route) => {
    if (route.request().method() === "PUT")
      library = route.request().postDataJSON().library;
    return route.fulfill({
      json: {
        url: "http://calibre.test:8081",
        username: "",
        passwordConfigured: false,
        library,
        browserUrl: "",
      },
    });
  });
  await page.route("**/api/settings/calibre/connection", (route) =>
    route.fulfill({
      json: {
        connected: true,
        libraries: [{ id: "main", name: "Main" }],
        defaultLibrary: "main",
      },
    }),
  );
  await page.goto("/settings/setup");
  const sync = page.getByRole("button", { name: "Sincronizar", exact: true });
  await expect(sync).toBeEnabled();
  await page
    .getByRole("button", { name: "Comprobar conexión", exact: true })
    .click();
  await expect(
    page.getByRole("textbox", { name: "Biblioteca", exact: true }),
  ).toHaveValue("Main");
  await expect(sync).toBeDisabled();
  await expect(
    page.getByText("Guarda la configuración antes de sincronizar.", {
      exact: true,
    }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Guardar", exact: true })
    .first()
    .click();
  await expect(sync).toBeEnabled();
  expect(library).toBe("main");
});

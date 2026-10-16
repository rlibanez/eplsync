import { test, expect } from "./fixtures";

test("creates, edits and toggles cron tasks and changes required check periods", async ({
  page,
}) => {
  const tasks: Record<string, unknown>[] = [];
  const internal = [
    { id: "torrent-cleanup", interval: "30s" },
    { id: "calibre-download-check", interval: "30s" },
  ];
  const changes: Record<string, unknown>[] = [];
  await page.route("**/api/settings/tasks**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/cron")) {
      const cron = new URL(route.request().url()).searchParams.get("cron")!;
      if (cron === "bad")
        return route.fulfill({
          status: 400,
          json: { details: "Cron inválido" },
        });
      return route.fulfill({
        json: {
          expression:
            cron.trim().split(/\s+/).length === 5 ? `0 ${cron}` : cron,
          timezone: "Europe/Madrid",
          executions: [
            "2026-10-15T03:00:00Z",
            "2026-10-16T03:00:00Z",
            "2026-10-17T03:00:00Z",
            "2026-10-18T03:00:00Z",
            "2026-10-19T03:00:00Z",
          ],
        },
      });
    }
    if (route.request().method() === "GET")
      return route.fulfill({
        json: { tasks, internal, defaultTimezone: "Europe/Madrid" },
      });
    if (path.endsWith("/restore")) {
      const item = internal.find((period) => path.includes(period.id))!;
      item.interval = "30s";
      return route.fulfill({ json: item });
    }
    const input = route.request().postDataJSON();
    changes.push(input);
    expect(input).not.toHaveProperty("timezone");
    expect(input).not.toHaveProperty("ownerId");
    if (path.includes("/internal/")) {
      expect(Object.keys(input)).toEqual(["interval"]);
      const item = internal.find((period) => path.endsWith(period.id))!;
      if (["9", "9s"].includes(input.interval))
        return route.fulfill({
          status: 400,
          json: { details: "Introduce un intervalo entre 10s y 365 días" },
        });
      item.interval =
        input.interval === "1m"
          ? "60s"
          : /^\d+$/.test(input.interval)
            ? `${input.interval}s`
            : input.interval;
      return route.fulfill({ json: item });
    }
    if (path.endsWith("/notifications")) {
      tasks[0].telegramNotifications = input.enabled;
      return route.fulfill({ json: tasks[0] });
    }
    if (path.endsWith("/enabled")) {
      tasks[0].enabled = input.enabled;
      tasks[0].nextRun = input.enabled ? "2026-10-15T01:00:00Z" : null;
      return route.fulfill({ json: tasks[0] });
    }
    if (input.cron === "bad")
      return route.fulfill({
        status: 400,
        json: { details: "Cron o zona horaria inválidos" },
      });
    const task = {
      ...input,
      timezone: "Europe/Madrid",
      id: "task-1",
      ownerId: "test-admin",
      ownerUsername: "admin",
      running: false,
      nextRun: "2026-10-15T01:00:00Z",
      lastRun: null,
      lastFinished: null,
      lastResult: null,
      lastMessage: null,
    };
    tasks.splice(0, tasks.length, task);
    return route.fulfill({ json: task });
  });
  await page.goto("/settings/tasks");
  await expect(
    page.getByRole("link", { name: "Tareas programadas", exact: true }),
  ).toBeVisible();
  await expect(page.getByText("No hay tareas programadas.")).toBeVisible();
  const required = page.locator(".settings-section").filter({
    has: page.getByRole("heading", { name: "Comprobaciones de trabajos" }),
  });
  await expect(required.getByRole("switch")).toHaveCount(0);
  await required
    .getByRole("textbox", { name: "Descargas pendientes para Calibre" })
    .fill("1m");
  await required
    .getByRole("button", { name: "Guardar", exact: true })
    .nth(1)
    .click();
  await expect(
    required.getByRole("textbox", {
      name: "Descargas pendientes para Calibre",
    }),
  ).toHaveValue("60s");
  await required
    .getByRole("button", { name: "Restablecer", exact: true })
    .nth(1)
    .click();
  await expect(
    required.getByRole("textbox", {
      name: "Descargas pendientes para Calibre",
    }),
  ).toHaveValue("30s");
  await required
    .getByRole("textbox", { name: "Descargas pendientes para Calibre" })
    .fill("9");
  await required
    .getByRole("button", { name: "Guardar", exact: true })
    .nth(1)
    .click();
  await expect(required.getByRole("alert")).toContainText("10s");
  await required
    .getByRole("textbox", { name: "Descargas pendientes para Calibre" })
    .fill("30");
  await required
    .getByRole("button", { name: "Guardar", exact: true })
    .nth(1)
    .click();
  await expect(
    required.getByRole("textbox", {
      name: "Descargas pendientes para Calibre",
    }),
  ).toHaveValue("30s");
  await page.getByRole("button", { name: "Crear tarea", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByRole("checkbox", {
      name: "Enviar notificaciones de Telegram para esta tarea",
    }),
  ).toBeChecked();
  await dialog
    .getByRole("checkbox", {
      name: "Enviar notificaciones de Telegram para esta tarea",
    })
    .uncheck();
  await expect(dialog.getByRole("textbox", { name: /^Operación/ })).toHaveValue(
    "",
  );
  await expect(dialog.getByRole("textbox", { name: /^Cron/ })).toHaveValue(
    "0 5 * * *",
  );
  await expect(
    dialog.getByRole("textbox", { name: /^Zona horaria/ }),
  ).toHaveCount(0);
  await expect(
    dialog.getByRole("link", { name: "crontab.guru" }),
  ).toHaveAttribute("href", "https://crontab.guru/");
  await expect(
    dialog.getByRole("link", { name: "Formato con segundos" }),
  ).toHaveAttribute(
    "href",
    "https://docs.spring.io/spring-framework/reference/integration/scheduling.html#scheduling-cron-expression",
  );
  await expect(page.getByRole("tooltip")).toHaveCount(0);
  await dialog.getByRole("button", { name: "Información del cron" }).hover();
  const tooltip = page.getByRole("tooltip");
  await expect(tooltip).toContainText(
    "Próximas cinco ejecuciones (Europe/Madrid)",
  );
  await expect(tooltip.locator("ol li")).toHaveCount(5);
  await expect(tooltip).toContainText("05:00, cada día");
  await dialog.getByRole("textbox", { name: /^Nombre/ }).hover();
  await expect(tooltip).toBeHidden();
  await dialog
    .getByRole("textbox", { name: /^Nombre/ })
    .fill("Calibre nocturno");
  await expect(
    dialog.getByRole("button", { name: "Guardar", exact: true }),
  ).toBeDisabled();
  await dialog.getByRole("textbox", { name: /^Operación/ }).click();
  await expect(
    page.getByRole("option", {
      name: "Buscar actualizaciones del catálogo",
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    page.getByRole("option", {
      name: "Buscar actualizaciones de Calibre",
      exact: true,
    }),
  ).toBeVisible();
  await page
    .getByRole("option", { name: "Sincronizar Calibre", exact: true })
    .click();
  await expect(dialog.getByRole("textbox", { name: /^Usuario/ })).toHaveCount(
    0,
  );
  await dialog.getByRole("textbox", { name: /^Cron/ }).fill("bad");
  await expect(
    dialog.getByText("Cron inválido", { exact: true }),
  ).toBeVisible();
  await expect(
    dialog.getByRole("button", { name: "Guardar", exact: true }),
  ).toBeDisabled();
  await dialog.getByRole("textbox", { name: /^Cron/ }).fill("0 5 * * *");
  await expect(
    dialog.getByRole("button", { name: "Guardar", exact: true }),
  ).toBeEnabled();
  await dialog.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect(dialog).toBeHidden();
  const row = page.locator("tbody tr").filter({ hasText: "Calibre nocturno" });
  await expect(row).toContainText("admin");
  await row
    .getByRole("switch", { name: /^Activar o desactivar (?!Telegram)/ })
    .press("Space");
  await expect(
    row.getByRole("switch", { name: /^Activar o desactivar (?!Telegram)/ }),
  ).not.toBeChecked();
  await expect(
    page.getByRole("columnheader", { name: "Zona horaria", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("columnheader", { name: /^Notificaciones/ }),
  ).toBeVisible();
  const notificationToggle = row.getByRole("switch", {
    name: /Activar o desactivar Telegram/,
  });
  await expect(notificationToggle).not.toBeChecked();
  await notificationToggle.press("Space");
  await expect(notificationToggle).toBeChecked();
  await notificationToggle.press("Space");
  await expect(notificationToggle).not.toBeChecked();
  expect(tasks[0].telegramNotifications).toBe(false);
  await row.getByRole("button", { name: "Editar tarea" }).click();
  await expect(
    dialog.getByRole("checkbox", {
      name: "Enviar notificaciones de Telegram para esta tarea",
    }),
  ).not.toBeChecked();
  await dialog
    .getByRole("textbox", { name: /^Nombre/ })
    .fill("Calibre semanal");
  await dialog.getByRole("textbox", { name: /^Cron/ }).fill("0 0 4 * * MON");
  await expect(
    dialog.getByRole("button", { name: "Guardar", exact: true }),
  ).toBeEnabled();
  await dialog.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(page.locator("tbody tr")).toContainText("Calibre semanal");
  await page
    .locator("tbody tr")
    .getByRole("switch", { name: /^Activar o desactivar (?!Telegram)/ })
    .press("Space");
  await expect(
    page
      .locator("tbody tr")
      .getByRole("switch", { name: /^Activar o desactivar (?!Telegram)/ }),
  ).toBeChecked();
  expect(
    changes.some((change) => change.operation === "CALIBRE_SYNC"),
  ).toBeTruthy();
  await page.locator(".table-scroll").evaluate((el) => {
    el.scrollLeft = 0;
  });
  await page.getByRole("button", { name: "Crear tarea", exact: true }).click();
  await dialog
    .getByRole("textbox", { name: /^Nombre/ })
    .fill("Catálogo diario");
  await dialog.getByRole("textbox", { name: /^Operación/ }).click();
  await page
    .getByRole("option", { name: "Importar catálogo", exact: true })
    .click();
  await expect(
    dialog.getByRole("button", { name: "Guardar", exact: true }),
  ).toBeEnabled();
  await dialog.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(page.locator("tbody tr")).toContainText("Importar catálogo");
  await page.setViewportSize({ width: 2400, height: 1100 });
  const table = page.locator(".scheduled-tasks-table");
  const container = table.locator("..");
  await expect
    .poll(
      async () =>
        (await table.boundingBox())!.width -
        (await container.boundingBox())!.width,
    )
    .toBeGreaterThanOrEqual(-1);
  for (const field of ["next", "duration", "enabled", "notifications"]) {
    const heading = table.locator(`th[data-table-column="${field}"]`);
    await expect(heading).toHaveCSS("white-space", "nowrap");
    await expect
      .poll(() => heading.evaluate((el) => el.scrollWidth - el.clientWidth))
      .toBeLessThanOrEqual(1);
  }
  for (const field of ["cron", "cycle"]) {
    await expect
      .poll(
        async () =>
          (await table
            .locator(`th[data-table-column="${field}"]`)
            .boundingBox())!.width,
      )
      .toBeLessThan(155);
  }
  const header = page.locator('th[data-table-column="name"]');
  const before = (await header.boundingBox())!.width;
  const handle = header.getByRole("button", {
    name: "Ajustar ancho de Nombre",
  });
  const bounds = (await handle.boundingBox())!;
  await page.mouse.move(
    bounds.x + bounds.width / 2,
    bounds.y + bounds.height / 2,
  );
  await page.mouse.down();
  await page.mouse.move(
    bounds.x + bounds.width / 2 + 70,
    bounds.y + bounds.height / 2,
  );
  await page.mouse.up();
  await expect
    .poll(async () => (await header.boundingBox())!.width)
    .toBeGreaterThan(before + 50);
  const resized = (await header.boundingBox())!.width;
  await page.reload();
  await expect
    .poll(async () => Math.abs(((await header.boundingBox())?.width ?? 0) - resized))
    .toBeLessThan(2);
  await page.screenshot({
    path: "/tmp/eplsync-scheduled-tasks.png",
    fullPage: true,
  });
});

test("task settings are restricted to administrators", async ({ page }) => {
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      json: {
        id: "reader",
        username: "reader",
        role: "USER",
        permissions: ["CATALOG_READ"],
        mustChangePassword: false,
      },
    }),
  );
  await page.goto("/settings/tasks");
  await expect(
    page.getByText("No tienes permiso para acceder a este apartado"),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Tareas programadas", exact: true }),
  ).toHaveCount(0);
});

test("shows task execution data and runs once without changing its schedule", async ({
  page,
}) => {
  const task = {
    id: "run-task",
    name: "Sincronizar biblioteca",
    operation: "CALIBRE_SYNC",
    cron: "0 5 * * *",
    timezone: "Europe/Madrid",
    ownerUsername: "admin",
    enabled: false,
    running: false,
    nextRun: null,
    lastRun: "2026-10-15T03:00:00Z",
    lastFinished: "2026-10-15T03:00:02Z",
    lastResult: "SUCCEEDED",
    lastMessage: null,
  };
  let runs = 0;
  await page.route("**/api/settings/tasks**", async (route) => {
    if (route.request().method() === "POST") {
      expect(new URL(route.request().url()).pathname).toBe(
        "/api/settings/tasks/run-task/run",
      );
      runs++;
      task.running = true;
      return route.fulfill({ status: 202, json: task });
    }
    return route.fulfill({
      json: { tasks: [task], internal: [], defaultTimezone: "Europe/Madrid" },
    });
  });
  await page.goto("/settings/tasks");
  await expect(
    page.getByRole("columnheader", { name: /^Duración/ }),
  ).toBeVisible();
  await expect(
    page.getByRole("cell", { name: "2 s", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Ejecutar ahora" }).click();
  await expect(
    page.getByRole("button", { name: "Ejecutar ahora" }),
  ).toBeDisabled();
  expect(runs).toBe(1);
  expect(task.nextRun).toBeNull();
  await expect(
    page.getByRole("cell", { name: "En ejecución", exact: true }),
  ).toBeVisible();
});

for (const operation of ["TORRENT_AUTO_UPDATE", "CALIBRE_AUTO_UPDATE"]) {
  test(`configures ${operation} with an explicit automation wizard`, async ({
    page,
  }) => {
    const configuration = {
      updateCatalog: false,
      limit: 100,
      states: ["DOWNLOADED"],
      options: {
        hash: null,
        start: true,
        savePath: "",
        rename: { enabled: false, pattern: "{title}" },
        qbittorrent: { category: "", tags: [], autoManagement: true },
      },
      concurrency: 2,
      batchSize: 100,
      interval: "500ms",
      hashes: "first",
      torrentCleanup: "keep",
      calibreCleanup: "KEEP",
      maxRetries: 1,
      library: null,
      torrentTarget: null,
      calibreTarget: null,
    };
    const tasks: Record<string, unknown>[] = [];
    let submitted: Record<string, any> | null = null;
    await page.route("**/api/settings/tasks**", async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path.endsWith("/automation-defaults"))
        return route.fulfill({ json: configuration });
      if (path.endsWith("/cron"))
        return route.fulfill({
          json: {
            expression: "0 0 5 * * *",
            timezone: "UTC",
            executions: ["2026-10-16T05:00:00Z"],
          },
        });
      if (route.request().method() === "GET")
        return route.fulfill({
          json: {
            tasks,
            internal: [],
            defaultTimezone: "UTC",
            automations: submitted
              ? { task: { configuration: submitted.automation, cycle: null } }
              : {},
          },
        });
      submitted = route.request().postDataJSON();
      const task = {
        ...submitted,
        id: "task",
        timezone: "UTC",
        ownerUsername: "admin",
        running: false,
        lastRun: null,
        lastFinished: null,
        nextRun: "2026-10-16T05:00:00Z",
        lastResult: null,
        lastMessage: null,
      };
      tasks.push(task);
      return route.fulfill({ json: task });
    });
    await page.goto("/settings/tasks");
    await page
      .getByRole("button", { name: "Crear tarea", exact: true })
      .click();
    await page
      .getByRole("textbox", { name: "Nombre", exact: true })
      .fill("Mi automatización");
    await page.getByRole("textbox", { name: "Operación", exact: true }).click();
    await page
      .getByRole("option", {
        name:
          operation === "TORRENT_AUTO_UPDATE"
            ? "Actualizar descargas"
            : "Actualizar biblioteca de Calibre",
        exact: true,
      })
      .click();
    await expect(
      page.getByRole("textbox", { name: "Máximo de libros por ciclo" }),
    ).toHaveValue("100");
    await page
      .getByRole("textbox", { name: "Máximo de libros por ciclo" })
      .fill("20");
    await expect(
      page.getByRole("textbox", { name: "Título contiene (opcional)" }),
    ).toHaveCount(0);
    await expect(
      page.getByRole("textbox", { name: "Autor contiene (opcional)" }),
    ).toHaveCount(0);
    await page.getByRole("button", { name: "Siguiente", exact: true }).click();
    await page
      .getByRole("textbox", { name: "Categoría", exact: true })
      .fill("Automáticos");
    await page.getByRole("button", { name: "Siguiente", exact: true }).click();
    if (operation === "CALIBRE_AUTO_UPDATE") {
      await page
        .getByRole("textbox", {
          name: "Revisiones anteriores en Calibre",
          exact: true,
        })
        .click();
      await page
        .getByRole("option", { name: "Eliminar el EPUB anterior", exact: true })
        .click();
    }
    await page.getByRole("button", { name: "Siguiente", exact: true }).click();
    await expect(page.getByText("Hasta 20 libros por ciclo.")).toBeVisible();
    if (operation === "CALIBRE_AUTO_UPDATE") {
      await expect(
        page.getByRole("button", { name: "Guardar", exact: true }),
      ).toBeDisabled();
      await page
        .getByRole("checkbox", {
          name: "Confirmo la eliminación automática",
          exact: false,
        })
        .check();
    }
    await page
      .getByRole("textbox", { name: "Cron", exact: true })
      .fill("0 5 * * *");
    await page.getByRole("button", { name: "Guardar", exact: true }).click();
    await expect(
      page.getByRole("cell", { name: "Mi automatización", exact: true }),
    ).toBeVisible();
    expect(submitted!.automation.limit).toBe(20);
    expect(submitted!.automation).not.toHaveProperty("author");
    expect(submitted!.automation).not.toHaveProperty("title");
    expect(submitted!.automation.options.qbittorrent.category).toBe(
      "Automáticos",
    );
    expect(submitted!.automation.torrentCleanup).toBe("keep");
    expect(submitted!.operation).toBe(operation);
    await page
      .getByRole("button", { name: "Editar tarea", exact: true })
      .click();
    await expect(
      page.getByRole("textbox", { name: "Máximo de libros por ciclo" }),
    ).toHaveValue("20");
  });
}

test("confirms task deletion and protects pending tasks and duplicate automations", async ({
  page,
}) => {
  let tasks = [
    {
      id: "delete",
      name: "Eliminar esta tarea",
      operation: "TORRENT_SYNC",
      running: false,
    },
    {
      id: "running",
      name: "Tarea ejecutándose",
      operation: "CALIBRE_SYNC",
      running: true,
    },
    {
      id: "pending",
      name: "Automatización pendiente",
      operation: "TORRENT_AUTO_UPDATE",
      running: false,
    },
    {
      id: "disabled",
      name: "Automatización desactivada",
      operation: "CALIBRE_AUTO_UPDATE",
      running: false,
    },
  ].map((task) => ({
    ...task,
    cron: "0 5 * * *",
    timezone: "UTC",
    ownerUsername: "admin",
    enabled: false,
    nextRun: null,
    lastRun: null,
    lastFinished: null,
    lastResult: null,
    lastMessage: null,
  }));
  let deletions = 0;
  await page.route("**/api/settings/tasks**", async (route) => {
    if (new URL(route.request().url()).pathname.endsWith("/cron")) {
      return route.fulfill({
        json: {
          expression: "0 0 5 * * *",
          timezone: "UTC",
          executions: ["2026-10-16T05:00:00Z"],
        },
      });
    }
    if (route.request().method() === "DELETE") {
      expect(new URL(route.request().url()).pathname).toBe(
        "/api/settings/tasks/delete",
      );
      deletions++;
      tasks = tasks.filter((task) => task.id !== "delete");
      return route.fulfill({ status: 204 });
    }
    return route.fulfill({
      json: {
        tasks,
        internal: [],
        defaultTimezone: "UTC",
        automations: {
          pending: {
            cycle: { id: "cycle", state: "WAITING", jobs: [], message: null },
          },
        },
      },
    });
  });
  await page.goto("/settings/tasks");
  for (const name of ["Tarea ejecutándose", "Automatización pendiente"]) {
    await expect(
      page
        .getByRole("row")
        .filter({ hasText: name })
        .getByRole("button", { name: "Eliminar…", exact: true }),
    ).toBeDisabled();
  }
  const remove = () =>
    page
      .getByRole("row")
      .filter({ hasText: "Eliminar esta tarea" })
      .getByRole("button", { name: "Eliminar…", exact: true })
      .click();
  await remove();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText(
    "Los trabajos y los Eventos generados se conservarán",
  );
  expect(deletions).toBe(0);
  await dialog.getByRole("button", { name: "Cancelar", exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(deletions).toBe(0);
  await remove();
  await dialog.getByRole("button", { name: "Eliminar…", exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(
    page.getByRole("row").filter({ hasText: "Eliminar esta tarea" }),
  ).toHaveCount(0);
  expect(deletions).toBe(1);
  await page.getByRole("button", { name: "Crear tarea", exact: true }).click();
  await dialog.getByRole("textbox", { name: "Operación", exact: true }).click();
  for (const name of [
    "Actualizar descargas",
    "Actualizar biblioteca de Calibre",
  ]) {
    await expect(
      page.getByRole("option", { name, exact: true }),
    ).toHaveAttribute("data-combobox-disabled", "true");
  }
});
